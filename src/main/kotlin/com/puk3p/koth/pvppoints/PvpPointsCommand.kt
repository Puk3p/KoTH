package com.puk3p.koth.pvppoints

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executor

/**
 * /pvppoints                         your points and rank
 * /pvppoints <player>                someone else's points
 * /pvppoints top                     the players with the most points
 * /pvppoints give|take|set <player> <amount>   admins and the console (shops use take)
 * /pvppoints history <player> [count]          latest changes and why (MySQL storage only)
 *
 * take fails without changing anything when the player has fewer points than asked, so a shop
 * that runs it can rely on the result. Players are found online, or among those who joined
 * since pvp-points was enabled (no Mojang lookups).
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
        if (store == null) {
            sender.sendMessage(message("disabled", "%reason%" to (module.disabledReason ?: "-")))
            return true
        }

        val sub = args.firstOrNull()?.lowercase()
        val permission = if (sub in ADMIN_SUBCOMMANDS) ADMIN_PERMISSION else PLAYER_PERMISSION
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(message("no-permission"))
            return true
        }

        when (sub) {
            null -> balanceSelf(sender, label, store)
            "top" -> top(sender, store)
            "give", "take", "set" -> change(sender, label, sub, args, store)
            "history" -> history(sender, label, args, store)
            else -> balanceOther(sender, args[0], store)
        }
        return true
    }

    private fun balanceSelf(
        sender: CommandSender,
        label: String,
        store: PvpPointsStore,
    ) {
        if (sender !is Player) {
            sender.sendMessage(message("usage", "%label%" to label))
            return
        }
        sender.sendMessage(
            message(
                "balance-self",
                "%points%" to store.points(sender.uniqueId).toString(),
                "%rank%" to (store.rank(sender.uniqueId)?.toString() ?: "-"),
            ),
        )
    }

    private fun balanceOther(
        sender: CommandSender,
        name: String,
        store: PvpPointsStore,
    ) {
        val (uuid, playerName) = findPlayer(name, store) ?: return sender.sendMessage(message("player-not-found", "%player%" to name))
        sender.sendMessage(
            message(
                "balance-other",
                "%player%" to playerName,
                "%points%" to store.points(uuid).toString(),
                "%rank%" to (store.rank(uuid)?.toString() ?: "-"),
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
                    "%player%" to entry.name,
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
        val (uuid, name) = findPlayer(args[1], store) ?: return sender.sendMessage(message("player-not-found", "%player%" to args[1]))

        val balance =
            when (action) {
                "give" -> store.add(uuid, name, amount, PointsReason.GIVE, sender.name)
                "set" -> store.set(uuid, name, amount, sender.name)
                else ->
                    store.take(uuid, name, amount, sender.name) ?: run {
                        sender.sendMessage(
                            message(
                                "not-enough",
                                "%player%" to name,
                                "%points%" to store.points(uuid).toString(),
                                "%amount%" to amount.toString(),
                            ),
                        )
                        return
                    }
            }
        sender.sendMessage(
            message(
                "$action-success",
                "%player%" to name,
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
    ) {
        if (!store.keepsHistory) {
            sender.sendMessage(message("history-unavailable"))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(message("usage-history", "%label%" to label))
            return
        }
        val (uuid, name) = findPlayer(args[1], store) ?: return sender.sendMessage(message("player-not-found", "%player%" to args[1]))
        val count = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, MAX_HISTORY) ?: DEFAULT_HISTORY
        val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(plugin.kothScheduler.zoneId)

        store.history(uuid, count, Executor { plugin.server.scheduler.runTask(plugin, it) }) { lines ->
            when {
                lines == null -> sender.sendMessage(message("history-error"))
                lines.isEmpty() -> sender.sendMessage(message("history-empty", "%player%" to name))
                else -> {
                    sender.sendMessage(message("history-header", "%player%" to name, "%count%" to lines.size.toString()))
                    lines.forEach { sender.sendMessage(historyLine(it, timeFormat)) }
                }
            }
        }
    }

    private fun historyLine(
        line: HistoryEntry,
        timeFormat: DateTimeFormatter,
    ): String {
        // A reason without a label in messages.yml shows as its name (KILL, GIVE, ...)
        val reason =
            plugin.configManager.messagesConfig.getString("pvp-points.reasons.${line.reason}")
                ?.let { ChatColor.translateAlternateColorCodes('&', it) } ?: line.reason
        return plugin.configManager.messages.raw(
            "pvp-points.history-line",
            "%time%" to timeFormat.format(Instant.ofEpochMilli(line.time)),
            "%delta%" to (if (line.delta > 0) "+${line.delta}" else line.delta.toString()),
            "%reason%" to reason,
            "%detail%" to (line.detail ?: ""),
            "%balance%" to line.balance.toString(),
        )
    }

    /** Online players first, then players who joined since pvp-points was enabled. */
    private fun findPlayer(
        name: String,
        store: PvpPointsStore,
    ): Pair<UUID, String>? {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId to it.name }
        return store.findByName(name)?.let { it.uuid to it.name }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> {
        val isAdmin = sender.hasPermission(ADMIN_PERMISSION)
        val players = Bukkit.getOnlinePlayers().map { it.name }
        val options =
            when {
                args.size == 1 ->
                    buildList {
                        if (sender.hasPermission(PLAYER_PERMISSION)) {
                            add("top")
                            addAll(players)
                        }
                        if (isAdmin) addAll(ADMIN_SUBCOMMANDS)
                    }
                args.size == 2 && isAdmin && args[0].lowercase() in ADMIN_SUBCOMMANDS -> players
                else -> emptyList()
            }
        val prefix = args.last()
        return options.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    private fun message(
        key: String,
        vararg placeholders: Pair<String, String>,
    ): String = pvpMessage(plugin, key, *placeholders)

    companion object {
        const val ADMIN_PERMISSION = "pvppoints.admin"
        const val PLAYER_PERMISSION = "pvppoints.player"

        private val ADMIN_SUBCOMMANDS = listOf("give", "take", "set", "history")
        private const val DEFAULT_HISTORY = 10
        private const val MAX_HISTORY = 50
    }
}
