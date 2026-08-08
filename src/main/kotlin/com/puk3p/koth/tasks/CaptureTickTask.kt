package com.puk3p.koth.tasks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.enums.ArenaState
import com.puk3p.koth.utils.ActionBarUtil
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.entity.Firework
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID

class CaptureTickTask(private val plugin: KoTHPlugin) : BukkitRunnable() {

    override fun run() {
        val activeArena = plugin.arenaManager.getActiveArena() ?: return

        if (activeArena.state == ArenaState.ENDED || activeArena.state == ArenaState.WAITING) return
        if (activeArena.isRegistrationOpen) return

        // NEW: Enforce Max Run Time (Time Limit)
        activeArena.totalActiveSeconds++
        val maxRunTime = plugin.configManager.config.getInt("types.${activeArena.type}.max-run-time", 0)

        if (maxRunTime > 0 && activeArena.totalActiveSeconds >= maxRunTime) {
            handleTimeLimitExceeded(activeArena)
            return
        }

        // VANISH FIX: Filter out offline, dead, and vanished staff members!
        val validPlayers = activeArena.playersInside.filter { uuid ->
            val p = Bukkit.getPlayer(uuid)
            p != null && !p.isDead && (plugin.premiumVanishHook?.isVanished(p) != true)
        }.toMutableSet()

        if (validPlayers.isEmpty()) {
            handleEmptyZone(activeArena)
            return
        }

        if (isTournamentMode(activeArena)) {
            validPlayers.removeIf { plugin.tournamentManager.isEliminated(it) }
        }

        if (validPlayers.isEmpty()) return

        if (validPlayers.size == 1) {
            val playerUUID = validPlayers.first()
            handleCapturing(activeArena, playerUUID)
        } else {
            handleContested(activeArena)
        }
    }

    private fun handleTimeLimitExceeded(arena: com.puk3p.koth.arena.Arena) {
        // End the arena in a draw
        arena.state = ArenaState.WAITING
        arena.capturingPlayer = null
        arena.playersInside.clear()
        plugin.tournamentManager.openRegistration()

        Bukkit.broadcastMessage("§c§lKoTH DRAW! §7The §c${arena.name} §7KoTH has exceeded the time limit without a winner.")
    }

    private fun handleEmptyZone(arena: com.puk3p.koth.arena.Arena) {
        val behavior = plugin.configManager.config.getString("types.${arena.type}.knock-behavior", "RESET")
        val defaultTime = plugin.configManager.config.getInt("types.${arena.type}.capture-time", 300)

        if (arena.capturingPlayer != null) {
            val player = Bukkit.getPlayer(arena.capturingPlayer)
            val playerName = player?.name ?: "Someone"

            val msg = plugin.configManager.messages.get("events.lost-control",
                "%player%" to playerName,
                "%arena%" to arena.name
            )
            Bukkit.broadcastMessage(msg)
            arena.capturingPlayer = null
        }

        if (behavior.equals("RESET", ignoreCase = true)) {
            arena.remainingSeconds = defaultTime
        }
        arena.state = ArenaState.RUNNING
    }

    private fun handleCapturing(arena: com.puk3p.koth.arena.Arena, playerUUID: UUID) {
        val player = Bukkit.getPlayer(playerUUID) ?: return
        val typePath = "types.${arena.type}"

        if (arena.capturingPlayer != playerUUID) {
            arena.capturingPlayer = playerUUID
            arena.state = ArenaState.RUNNING

            val factionTag = plugin.factionsHook?.getFaction(player) ?: "No Faction"
            val msg = plugin.configManager.messages.get("events.capturing",
                "%player%" to player.name,
                "%faction%" to factionTag,
                "%arena%" to arena.name,
                "%time%" to formatTime(arena.remainingSeconds)
            )
            Bukkit.broadcastMessage(msg)
        }

        arena.remainingSeconds--

        // NEW: Action Bar Timer
        if (plugin.configManager.config.getBoolean("$typePath.visuals.action-bar-timer", true)) {
            val timeFormatted = formatTime(arena.remainingSeconds)
            ActionBarUtil.sendActionBar(player, "§b§lCapturing ${arena.name} §8» §f$timeFormatted")
        }

        if (arena.remainingSeconds <= 0) {
            arena.state = ArenaState.ENDED

            val event = com.puk3p.koth.event.KothCaptureEvent(arena, player)
            Bukkit.getPluginManager().callEvent(event)

            if (!event.isCancelled) {
                val factionTag = plugin.factionsHook?.getFaction(player) ?: "No Faction"
                val msg = plugin.configManager.messages.get("events.captured",
                    "%player%" to player.name,
                    "%faction%" to factionTag,
                    "%arena%" to arena.name
                )
                Bukkit.broadcastMessage(msg)

                // NEW: Victory Fireworks
                if (plugin.configManager.config.getBoolean("$typePath.visuals.firework-on-capture", true)) {
                    spawnVictoryFirework(player)
                }

                val totalCaptureTime = plugin.configManager.config.getInt("$typePath.capture-time", 300)
                plugin.discordService.sendKothCaptured(
                    arena = arena,
                    playerName = player.name,
                    factionName = factionTag,
                    durationStr = formatTime(totalCaptureTime)
                )

                plugin.rewardService.giveRewards(arena, player)

                arena.state = ArenaState.WAITING
                arena.capturingPlayer = null
                arena.playersInside.clear()
                plugin.tournamentManager.openRegistration()
            }
        }
    }

    private fun handleContested(arena: com.puk3p.koth.arena.Arena) {
        if (arena.state != ArenaState.CONTESTED) {
            arena.state = ArenaState.CONTESTED
            val msg = plugin.configManager.messages.get("events.contested", "%arena%" to arena.name)
            Bukkit.broadcastMessage(msg)
        }

        // Let players know it is paused
        arena.playersInside.mapNotNull { Bukkit.getPlayer(it) }.forEach { p ->
            ActionBarUtil.sendActionBar(p, "§c§lCONTESTED! §7Clear the zone!")
        }
    }

    private fun spawnVictoryFirework(player: org.bukkit.entity.Player) {
        val fw = player.world.spawn(player.location, Firework::class.java)
        val meta = fw.fireworkMeta
        meta.addEffect(FireworkEffect.builder()
            .withColor(Color.ORANGE, Color.YELLOW)
            .withFade(Color.RED)
            .with(FireworkEffect.Type.BALL_LARGE)
            .trail(true)
            .flicker(true)
            .build())
        meta.power = 1
        fw.fireworkMeta = meta
    }

    private fun isTournamentMode(arena: com.puk3p.koth.arena.Arena): Boolean {
        return plugin.configManager.config.getBoolean("types.${arena.type}.tournament-mode.enabled", false)
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }
}