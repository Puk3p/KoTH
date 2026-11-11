package com.puk3p.koth.commands

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.ArenaManager.StartResult
import com.puk3p.koth.arena.ArenaState
import com.puk3p.koth.arena.Region
import com.puk3p.koth.config.Messages
import com.puk3p.koth.kotharena.KothArenaManager
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Level

/**
 * /koth <subcommand>. join/leave need [JOIN_PERMISSION] (everyone by default),
 * everything else needs [ADMIN_PERMISSION] (or koth.start / koth.stop / koth.reload for just those).
 */
class KothCommand(private val plugin: KoTHPlugin) : CommandExecutor, TabCompleter {

    private class Selection {
        var pos1: Location? = null
        var pos2: Location? = null
    }

    private val selections = HashMap<UUID, Selection>()

    // Looked up on every use: /koth reload replaces the Messages instance.
    private val messages: Messages get() = plugin.configManager.messages
    private val arenas get() = plugin.arenaManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val sub = args.firstOrNull()?.lowercase() ?: "help"
        if (sub == "help") {
            showHelp(sender, label)
            return true
        }

        val allowed = when (sub) {
            in PLAYER_SUBCOMMANDS -> sender.hasPermission(JOIN_PERMISSION)
            in ADMIN_SUBCOMMANDS -> hasAdminAccess(sender, sub)
            else -> {
                sender.sendMessage(messages.get("general.unknown-subcommand", "%label%" to label))
                return true
            }
        }
        if (!allowed) {
            sender.sendMessage(messages.get("general.no-permission"))
            return true
        }

        when (sub) {
            "join" -> join(sender)
            "leave" -> leave(sender)
            "pos1" -> setPosition(sender, first = true)
            "pos2" -> setPosition(sender, first = false)
            "create" -> create(sender, label, args)
            "delete" -> delete(sender, label, args)
            "setspawn" -> setSpawn(sender, label, args)
            "setarena" -> setKothArenaLocation(sender, "arena-location", "admin.arena-location-set")
            "setexit" -> setKothArenaLocation(sender, "exit-location", "admin.exit-location-set")
            "list" -> list(sender)
            "start" -> start(sender, label, args)
            "stop" -> stop(sender, label, args)
            "reload" -> reload(sender)
        }
        return true
    }

    /** koth.admin allows every admin subcommand; koth.start / koth.stop / koth.reload allow just their own. */
    private fun hasAdminAccess(sender: CommandSender, sub: String): Boolean {
        if (sender.hasPermission(ADMIN_PERMISSION)) return true
        return SINGLE_PERMISSION_SUBCOMMANDS[sub]?.let { sender.hasPermission(it) } ?: false
    }

    private fun showHelp(sender: CommandSender, label: String) {
        val path = when {
            sender.hasPermission(ADMIN_PERMISSION) -> "help"
            sender.hasPermission(JOIN_PERMISSION) -> "help-player"
            else -> {
                sender.sendMessage(messages.get("general.no-permission"))
                return
            }
        }
        messages.getList(path, "%label%" to label).forEach { sender.sendMessage(it) }
    }

    // ------------------------------------------------------------------ //
    //  Player subcommands (KOTH Arena)
    // ------------------------------------------------------------------ //

    private fun join(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return
        val path = when (plugin.kothArena.join(player)) {
            KothArenaManager.JoinResult.JOINED -> "joined"
            KothArenaManager.JoinResult.ALREADY_JOINED -> "already-joined"
            KothArenaManager.JoinResult.NOT_OPEN -> "join-closed"
            KothArenaManager.JoinResult.CANNOT_REJOIN -> "cannot-rejoin"
            KothArenaManager.JoinResult.NO_LOCATION -> "no-location"
            KothArenaManager.JoinResult.TELEPORT_FAILED -> "teleport-failed"
        }
        sender.sendMessage(plugin.kothArena.prefixed(path))
    }

    private fun leave(sender: CommandSender) {
        val player = requirePlayer(sender) ?: return
        val path = if (plugin.kothArena.leave(player)) "left" else "not-joined"
        sender.sendMessage(plugin.kothArena.prefixed(path))
    }

    // ------------------------------------------------------------------ //
    //  Admin subcommands
    // ------------------------------------------------------------------ //

    private fun setPosition(sender: CommandSender, first: Boolean) {
        val player = requirePlayer(sender) ?: return
        val location = player.location
        val selection = selections.getOrPut(player.uniqueId) { Selection() }
        if (first) selection.pos1 = location else selection.pos2 = location

        sender.sendMessage(
            messages.get(
                if (first) "admin.pos1-set" else "admin.pos2-set",
                "%x%" to location.blockX.toString(),
                "%y%" to location.blockY.toString(),
                "%z%" to location.blockZ.toString()
            )
        )
    }

    private fun create(sender: CommandSender, label: String, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        if (args.size < 3) return usage(sender, "/$label create <name> <type>")

        val name = args[1]
        if (!NAME_PATTERN.matches(name)) {
            sender.sendMessage(messages.get("admin.invalid-name"))
            return
        }
        if (arenas.getArena(name) != null) {
            sender.sendMessage(messages.get("admin.arena-exists", "%arena%" to name))
            return
        }
        val type = arenas.findType(args[2])
        if (type == null) {
            sender.sendMessage(messages.get("admin.type-not-found", "%type%" to args[2]))
            return
        }

        val selection = selections[player.uniqueId]
        val pos1 = selection?.pos1
        val pos2 = selection?.pos2
        if (pos1 == null || pos2 == null) {
            sender.sendMessage(messages.get("admin.selection-incomplete"))
            return
        }
        if (pos1.world.uid != pos2.world.uid) {
            sender.sendMessage(messages.get("admin.selection-world-mismatch"))
            return
        }

        // Both corners at foot level give a zone one block high, and a player's feet leave it on every
        // jump: "lost control" and, with RESET, the timer starting over. Extend such zones upward.
        var region = Region.fromLocations(pos1, pos2)
        val selectedHeight = region.maxY - region.minY + 1
        if (selectedHeight < MIN_ZONE_HEIGHT) {
            region = region.copy(maxY = region.minY + MIN_ZONE_HEIGHT - 1)
        }

        val arena = Arena(name, type, region)
        arenas.addArena(arena)
        arenas.saveArena(arena)
        selections.remove(player.uniqueId)

        sender.sendMessage(messages.get("admin.arena-created", "%arena%" to arena.name, "%type%" to type))
        if (selectedHeight < MIN_ZONE_HEIGHT) {
            sender.sendMessage(
                messages.get(
                    "admin.region-expanded",
                    "%selected%" to selectedHeight.toString(),
                    "%height%" to MIN_ZONE_HEIGHT.toString()
                )
            )
        }
    }

    private fun delete(sender: CommandSender, label: String, args: Array<String>) {
        val arena = requireArena(sender, args, "/$label delete <name>") ?: return
        if (arena.state != ArenaState.WAITING || arena == arenas.busyArena()) {
            sender.sendMessage(messages.get("admin.arena-busy", "%arena%" to arena.name))
            return
        }
        arenas.deleteArena(arena)
        sender.sendMessage(messages.get("admin.arena-deleted", "%arena%" to arena.name))
    }

    private fun setSpawn(sender: CommandSender, label: String, args: Array<String>) {
        val player = requirePlayer(sender) ?: return
        val arena = requireArena(sender, args, "/$label setspawn <name>") ?: return
        arena.spawnPoint = player.location
        arenas.saveArena(arena)
        sender.sendMessage(messages.get("admin.spawn-set", "%arena%" to arena.name))
    }

    /** /koth setarena, /koth setexit: writes koth-arena.<key> in config.yml (comments are kept). */
    private fun setKothArenaLocation(sender: CommandSender, key: String, messagePath: String) {
        val player = requirePlayer(sender) ?: return
        val location = player.location
        try {
            plugin.configManager.saveLocation("koth-arena", key, location)
        } catch (e: Exception) {
            plugin.logger.log(Level.SEVERE, "Could not save koth-arena.$key to config.yml", e)
            sender.sendMessage(messages.get("admin.location-save-failed"))
            return
        }
        sender.sendMessage(
            messages.get(
                messagePath,
                "%world%" to location.world.name,
                "%x%" to location.blockX.toString(),
                "%y%" to location.blockY.toString(),
                "%z%" to location.blockZ.toString()
            )
        )
    }

    private fun list(sender: CommandSender) {
        val all = arenas.getArenas().sortedBy { it.name.lowercase() }
        if (all.isEmpty()) {
            sender.sendMessage(messages.get("admin.list-empty"))
            return
        }
        sender.sendMessage(messages.get("admin.list-header", "%count%" to all.size.toString()))
        for (arena in all) {
            sender.sendMessage(
                messages.raw(
                    "admin.list-entry",
                    "%arena%" to arena.name,
                    "%type%" to arena.type,
                    "%state%" to stateLabel(arena),
                    "%enabled%" to if (arena.isEnabled) "yes" else "no"
                )
            )
        }
    }

    private fun stateLabel(arena: Arena): String {
        val kothArena = plugin.kothArena
        if (arena != kothArena.arena) return arena.state.name
        return when (kothArena.phase) {
            KothArenaManager.Phase.JOIN -> "JOIN"
            KothArenaManager.Phase.POST_CAPTURE -> "POST_CAPTURE"
            else -> arena.state.name
        }
    }

    private fun start(sender: CommandSender, label: String, args: Array<String>) {
        val arena = requireArena(sender, args, "/$label start <name>") ?: return
        when (arenas.launch(arena)) {
            StartResult.STARTED, StartResult.JOIN_OPENED -> Unit // already broadcast to everyone
            StartResult.NO_ARENA_LOCATION -> sender.sendMessage(messages.get("admin.koth-arena-no-location"))
            StartResult.ALREADY_RUNNING ->
                sender.sendMessage(messages.get("admin.already-running", "%arena%" to arena.name))
            StartResult.ANOTHER_RUNNING ->
                sender.sendMessage(
                    messages.get("admin.another-running", "%arena%" to (arenas.busyArena()?.name ?: "?"))
                )
            StartResult.CANCELLED ->
                sender.sendMessage(messages.get("admin.start-cancelled", "%arena%" to arena.name))
        }
    }

    private fun stop(sender: CommandSender, label: String, args: Array<String>) {
        val arena = if (args.size >= 2) {
            requireArena(sender, args, "/$label stop [name]") ?: return
        } else {
            arenas.busyArena() ?: run {
                sender.sendMessage(messages.get("admin.not-running"))
                return
            }
        }
        if (arena == plugin.kothArena.arena) {
            plugin.kothArena.cancel() // any phase; teleports everyone out
            return
        }
        if (!arenas.stopArena(arena)) {
            sender.sendMessage(messages.get("admin.arena-not-running", "%arena%" to arena.name))
        }
    }

    private fun reload(sender: CommandSender) {
        // Reloading rebuilds every Arena object, which would drop a running KoTH's progress.
        val active = arenas.busyArena()
        if (active != null) {
            sender.sendMessage(messages.get("admin.reload-blocked", "%arena%" to active.name))
            return
        }
        plugin.configManager.reload()
        arenas.loadArenas()
        plugin.kothScheduler.reload()
        plugin.factionWins.reload()
        sender.sendMessage(messages.get("general.reloaded"))
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender !is Player) {
            sender.sendMessage(messages.get("general.player-only"))
            return null
        }
        return sender
    }

    /** Resolves args[1] to an arena, replying with usage / not-found on failure. */
    private fun requireArena(sender: CommandSender, args: Array<String>, usage: String): Arena? {
        if (args.size < 2) {
            usage(sender, usage)
            return null
        }
        val arena = arenas.getArena(args[1])
        if (arena == null) {
            sender.sendMessage(messages.get("admin.arena-not-found", "%arena%" to args[1]))
        }
        return arena
    }

    private fun usage(sender: CommandSender, usage: String) {
        sender.sendMessage(messages.get("general.invalid-args", "%usage%" to usage))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>
    ): List<String> {
        val options: Collection<String> = when {
            args.size == 1 -> buildList {
                add("help")
                if (sender.hasPermission(JOIN_PERMISSION)) addAll(PLAYER_SUBCOMMANDS)
                addAll(ADMIN_SUBCOMMANDS.filter { hasAdminAccess(sender, it) })
            }
            !hasAdminAccess(sender, args[0].lowercase()) -> emptyList()
            args.size == 2 -> when (args[0].lowercase()) {
                "delete", "setspawn", "start", "stop" -> arenas.getArenas().map { it.name }
                else -> emptyList()
            }
            args.size == 3 && args[0].equals("create", ignoreCase = true) -> arenas.getTypes()
            else -> emptyList()
        }
        val prefix = args.last()
        return options.filter { it.startsWith(prefix, ignoreCase = true) }.sorted()
    }

    companion object {
        const val ADMIN_PERMISSION = "koth.admin"
        const val JOIN_PERMISSION = "koth.join"

        private val PLAYER_SUBCOMMANDS = listOf("join", "leave")
        private val SINGLE_PERMISSION_SUBCOMMANDS = mapOf(
            "start" to "koth.start",
            "stop" to "koth.stop",
            "reload" to "koth.reload",
        )
        private val ADMIN_SUBCOMMANDS = listOf(
            "pos1", "pos2", "create", "delete", "setspawn", "setarena", "setexit", "list", "start", "stop", "reload"
        )

        // Arena names become YAML keys, so no dots or spaces.
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,32}$")

        // Feet level plus the two blocks a jump or a knock upward can reach
        private const val MIN_ZONE_HEIGHT = 3
    }
}
