package com.puk3p.koth.listeners

import com.puk3p.koth.KoTHPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

class PlayerListener(private val plugin: KoTHPlugin) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from

        // Only check if they moved a full block to save CPU
        if (to.blockX == from.blockX && to.blockY == from.blockY && to.blockZ == from.blockZ) return

        val player = event.player
        val activeArena = plugin.arenaManager.getActiveArena() ?: return

        val isInside = activeArena.region.contains(to)
        val wasInside = activeArena.playersInside.contains(player.uniqueId)

        if (isInside && !wasInside) {
            // Tournament mode check - block entry if registration closed & not registered
            if (plugin.configManager.config.getBoolean("types.${activeArena.type}.tournament-mode.enabled", false)) {
                if (plugin.tournamentManager.isRegistrationClosed() && !plugin.tournamentManager.isParticipant(player.uniqueId)) {
                    val msg = plugin.configManager.messages.get("tournament.not-registered")
                    player.sendMessage(msg)
                    // Push them back
                    event.isCancelled = true
                    return
                }
            }
            activeArena.playersInside.add(player.uniqueId)
        } else if (!isInside && wasInside) {
            activeArena.playersInside.remove(player.uniqueId)
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        handlePlayerLeave(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        handlePlayerLeave(player.uniqueId)

        // Tournament elimination logic
        val activeArena = plugin.arenaManager.getActiveArena() ?: return
        if (plugin.configManager.config.getBoolean("types.${activeArena.type}.tournament-mode.enabled", false)) {
            if (plugin.tournamentManager.isParticipant(player.uniqueId)) {
                plugin.tournamentManager.eliminatePlayer(player.uniqueId)
                val msg = plugin.configManager.messages.get("tournament.eliminated")
                player.sendMessage(msg)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        handlePlayerLeave(event.player.uniqueId)
    }

    private fun handlePlayerLeave(uuid: java.util.UUID) {
        plugin.arenaManager.getActiveArena()?.playersInside?.remove(uuid)
    }
}