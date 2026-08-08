package com.puk3p.koth.listeners

import com.puk3p.koth.KoTHPlugin
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

class PlayerListener(private val plugin: KoTHPlugin) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        val from = event.from

        if (to.blockX == from.blockX && to.blockY == from.blockY && to.blockZ == from.blockZ) return

        handleMovement(event.player, to, event)
    }

    // EXPLOIT FIX: If they enderpearl or /tpa into the zone!
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val to = event.to ?: return
        handleMovement(event.player, to, event)
    }

    // EXPLOIT FIX: If they log in directly inside the active KoTH zone!
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val activeArena = plugin.arenaManager.getActiveArena() ?: return

        if (activeArena.region.contains(player.location)) {
            val isTournament = plugin.configManager.config.getBoolean("types.${activeArena.type}.tournament-mode.enabled", false)
            if (isTournament && plugin.tournamentManager.isRegistrationClosed() && !plugin.tournamentManager.isParticipant(player.uniqueId)) {

                // VANISH FIX: Allow admins and vanished players to log in inside the zone
                if (player.hasPermission("koth.admin") || plugin.premiumVanishHook?.isVanished(player) == true) {
                    activeArena.playersInside.add(player.uniqueId)
                } else {
                    // Illegal entry: Teleport them to safety (server spawn)
                    player.teleport(player.world.spawnLocation)
                    player.sendMessage(plugin.configManager.messages.get("tournament.not-registered"))
                }
            } else {
                activeArena.playersInside.add(player.uniqueId)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        plugin.arenaManager.getActiveArena()?.playersInside?.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        plugin.arenaManager.getActiveArena()?.playersInside?.remove(player.uniqueId)

        val activeArena = plugin.arenaManager.getActiveArena() ?: return
        if (plugin.configManager.config.getBoolean("types.${activeArena.type}.tournament-mode.enabled", false)) {
            if (plugin.tournamentManager.isParticipant(player.uniqueId)) {
                plugin.tournamentManager.eliminatePlayer(player.uniqueId)
                player.sendMessage(plugin.configManager.messages.get("tournament.eliminated"))
            }
        }
    }

    /**
     * Unified logic for Move and Teleport events to ensure strict checking.
     */
    private fun handleMovement(player: org.bukkit.entity.Player, to: org.bukkit.Location, event: org.bukkit.event.player.PlayerMoveEvent) {
        val activeArena = plugin.arenaManager.getActiveArena() ?: return

        val isInside = activeArena.region.contains(to)
        val wasInside = activeArena.playersInside.contains(player.uniqueId)

        if (isInside && !wasInside) {
            val isTournament = plugin.configManager.config.getBoolean("types.${activeArena.type}.tournament-mode.enabled", false)
            if (isTournament && plugin.tournamentManager.isRegistrationClosed() && !plugin.tournamentManager.isParticipant(player.uniqueId)) {

                // VANISH FIX: Allow admins and vanished players to fly into the zone
                if (player.hasPermission("koth.admin") || plugin.premiumVanishHook?.isVanished(player) == true) {
                    // Let them pass silently
                } else {
                    player.sendMessage(plugin.configManager.messages.get("tournament.not-registered"))
                    event.isCancelled = true // Push them back
                    return
                }
            }
            activeArena.playersInside.add(player.uniqueId)
        } else if (!isInside && wasInside) {
            activeArena.playersInside.remove(player.uniqueId)
        }
    }
}