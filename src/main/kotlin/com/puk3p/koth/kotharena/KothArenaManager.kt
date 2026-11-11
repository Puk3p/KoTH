package com.puk3p.koth.kotharena

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.ArenaManager.StartResult
import com.puk3p.koth.util.ConfigLocations
import com.puk3p.koth.util.formatSpoken
import com.puk3p.koth.util.shouldAnnounce
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * KOTH Arena: an LMS-style KoTH event (config.yml -> koth-arena), for types with `koth-arena: true`.
 *
 * - JOIN: `/koth join` teleports the player to the arena location. Joined players are invincible and
 *   command-locked while `join-time` counts down.
 * - FIGHT: the KoTH starts and only joined players count in the zone. Dying, logging out, `/koth leave`
 *   or being teleported away removes a player for good.
 * - POST_CAPTURE: after the capture, players stay `post-capture-time` seconds, then everyone still
 *   in the arena is teleported to the exit location.
 *
 * Main thread only, except the read-only state used by PlaceholderAPI.
 */
class KothArenaManager(private val plugin: KoTHPlugin) {

    enum class Phase { IDLE, JOIN, FIGHT, POST_CAPTURE }

    enum class JoinResult { JOINED, ALREADY_JOINED, NOT_OPEN, CANNOT_REJOIN, NO_LOCATION, TELEPORT_FAILED }

    @Volatile
    var phase = Phase.IDLE
        private set

    /** The event's KoTH arena (capture zone), from the join phase until the end. */
    @Volatile
    var arena: Arena? = null
        private set

    /** JOIN: seconds until the KoTH starts. POST_CAPTURE: seconds until players are teleported out. */
    @Volatile
    var secondsLeft = 0
        private set

    // Players in the arena right now. Concurrent because PlaceholderAPI may read it off the main thread.
    private val participants: MutableSet<UUID> = Collections.newSetFromMap(ConcurrentHashMap())

    // Died or left during this event: can't join again
    private val removed = mutableSetOf<UUID>()

    // Outlive the event: a death or logout near the end is handled after it's over
    private val respawnAtExit = mutableSetOf<UUID>()
    private val exitOnLogin = mutableSetOf<UUID>()

    // exitOnLogin plus everyone currently in the arena, kept on disk so that players who logged out
    // in the arena (or were in it when the server crashed) are still sent out after a restart.
    private val pendingExitFile = File(plugin.dataFolder, "koth-arena-players.yml")
    private var savedPendingExits: Set<String> = emptySet()

    private var task: BukkitTask? = null

    // True while this plugin teleports a player, so it isn't mistaken for leaving the arena
    private var teleporting = false

    private val config get() = plugin.configManager.config

    init {
        loadPendingExits()
    }

    // ------------------------------------------------------------------ //
    //  Settings
    // ------------------------------------------------------------------ //

    fun isEventArena(arena: Arena): Boolean {
        return config.getBoolean("koth-arena.enabled", true) && config.getBoolean("types.${arena.type}.koth-arena", false)
    }

    val joinTime: Int
        get() = config.getInt("koth-arena.join-time", 300).coerceAtLeast(1)

    private val postCaptureTime: Int
        get() = config.getInt("koth-arena.post-capture-time", 60).coerceAtLeast(0)

    private val minPlayers: Int
        get() = config.getInt("koth-arena.min-players", 2).coerceAtLeast(1)

    private val invincibleAfterCapture: Boolean
        get() = config.getBoolean("koth-arena.invincible-after-capture", true)

    /** Players outside the event and players in it can't damage each other while it runs. */
    val blockOutsiderPvp: Boolean
        get() = config.getBoolean("koth-arena.block-outsider-pvp", true)

    /** Blocks from the arena location beyond which a player counts as having left; 0 = no limit. */
    private val arenaRadius: Double
        get() = config.getDouble("koth-arena.arena-radius", 0.0)

    private fun announcements(): Set<Int> {
        val configured = config.getIntegerList("koth-arena.countdown-announcements")
        return if (configured.isEmpty()) DEFAULT_ANNOUNCEMENTS else configured.toSet()
    }

    /** Null if not set or its world isn't loaded. */
    fun arenaLocation(): Location? = ConfigLocations.read(config.getConfigurationSection("koth-arena.arena-location"))

    /** Falls back to the main world's spawn. */
    fun exitLocation(): Location {
        return ConfigLocations.read(config.getConfigurationSection("koth-arena.exit-location"))
            ?: Bukkit.getWorlds()[0].spawnLocation
    }

    // ------------------------------------------------------------------ //
    //  State
    // ------------------------------------------------------------------ //

    val isRunning: Boolean get() = phase != Phase.IDLE
    val isJoinOpen: Boolean get() = phase == Phase.JOIN
    val isFighting: Boolean get() = phase == Phase.FIGHT

    fun isParticipant(uuid: UUID): Boolean = uuid in participants

    fun participantCount(): Int = participants.size

    /** No damage taken or dealt: from joining until the start, and after the capture if configured. */
    fun isInvincible(uuid: UUID): Boolean {
        if (uuid !in participants) return false
        return phase == Phase.JOIN || (phase == Phase.POST_CAPTURE && invincibleAfterCapture)
    }

    // ------------------------------------------------------------------ //
    //  Phases
    // ------------------------------------------------------------------ //

    /**
     * Opens /koth join for [arena]. Callers check that nothing else is running.
     * @return false if the arena location isn't usable (nothing is started then)
     */
    fun openJoin(arena: Arena): Boolean {
        if (arenaLocation() == null) return false
        reset()
        this.arena = arena
        phase = Phase.JOIN
        secondsLeft = joinTime
        broadcast("join-opened", "%time%" to spoken(secondsLeft))
        task = KothArenaTask(this).runTaskTimer(plugin, 20L, 20L)
        return true
    }

    /** Called once per second by [KothArenaTask]. */
    fun tick() {
        val arena = arena
        if (arena == null) {
            reset()
            return
        }
        removeWanderers()
        when (phase) {
            Phase.IDLE -> reset()
            Phase.JOIN -> {
                secondsLeft--
                if (secondsLeft <= 0) {
                    startFight(arena)
                } else if (secondsLeft in announcements()) {
                    broadcast(
                        "join-countdown",
                        "%time%" to spoken(secondsLeft),
                        "%count%" to participants.size.toString()
                    )
                }
            }
            Phase.FIGHT -> if (participants.isEmpty()) {
                broadcast("no-players-left")
                plugin.arenaManager.stopArena(arena, broadcast = false) // -> onArenaEnded -> finish
            }
            Phase.POST_CAPTURE -> {
                secondsLeft--
                if (secondsLeft <= 0) {
                    finish()
                } else if (shouldAnnounce(secondsLeft)) {
                    tellParticipants("post-capture-countdown", "%time%" to spoken(secondsLeft))
                }
            }
        }
    }

    private fun startFight(arena: Arena) {
        participants.removeIf { Bukkit.getPlayer(it) == null }
        if (participants.size < minPlayers) {
            broadcast("not-enough-players", "%count%" to participants.size.toString(), "%min%" to minPlayers.toString())
            finish()
            return
        }

        // Set before starting so ArenaManager.canEnter lets the participants count in the zone
        phase = Phase.FIGHT
        if (plugin.arenaManager.startArena(arena) != StartResult.STARTED) {
            broadcast("cancelled") // e.g. a KothStartEvent listener cancelled it
            finish()
            return
        }
        tellParticipants("fight-started")
    }

    /** Called by the capture loop after a capture, before the arena goes idle. */
    fun onCaptured(arena: Arena, winnerName: String) {
        if (arena != this.arena || phase != Phase.FIGHT) return
        phase = Phase.POST_CAPTURE
        secondsLeft = postCaptureTime
        if (secondsLeft <= 0) {
            finish()
            return
        }
        tellParticipants("post-capture", "%player%" to winnerName, "%time%" to spoken(secondsLeft))
    }

    /** ArenaManager calls this whenever an arena goes idle. Stopped mid-fight (no capture) ends the event. */
    fun onArenaEnded(ended: Arena) {
        if (ended == arena && phase == Phase.FIGHT) {
            finish()
        }
    }

    /** Cancels the event in any phase (/koth stop, shutdown) and teleports everyone out. */
    fun cancel() {
        val arena = arena ?: return
        broadcast("cancelled")
        if (phase == Phase.FIGHT) {
            plugin.arenaManager.stopArena(arena, broadcast = false) // -> onArenaEnded -> finish
        }
        finish()
    }

    /** Teleports everyone still in the arena to the exit and ends the event. */
    private fun finish() {
        if (participants.isNotEmpty()) {
            val exit = exitLocation()
            val message = prefixed("teleported-out")
            for (uuid in participants.toList()) {
                val player = Bukkit.getPlayer(uuid)
                if (player == null) {
                    exitOnLogin.add(uuid) // not expected (quitting removes them), but never strand anyone
                    continue
                }
                teleport(player, exit)
                player.sendMessage(message)
            }
        }
        reset()
        savePendingExits()
    }

    /**
     * `arena-radius`: a participant who walked out of the arena (or is in another world) has left it,
     * which also ends their invincibility. Without a radius only teleports and world changes count.
     */
    private fun removeWanderers() {
        val radius = arenaRadius
        if (radius <= 0.0 || participants.isEmpty()) return
        val center = arenaLocation() ?: return
        val maxDistanceSquared = radius * radius

        var changed = false
        for (uuid in participants.toList()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (player.isDead) continue // onDeath deals with them
            val location = player.location
            if (location.world == center.world && location.distanceSquared(center) <= maxDistanceSquared) continue

            participants.remove(uuid)
            removed.add(uuid)
            player.sendMessage(prefixed("walked-out"))
            changed = true
        }
        if (changed) savePendingExits()
    }

    private fun reset() {
        task?.cancel()
        task = null
        participants.clear()
        removed.clear()
        phase = Phase.IDLE
        secondsLeft = 0
        arena = null
    }

    // ------------------------------------------------------------------ //
    //  Players
    // ------------------------------------------------------------------ //

    fun join(player: Player): JoinResult {
        if (phase != Phase.JOIN) return JoinResult.NOT_OPEN
        val uuid = player.uniqueId
        if (uuid in participants) return JoinResult.ALREADY_JOINED
        if (uuid in removed) return JoinResult.CANNOT_REJOIN
        val location = arenaLocation() ?: return JoinResult.NO_LOCATION

        participants.add(uuid)
        if (!teleport(player, location)) {
            // Another plugin (e.g. combat tag) blocked the teleport
            participants.remove(uuid)
            return JoinResult.TELEPORT_FAILED
        }
        savePendingExits()
        return JoinResult.JOINED
    }

    /** /koth leave: out of the arena for good. @return false if the player wasn't in it. */
    fun leave(player: Player): Boolean {
        val uuid = player.uniqueId
        if (!participants.remove(uuid)) return false
        removed.add(uuid)
        teleport(player, exitLocation())
        savePendingExits()
        return true
    }

    fun onDeath(player: Player) {
        val uuid = player.uniqueId
        if (!participants.remove(uuid)) return
        removed.add(uuid)
        respawnAtExit.add(uuid)
        player.sendMessage(prefixed("eliminated"))
        savePendingExits()
    }

    fun onQuit(uuid: UUID) {
        if (!participants.remove(uuid)) return
        removed.add(uuid)
        exitOnLogin.add(uuid)
        savePendingExits()
    }

    /**
     * Portal travel doesn't reach [onTeleport] (PlayerPortalEvent has its own handlers): a participant
     * who changed world without this plugin teleporting them has left the arena.
     */
    fun onWorldChange(player: Player) {
        if (teleporting) return
        val uuid = player.uniqueId
        if (!participants.remove(uuid)) return
        removed.add(uuid)
        player.sendMessage(prefixed("left-arena"))
        savePendingExits()
    }

    /**
     * A participant was teleported by something else (another plugin, an admin): they left the arena.
     * Ender pearls and short hops (anti-cheat setbacks) don't count.
     */
    fun onTeleport(player: Player, from: Location, to: Location, cause: TeleportCause) {
        if (teleporting || cause == TeleportCause.ENDER_PEARL) return
        val uuid = player.uniqueId
        if (uuid !in participants) return
        if (from.world == to.world && from.distanceSquared(to) < LEAVE_DISTANCE * LEAVE_DISTANCE) return

        participants.remove(uuid)
        removed.add(uuid)
        player.sendMessage(prefixed("left-arena"))
        savePendingExits()
    }

    /** Where a participant who died should respawn, once. Null for everyone else. */
    fun takeRespawnLocation(uuid: UUID): Location? = if (respawnAtExit.remove(uuid)) exitLocation() else null

    /** Where a participant who logged out mid-event should be sent on their next login, once. */
    fun takeLoginLocation(uuid: UUID): Location? {
        if (!exitOnLogin.remove(uuid)) return null
        savePendingExits()
        return exitLocation()
    }

    private fun loadPendingExits() {
        if (!pendingExitFile.isFile) return
        val stored = YamlConfiguration.loadConfiguration(pendingExitFile).getStringList("pending-exit")
        for (raw in stored) {
            try {
                exitOnLogin.add(UUID.fromString(raw))
            } catch (e: IllegalArgumentException) {
                // not a UUID: ignore the entry
            }
        }
        savedPendingExits = exitOnLogin.map { it.toString() }.toSet()
    }

    /** Writes [pendingExitFile] if the set of players to send out on login changed. */
    private fun savePendingExits() {
        val pending = (participants + exitOnLogin).map { it.toString() }.toSet()
        if (pending == savedPendingExits) return
        savedPendingExits = pending
        try {
            if (pending.isEmpty()) {
                Files.deleteIfExists(pendingExitFile.toPath())
            } else {
                val yaml = YamlConfiguration()
                yaml.options().header(
                    "Players who were inside the KOTH Arena when they logged out or the server stopped.\n" +
                        "They are sent to the exit location on their next login. Managed by the plugin."
                )
                yaml.set("pending-exit", pending.sorted())
                yaml.save(pendingExitFile)
            }
        } catch (e: IOException) {
            plugin.logger.log(Level.WARNING, "Could not update ${pendingExitFile.name}", e)
        }
    }

    private fun teleport(player: Player, location: Location): Boolean {
        teleporting = true
        try {
            return player.teleport(location)
        } finally {
            teleporting = false
        }
    }

    // ------------------------------------------------------------------ //
    //  Messages
    // ------------------------------------------------------------------ //

    /** `koth-arena.<path>` from messages.yml with the KOTH Arena prefix; %arena% is filled in. */
    fun prefixed(path: String, vararg placeholders: Pair<String, String>): String {
        val messages = plugin.configManager.messages
        return messages.raw("koth-arena.prefix") +
            messages.raw("koth-arena.$path", "%arena%" to (arena?.name ?: "?"), *placeholders)
    }

    private fun broadcast(path: String, vararg placeholders: Pair<String, String>) {
        Bukkit.broadcastMessage(prefixed(path, *placeholders))
    }

    private fun tellParticipants(path: String, vararg placeholders: Pair<String, String>) {
        val message = prefixed(path, *placeholders)
        participants.mapNotNull { Bukkit.getPlayer(it) }.forEach { it.sendMessage(message) }
    }

    /** "3 minute", "10 secunde", ... using `time-units` from messages.yml. */
    fun spoken(seconds: Int): String {
        val messages = plugin.configManager.messages
        return formatSpoken(seconds, messages.getList("time-units.minute"), messages.getList("time-units.second"))
    }

    companion object {
        private const val LEAVE_DISTANCE = 32.0
        private val DEFAULT_ANNOUNCEMENTS = setOf(240, 180, 120, 60, 30, 10, 5, 4, 3, 2, 1)
    }
}
