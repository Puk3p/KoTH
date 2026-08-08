package com.puk3p.koth.tasks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.ArenaState
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID

class CaptureTickTask(private val plugin: KoTHPlugin) : BukkitRunnable() {

    override fun run() {
        val activeArena = plugin.arenaManager.getActiveArena() ?: return

        if (activeArena.state == ArenaState.ENDED || activeArena.state == ArenaState.WAITING) return

        val playersInside = activeArena.playersInside

        if (playersInside.isEmpty()) {
            handleEmptyZone(activeArena)
            return
        }

        // Tournament mode elimination check (remove spectators if any snuck in)
        if (isTournamentMode(activeArena)) {
            playersInside.removeIf { plugin.tournamentManager.isEliminated(it) }
        }

        if (playersInside.isEmpty()) return

        if (playersInside.size == 1) {
            val playerUUID = playersInside.first()
            handleCapturing(activeArena, playerUUID)
        } else {
            handleContested(activeArena)
        }
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
            arena.resetCapture(defaultTime)
        } else {
            arena.state = ArenaState.RUNNING // Just paused
        }
    }

    private fun handleCapturing(arena: com.puk3p.koth.arena.Arena, playerUUID: UUID) {
        val player = Bukkit.getPlayer(playerUUID) ?: return

        // New capper stepped on
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

        // Decrement timer
        arena.remainingSeconds--

        // Check for win
        if (arena.remainingSeconds <= 0) {
            arena.state = ArenaState.ENDED

            // Call our custom event (will implement below)
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

                // Give Rewards
                plugin.rewardService.giveRewards(arena, player)

                // End event
                arena.isEnabled = false // or whatever reset logic you want
                arena.capturingPlayer = null
                arena.playersInside.clear()
            }
        } else if (arena.remainingSeconds % 60 == 0 || arena.remainingSeconds <= 10) {
            // Broadcast every minute or last 10 seconds
            // Optional: add action bar here
        }
    }

    private fun handleContested(arena: com.puk3p.koth.arena.Arena) {
        if (arena.state != ArenaState.CONTESTED) {
            arena.state = ArenaState.CONTESTED
            val msg = plugin.configManager.messages.get("events.contested", "%arena%" to arena.name)
            Bukkit.broadcastMessage(msg)
        }
        // In a contested state, timer does not decrement.
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