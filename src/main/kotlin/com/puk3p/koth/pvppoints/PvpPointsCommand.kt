package com.puk3p.koth.pvppoints

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.hooks.FactionRef
import com.puk3p.koth.hooks.FactionsHook
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor

/**
 * Points belong to factions; a player name always means that player's faction.
 *
 * /pvppoints                                  your faction's points and rank
 * /pvppoints <player|faction>                 another faction's points
 * /pvppoints top                              the factions with the most points
 * /pvppoints add <player> <amount>            to the player's faction (like the KoTH win rewards)
 * /pvppoints take|set <player|faction> <amount>
 * /pvppoints history <player|faction> [count] latest changes and why (MySQL storage only)
 * /pvppoints reset [confirm]                  every faction back to 0 (end of a wipe)
 *
 * take fails without changing anything when the faction has fewer points than asked, so a shop
 * that runs it can rely on the result. Players are found online or in Factions' data, factions
 * by tag; a name that is both a player and a tag means the player.
 */
class PvpPointsCommand(
    private val plugin: KoTHPlugin,
    private val module: PvpPointsModule,
) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        val store = module.store
        val factions = plugin.factionsHook
        if (store == null || factions == null) {
            sender.sendMessage(message("disabled", "%reason%" to (module.disabledReason ?: "-")))
            return true
        }

        val sub = args.firstOrNull()?.lowercase()
        val permission =
            when (sub) {
                "reset" -> RESET_PERMISSION
                in ADMIN_SUBCOMMANDS -> ADMIN_PERMISSION
                else -> PLAYER_PERMISSION
            }
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(message("no-permission"))
            return true
        }

        when (sub) {
            null -> balanceSelf(sender, label, store, factions)
            "top" -> top(sender, store)
            "add", "give", "take", "set" -> change(sender, label, if (sub == "give") "add" else sub, args, store, factions)
            "history" -> history(sender, label, args, store, factions)
            "reset" -> reset(sender, label, args, store)
            else -> balanceOther(sender, args[0], store, factions)
        }
        return true
    }

    private fun balanceSelf(
        sender: CommandSender,
        label: String,
        store: PvpPointsStore,
        factions: FactionsHook,
    ) {
        if (sender !is Player) {
            sender.sendMessage(message("usage", "%label%" to label))
            return
        }
        val faction = factions.factionOf(sender) ?: return sender.sendMessage(message("no-faction"))
        sender.sendMessage(
            message(
                "balance-self",
                "%faction%" to faction.tag,
                "%points%" to store.points(faction.id).toString(),
                "%rank%" to rankText(store, faction.id),
            ),
        )
    }

    private fun balanceOther(
        sender: CommandSender,
        name: String,
        store: PvpPointsStore,
        factions: FactionsHook,
    ) {
        val target = resolve(name, store, factions, sender) ?: return
        sender.sendMessage(
            message(
                "balance-other",
                "%faction%" to target.faction.tag,
                "%points%" to store.points(target.faction.id).toString(),
                "%rank%" to rankText(store, target.faction.id),
            ),
        )
    }

    private fun top(
        sender: CommandSender,
        store: PvpPointsStore,
    ) {
        val size = plugin.configManager.config.getInt("${PvpPointsModule.ROOT}.top-size", 10).coerceIn(1, 100)
        val top = store.top(size)
        if (top.isEmpty()) {
            sender.sendMessage(message("top-empty"))
            return
        }
        sender.sendMessage(message("top-header", "%size%" to size.toString()))
        top.forEachIndexed { index, entry ->
            sender.sendMessage(
                plugin.configManager.messages.raw(
                    "pvp-points.top-line",
                    "%position%" to (index + 1).toString(),
                    "%faction%" to entry.tag,
                    "%points%" to entry.points.toString(),
                ),
            )
        }
    }

    private fun change(
        sender: CommandSender,
        label: String,
        action: String,
        args: Array<String>,
        store: PvpPointsStore,
        factions: FactionsHook,
    ) {
        if (args.size < 3) {
            sender.sendMessage(message("usage-$action", "%label%" to label))
            return
        }
        val minimum = if (action == "set") 0 else 1
        val amount = args[2].toIntOrNull()?.takeIf { it >= minimum }
        if (amount == null) {
            sender.sendMessage(message("invalid-number", "%value%" to args[2]))
            return
        }
        val target = resolve(args[1], store, factions, sender) ?: return
        val faction = target.faction

        val balance =
            when (action) {
                "add" -> store.add(faction, amount, PointsReason.ADD, target.player, sender.name)
                "set" -> store.set(faction, amount, PointsReason.SET, target.player, sender.name)
                else ->
                    store.take(faction, amount, target.player, sender.name) ?: run {
                        sender.sendMessage(
                            message(
                                "not-enough",
                                "%faction%" to faction.tag,
                                "%points%" to store.points(faction.id).toString(),
                                "%amount%" to amount.toString(),
                            ),
                        )
                        return
                    }
            }
        sender.sendMessage(
            message(
                "$action-success",
                "%faction%" to faction.tag,
                "%via%" to via(target.player),
                "%amount%" to amount.toString(),
                "%balance%" to balance.toString(),
            ),
        )
    }

    private fun history(
        sender: CommandSender,
        label: String,
        args: Array<String>,
        store: PvpPointsStore,
        factions: FactionsHook,
    ) {
        if (!store.keepsHistory) {
            sender.sendMessage(message("history-unavailable"))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(message("usage-history", "%label%" to label))
            return
        }
        val faction = (resolve(args[1], store, factions, sender) ?: return).faction
        val count = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, MAX_HISTORY) ?: DEFAULT_HISTORY
        val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(plugin.kothScheduler.zoneId)

        store.history(faction.id, count, Executor { plugin.server.scheduler.runTask(plugin, it) }) { lines ->
            when {
                lines == null -> sender.sendMessage(message("history-error"))
                lines.isEmpty() -> sender.sendMessage(message("history-empty", "%faction%" to faction.tag))
                else -> {
                    sender.sendMessage(message("history-header", "%faction%" to faction.tag, "%count%" to lines.size.toString()))
                    lines.forEach { sender.sendMessage(historyLine(it, timeFormat)) }
                }
            }
        }
    }

    private fun historyLine(
        line: HistoryEntry,
        timeFormat: DateTimeFormatter,
    ): String {
        // A reason without a label in messages.yml shows as its name (KILL, ADD, ...)
        val reason =
            plugin.configManager.messagesConfig.getString("pvp-points.reasons.${line.reason}")
                ?.let { ChatColor.translateAlternateColorCodes('&', it) } ?: line.reason
        return plugin.configManager.messages.raw(
            "pvp-points.history-line",
            "%time%" to timeFormat.format(Instant.ofEpochMilli(line.time)),
            "%delta%" to (if (line.delta > 0) "+${line.delta}" else line.delta.toString()),
            "%reason%" to reason,
            "%player%" to (line.player ?: "-"),
            "%detail%" to (line.detail ?: ""),
            "%balance%" to line.balance.toString(),
        )
    }

    /** /pvppoints reset shows what would be wiped; /pvppoints reset confirm does it. */
    private fun reset(
        sender: CommandSender,
        label: String,
        args: Array<String>,
        store: PvpPointsStore,
    ) {
        val withPoints = store.top(Int.MAX_VALUE)
        val total = withPoints.sumOf { it.points.toLong() }.toString()
        if (withPoints.isEmpty()) {
            sender.sendMessage(message("reset-nothing"))
            return
        }
        if (!args.getOrNull(1).equals("confirm", ignoreCase = true)) {
            sender.sendMessage(
                message("reset-confirm", "%factions%" to withPoints.size.toString(), "%points%" to total, "%label%" to label),
            )
            return
        }
        val reset = store.resetAll(sender.name)
        plugin.logger.info("[PvpPoints] ${sender.name} reset the points of $reset faction(s) ($total point(s) in total).")
        sender.sendMessage(message("reset-success", "%factions%" to reset.toString(), "%points%" to total))
    }

    private class Target(val faction: FactionRef, val player: String?)

    /**
     * A player (online, then Factions' data) means their faction; otherwise a faction tag, then a
     * tag stored with points (a faction Factions no longer has). Tells [sender] when nothing matches.
     */
    private fun resolve(
        name: String,
        store: PvpPointsStore,
        factions: FactionsHook,
        sender: CommandSender,
    ): Target? {
        val member = factions.findMember(name)
        if (member != null) {
            val faction = member.faction
            if (faction == null) {
                sender.sendMessage(message("player-no-faction", "%player%" to member.name))
                return null
            }
            return Target(faction, member.name)
        }
        factions.factionByTag(name)?.let { return Target(it, null) }
        store.findByTag(name)?.let { return Target(FactionRef(it.factionId, it.tag), null) }
        sender.sendMessage(message("not-found", "%name%" to name))
        return null
    }

    private fun via(player: String?): String =
        if (player == null) "" else plugin.configManager.messages.raw("pvp-points.via-player", "%player%" to player)

    private fun rankText(
        store: PvpPointsStore,
        factionId: String,
    ): String = store.rank(factionId)?.let { "#$it" } ?: "-"

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> {
        val isAdmin = sender.hasPermission(ADMIN_PERMISSION)
        val players = Bukkit.getOnlinePlayers().map { it.name }
        val tags = module.store?.top(TAB_FACTIONS)?.map { it.tag }.orEmpty()
        val sub = args.firstOrNull()?.lowercase()
        val options =
            when {
                args.size == 1 ->
                    buildList {
                        if (sender.hasPermission(PLAYER_PERMISSION)) {
                            add("top")
                            addAll(players)
                            addAll(tags)
                        }
                        if (isAdmin) addAll(ADMIN_SUBCOMMANDS.filter { it != "give" })
                        if (sender.hasPermission(RESET_PERMISSION)) add("reset")
                    }
                args.size == 2 && sub == "reset" && sender.hasPermission(RESET_PERMISSION) -> listOf("confirm")
                args.size == 2 && sub == "add" && isAdmin -> players
                args.size == 2 && sub in ADMIN_SUBCOMMANDS && isAdmin -> players + tags
                else -> emptyList()
            }
        val prefix = args.last()
        return options.distinct().filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    private fun message(
        key: String,
        vararg placeholders: Pair<String, String>,
    ): String = pvpMessage(plugin, key, *placeholders)

    companion object {
        const val ADMIN_PERMISSION = "pvppoints.admin"
        const val PLAYER_PERMISSION = "pvppoints.player"
        const val RESET_PERMISSION = "pvppoints.reset"

        // give: the 1.1.2 name of add, still accepted
        private val ADMIN_SUBCOMMANDS = listOf("add", "give", "take", "set", "history")
        private const val DEFAULT_HISTORY = 10
        private const val MAX_HISTORY = 50
        private const val TAB_FACTIONS = 50
    }
}
