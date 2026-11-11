package com.puk3p.koth.listeners

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import java.util.UUID

/**
 * Keeps the active arena's `playersInside` in sync with where players actually are.
 */
class PlayerListener(private val plugin: KoTHPlugin) : Listener {

    /**
     * KOTH Arena: pushes back players who aren't in the event when they walk into the zone.
     * Runs at HIGH (not MONITOR) because it cancels the event.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerMoveCheck(event: PlayerMoveEvent) {
        val to = event.to ?: return
        if (!changedBlock(event.from, to)) return
        if (isEntryDenied(event.player, event.from, to)) {
            // Push them back
            event.isCancelled = true
        }
    }

    /** Same rule for teleports (/tpa to a participant, pearls, ...). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerTeleportCheck(event: PlayerTeleportEvent) {
        val to = event.to ?: return
        if (isEntryDenied(event.player, event.from, to)) {
            event.isCancelled = true
        }
    }

    /** True (and tells the player why) when [player] crosses into the active zone but may not count there. */
    private fun isEntryDenied(player: Player, from: Location, to: Location): Boolean {
        val arenas = plugin.arenaManager
        val arena = arenas.getActiveArena() ?: return false
        if (!arena.region.contains(to) || arena.region.contains(from)) return false
        if (arenas.canEnter(arena, player.uniqueId)) return false

        // Walking against the border retries several times a second; don't flood the chat
        val now = System.currentTimeMillis()
        val last = lastDeniedMessage[player.uniqueId]
        if (last == null || now - last >= DENIED_MESSAGE_COOLDOWN_MS) {
            lastDeniedMessage[player.uniqueId] = now
            player.sendMessage(plugin.kothArena.prefixed("zone-denied"))
        }
        return true
    }

    /** Tracking only; MONITOR sees the final, uncancelled movement. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val to = event.to ?: return
        // Only check if they moved a full block to save CPU
        if (!changedBlock(event.from, to)) return
        updateZone(event.player, to)
    }

    /** Covers pearls and other teleports both into and out of the zone. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        val to = event.to ?: return
        // Teleported away by something else: out of the KOTH Arena (checked first, so the zone update sees it)
        plugin.kothArena.onTeleport(event.player, event.from, to, event.cause)
        updateZone(event.player, to)
    }

    /**
     * Portals fire PlayerPortalEvent, which the teleport handlers above don't receive, so a player
     * stepping into a portal inside the zone would stay counted until they moved in the other world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        plugin.kothArena.onWorldChange(player)
        updateZone(player, player.location)
    }

    /** PlayerMoveEvent doesn't fire for riders, so track players on horses, minecarts and boats. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onVehicleMove(event: VehicleMoveEvent) {
        val rider = event.vehicle.passenger as? Player ?: return
        if (!changedBlock(event.from, event.to)) return
        updateZone(rider, event.to)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Logged out while in the KOTH Arena: they'd log back in inside it, so send them to the exit.
        // One tick later, because teleporting during the join event isn't reliable.
        plugin.kothArena.takeLoginLocation(player.uniqueId)?.let { exit ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) player.teleport(exit)
            })
        }
        updateZone(player, player.location)
    }

    /** KOTH Arena: players who died in the event respawn at the exit location. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerRespawnLocation(event: PlayerRespawnEvent) {
        plugin.kothArena.takeRespawnLocation(event.player.uniqueId)?.let { event.respawnLocation = it }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        updateZone(event.player, event.respawnLocation)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        plugin.arenaManager.getActiveArena()?.playersInside?.remove(uuid)
        // Logging out means leaving the KOTH Arena for good
        plugin.kothArena.onQuit(uuid)
        lastDeniedMessage.remove(uuid)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.entity
        plugin.arenaManager.getActiveArena()?.playersInside?.remove(player.uniqueId)
        // KOTH Arena: eliminated, respawns at the exit location
        plugin.kothArena.onDeath(player)
    }

    /** Adds or removes the player depending on whether [location] is inside the active zone. */
    private fun updateZone(player: Player, location: Location) {
        val arena = plugin.arenaManager.getActiveArena() ?: return
        val uuid = player.uniqueId
        if (arena.region.contains(location) && plugin.arenaManager.canEnter(arena, uuid)) {
            arena.playersInside.add(uuid)
        } else {
            arena.playersInside.remove(uuid)
        }
    }

    private fun changedBlock(from: Location, to: Location): Boolean {
        return from.blockX != to.blockX || from.blockY != to.blockY || from.blockZ != to.blockZ ||
            from.world != to.world
    }

    private val lastDeniedMessage = HashMap<UUID, Long>()

    companion object {
        private const val DENIED_MESSAGE_COOLDOWN_MS = 2000L
    }
}
