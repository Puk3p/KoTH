package com.puk3p.koth.commands

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.Region
import com.puk3p.koth.enums.ArenaState
import com.puk3p.koth.event.KothStartEvent
import com.puk3p.koth.tasks.TournamentTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class KothCommand(private val plugin: KoTHPlugin) : CommandExecutor, TabCompleter {

    private val pos1Map = HashMap<UUID, Location>()
    private val pos2Map = HashMap<UUID, Location>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "join" -> handleJoin(sender)
            "pos1" -> handlePos(sender, 1)
            "pos2" -> handlePos(sender, 2)
            "create" -> handleCreate(sender, args)
            "start" -> handleStart(sender, args)
            "stop" -> handleStop(sender)
            "reload" -> handleReload(sender)
            else -> sendHelp(sender)
        }

        return true
    }

    private fun handleJoin(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(plugin.configManager.messages.get("general.player-only"))
            return
        }

        val activeArena = plugin.arenaManager.getActiveArena()
        if (activeArena == null) {
            sender.sendMessage("§cThere is no active KoTH event right now.")
            return
        }

        val isTournament = plugin.configManager.config.getBoolean("types.${activeArena.type}.tournament-mode.enabled", false)
        if (!isTournament) {
            sender.sendMessage("§cThis KoTH event does not require registration.")
            return
        }

        if (plugin.tournamentManager.isRegistrationClosed()) {
            sender.sendMessage(plugin.configManager.messages.get("tournament.registration-closed", "%arena%" to activeArena.name))
            return
        }

        if (plugin.tournamentManager.addParticipant(sender.uniqueId)) {
            sender.sendMessage(plugin.configManager.messages.get("tournament.joined"))
        } else {
            sender.sendMessage(plugin.configManager.messages.get("tournament.already-joined"))
        }
    }

    private fun handlePos(sender: CommandSender, pos: Int) {
        if (sender !is Player) {
            sender.sendMessage(plugin.configManager.messages.get("general.player-only"))
            return
        }
        if (!sender.hasPermission("koth.admin")) {
            sender.sendMessage(plugin.configManager.messages.get("general.no-permission"))
            return
        }

        val loc = sender.location
        if (pos == 1) {
            pos1Map[sender.uniqueId] = loc
            sender.sendMessage(plugin.configManager.messages.get("admin.pos1-set"))
        } else {
            pos2Map[sender.uniqueId] = loc
            sender.sendMessage(plugin.configManager.messages.get("admin.pos2-set"))
        }
    }

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("koth.admin")) {
            sender.sendMessage(plugin.configManager.messages.get("general.no-permission"))
            return
        }
        if (sender !is Player) {
            sender.sendMessage(plugin.configManager.messages.get("general.player-only"))
            return
        }
        if (args.size < 3) {
            sender.sendMessage("§cUsage: /koth create <name> <type>")
            return
        }

        val name = args[1].lowercase()
        val type = args[2].lowercase()

        if (!plugin.configManager.config.contains("types.$type")) {
            sender.sendMessage(plugin.configManager.messages.get("admin.type-not-found", "%type%" to type))
            return
        }

        val loc1 = pos1Map[sender.uniqueId]
        val loc2 = pos2Map[sender.uniqueId]

        if (loc1 == null || loc2 == null) {
            sender.sendMessage("§cYou must set pos1 and pos2 first!")
            return
        }
        if (loc1.world != loc2.world) {
            sender.sendMessage("§cPositions must be in the same world!")
            return
        }

        // Create memory object
        val region = Region.fromLocations(loc1, loc2)
        val arena = Arena(name, type, region)
        plugin.arenaManager.addArena(arena)

        // Save to arenas.yml
        val config = plugin.configManager.arenasConfig
        val path = "arenas.$name"
        config.set("$path.type", type)
        config.set("$path.enabled", true)
        config.set("$path.world", region.worldName)
        config.set("$path.region.min-x", region.minX)
        config.set("$path.region.min-y", region.minY)
        config.set("$path.region.min-z", region.minZ)
        config.set("$path.region.max-x", region.maxX)
        config.set("$path.region.max-y", region.maxY)
        config.set("$path.region.max-z", region.maxZ)
        plugin.configManager.saveArenas()

        sender.sendMessage(plugin.configManager.messages.get("admin.arena-created", "%arena%" to name, "%type%" to type))
    }

    private fun handleStart(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("koth.start")) {
            sender.sendMessage(plugin.configManager.messages.get("general.no-permission"))
            return
        }
        if (args.size < 2) {
            sender.sendMessage("§cUsage: /koth start <arena>")
            return
        }

        if (plugin.arenaManager.getActiveArena() != null) {
            sender.sendMessage("§cA KoTH event is already running!")
            return
        }

        val name = args[1]
        val arena = plugin.arenaManager.getArena(name)

        if (arena == null) {
            sender.sendMessage(plugin.configManager.messages.get("admin.arena-not-found", "%arena%" to name))
            return
        }

        val defaultTime = plugin.configManager.config.getInt("types.${arena.type}.capture-time", 300)
        arena.resetCapture(defaultTime)

        // Trigger Event
        Bukkit.getPluginManager().callEvent(KothStartEvent(arena))

        val isTournament = plugin.configManager.config.getBoolean("types.${arena.type}.tournament-mode.enabled", false)
        if (isTournament) {
            TournamentTask(plugin, arena).runTaskTimer(plugin, 0L, 20L)
        } else {
            val msg = plugin.configManager.messages.get("events.started", "%arena%" to arena.name, "%type%" to arena.type)
            Bukkit.broadcastMessage(msg)
        }

        plugin.discordService.sendKothStarted(arena)
    }

    private fun handleStop(sender: CommandSender) {
        if (!sender.hasPermission("koth.stop")) {
            sender.sendMessage(plugin.configManager.messages.get("general.no-permission"))
            return
        }

        val activeArena = plugin.arenaManager.getActiveArena()
        if (activeArena == null) {
            sender.sendMessage("§cThere is no active KoTH event to stop.")
            return
        }

        activeArena.state = ArenaState.WAITING
        activeArena.isRegistrationOpen = false
        activeArena.capturingPlayer = null
        activeArena.playersInside.clear()

        plugin.tournamentManager.openRegistration() // Clears memory

        val msg = plugin.configManager.messages.get("events.stopped", "%arena%" to activeArena.name)
        Bukkit.broadcastMessage(msg)
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("koth.reload")) {
            sender.sendMessage(plugin.configManager.messages.get("general.no-permission"))
            return
        }

        plugin.configManager.reload()
        sender.sendMessage(plugin.configManager.messages.get("general.reloaded"))
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("§8=== §bKoTH Commands §8===")
        sender.sendMessage("§e/koth join §7- Join an active tournament")
        if (sender.hasPermission("koth.admin")) {
            sender.sendMessage("§e/koth pos1/pos2 §7- Set capture region")
            sender.sendMessage("§e/koth create <name> <type> §7- Create new arena")
            sender.sendMessage("§e/koth start <arena> §7- Force start an event")
            sender.sendMessage("§e/koth stop §7- Force stop active event")
            sender.sendMessage("§e/koth reload §7- Reload configs")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val completions = mutableListOf<String>()
        if (args.size == 1) {
            val subs = mutableListOf("join")
            if (sender.hasPermission("koth.admin")) subs.addAll(listOf("pos1", "pos2", "create", "start", "stop", "reload"))
            completions.addAll(subs.filter { it.startsWith(args[0].lowercase()) })
        } else if (args.size == 2 && args[0].equals("start", ignoreCase = true)) {
            val arenas = plugin.arenaManager.getArenas().map { it.name }
            completions.addAll(arenas.filter { it.startsWith(args[1].lowercase()) })
        } else if (args.size == 3 && args[0].equals("create", ignoreCase = true)) {
            val types = plugin.configManager.config.getConfigurationSection("types")?.getKeys(false) ?: emptySet()
            completions.addAll(types.filter { it.startsWith(args[2].lowercase()) })
        }
        return completions
    }
}