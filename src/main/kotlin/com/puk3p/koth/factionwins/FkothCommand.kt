package com.puk3p.koth.factionwins

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.logging.Level

/**
 * /fkoth (alias /fkw), carried over from the FKothWins addon:
 * add <player> <n>, remove <faction> <n>, set <faction> <n>, reload, debug, sethologram (fkoth.admin);
 * stats, top (fkoth.player).
 */
class FkothCommand(
    private val plugin: KoTHPlugin,
    private val module: FactionWinsModule,
) : CommandExecutor, TabCompleter {
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        val service = module.service
        if (service == null) {
            sender.sendMessage(message("disabled", "%reason%" to (module.disabledReason ?: "?")))
            return true
        }

        val sub = args.firstOrNull()?.lowercase()
        val permission =
            when (sub) {
                in ADMIN_SUBCOMMANDS -> ADMIN_PERMISSION
                in PLAYER_SUBCOMMANDS -> PLAYER_PERMISSION
                else -> {
                    sender.sendMessage(message("usage-main", "%label%" to label))
                    return true
                }
            }
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(message("no-permission"))
            return true
        }

        when (sub) {
            "add" -> add(sender, label, args, service)
            "remove" -> {
                // A typo must not look like a success (and used to create a 0-win entry in the top)
                if (args.size >= 2 && !service.isTracked(args[1])) {
                    sender.sendMessage(message("faction-not-found", "%faction%" to args[1]))
                    return true
                }
                changeFaction(sender, label, args, "remove") { faction, n -> service.removeWinsForFaction(faction, n) }
            }
            "set" -> changeFaction(sender, label, args, "set") { faction, n -> service.setWinsForFaction(faction, n) }
            "reload" -> reload(sender)
            "debug" -> debug(sender)
            "sethologram" -> setHologram(sender)
            "stats" -> stats(sender, service)
            "top" -> top(sender, service)
        }
        return true
    }

    private fun add(
        sender: CommandSender,
        label: String,
        args: Array<String>,
        service: FactionWinsService,
    ) {
        if (args.size < 3) {
            sender.sendMessage(message("usage-add", "%label%" to label))
            return
        }
        val amount = args[2].toIntOrNull()?.takeIf { it > 0 }
        if (amount == null) {
            sender.sendMessage(message("invalid-number"))
            return
        }

        val (status, faction) = service.addWinsForPlayer(args[1], amount)
        val reply =
            when (status) {
                AddByPlayerResult.SUCCESS -> message("add-success", "%amount%" to amount.toString(), "%faction%" to (faction ?: "-"))
                AddByPlayerResult.PLAYER_NOT_FOUND -> message("player-not-found")
                AddByPlayerResult.PLAYER_OFFLINE -> message("player-offline")
                AddByPlayerResult.NO_FACTION -> message("ignored-no-faction")
            }
        sender.sendMessage(reply)
    }

    /** remove / set <faction> <amount> */
    private fun changeFaction(
        sender: CommandSender,
        label: String,
        args: Array<String>,
        action: String,
        apply: (String, Int) -> Int,
    ) {
        if (args.size < 3) {
            sender.sendMessage(message("usage-$action", "%label%" to label))
            return
        }
        val minimum = if (action == "set") 0 else 1
        val amount = args[2].toIntOrNull()?.takeIf { it >= minimum }
        if (amount == null) {
            sender.sendMessage(message("invalid-number"))
            return
        }
        apply(args[1], amount)
        sender.sendMessage(message("$action-success", "%amount%" to amount.toString(), "%faction%" to args[1]))
    }

    private fun reload(sender: CommandSender) {
        plugin.configManager.reload()
        plugin.kothScheduler.reload() // config.yml is shared: keep the scheduler's timezone in step
        module.reload()
        sender.sendMessage(message("reload-success"))
    }

    private fun debug(sender: CommandSender) {
        sender.sendMessage(message("debug-header"))
        for ((name, value) in module.debugStatus()) {
            sender.sendMessage(message("debug-line", "%name%" to name, "%value%" to value))
        }
    }

    /** Writes faction-wins.hologram-location in config.yml (comments are kept) and restarts the hologram. */
    private fun setHologram(sender: CommandSender) {
        val player =
            sender as? Player ?: run {
                sender.sendMessage(message("stats-only-player"))
                return
            }
        try {
            plugin.configManager.saveLocation("faction-wins", "hologram-location", player.location)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not save faction-wins.hologram-location to config.yml", e)
            sender.sendMessage(plugin.configManager.messages.get("admin.location-save-failed"))
            return
        }
        module.reload()
        sender.sendMessage(message("hologram-set"))
    }

    private fun stats(
        sender: CommandSender,
        service: FactionWinsService,
    ) {
        val player =
            sender as? Player ?: run {
                sender.sendMessage(message("stats-only-player"))
                return
            }
        val faction = service.factionOf(player.uniqueId)
        if (faction == null) {
            sender.sendMessage(message("no-faction"))
            return
        }
        val wins = service.getWinsForFaction(faction)
        sender.sendMessage(message("stats-self", "%faction%" to faction, "%wins%" to wins.toString()))
    }

    private fun top(
        sender: CommandSender,
        service: FactionWinsService,
    ) {
        val size = plugin.configManager.config.getInt("faction-wins.top-size", 10).coerceIn(1, 100)
        sender.sendMessage(message("top-header", "%size%" to size.toString()))
        service.top(size).forEachIndexed { index, item ->
            sender.sendMessage(
                message(
                    "top-line",
                    "%position%" to (index + 1).toString(),
                    "%faction%" to item.faction,
                    "%wins%" to item.wins.toString(),
                ),
            )
        }
    }

    /** `fkoth.<key>` from messages.yml with the FKoth prefix. */
    private fun message(
        key: String,
        vararg placeholders: Pair<String, String>,
    ): String {
        val messages = plugin.configManager.messages
        return messages.raw("fkoth.prefix") + messages.raw("fkoth.$key", *placeholders)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> {
        val isAdmin = sender.hasPermission(ADMIN_PERMISSION)
        val options: Collection<String> =
            when {
                args.size == 1 ->
                    buildList {
                        if (sender.hasPermission(PLAYER_PERMISSION)) addAll(PLAYER_SUBCOMMANDS)
                        if (isAdmin) addAll(ADMIN_SUBCOMMANDS)
                    }
                !isAdmin || args.size != 2 -> emptyList()
                args[0].equals("add", ignoreCase = true) -> Bukkit.getOnlinePlayers().map { it.name }
                args[0].equals("remove", ignoreCase = true) || args[0].equals("set", ignoreCase = true) ->
                    module.service?.top(Int.MAX_VALUE)?.map { it.faction } ?: emptyList()
                else -> emptyList()
            }
        val prefix = args.last()
        return options.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    companion object {
        const val ADMIN_PERMISSION = "fkoth.admin"
        const val PLAYER_PERMISSION = "fkoth.player"

        private val ADMIN_SUBCOMMANDS = listOf("add", "remove", "set", "reload", "debug", "sethologram")
        private val PLAYER_SUBCOMMANDS = listOf("stats", "top")
    }
}
