package com.puk3p.koth.arena

import com.puk3p.koth.scheduler.ScheduleEntry
import org.bukkit.Location
import java.util.Collections
import java.util.UUID

/**
 * A KoTH capture zone and its live state. State is written on the main thread only; the fields
 * PlaceholderAPI reads from other threads are volatile.
 */
class Arena(
    val name: String,
    // Maps to the config type (e.g., "superkoth")
    val type: String,
    val region: Region,
    var spawnPoint: Location? = null,
    var isEnabled: Boolean = true,
    schedule: List<ScheduleEntry> = emptyList(),
) {
    // Weekly start slots from arenas.yml. Changed with /koth schedule through ArenaManager.setSchedule,
    // which swaps in a new list, so PlaceholderAPI threads always see a complete one.
    @Volatile
    var schedule: List<ScheduleEntry> = schedule
        internal set

    @Volatile
    var state: ArenaState = ArenaState.WAITING

    // Team holding the zone: "f:<factionId>" or "p:<playerUUID>" (see CaptureTickTask.teamOf).
    var capturingTeam: String? = null

    // The team member credited with the capture (messages, KothCaptureEvent, rewards).
    @Volatile
    var capturingPlayer: UUID? = null

    // Kept alongside the UUID so messages and placeholders don't need a player-data lookup
    // once the player is offline.
    @Volatile
    var capturingPlayerName: String? = null

    @Volatile
    var remainingSeconds: Int = 0

    // Seconds since this KoTH started, for `max-duration`.
    var elapsedSeconds: Int = 0

    // Players inside the region, in order of entry. Updated by PlayerListener, read by CaptureTickTask.
    // Synchronized so PlaceholderAPI can call size/contains from other threads; only the main
    // thread modifies or iterates it.
    val playersInside: MutableSet<UUID> = Collections.synchronizedSet(LinkedHashSet())

    val isActive: Boolean
        get() = state == ArenaState.RUNNING || state == ArenaState.CONTESTED

    fun resetCapture(defaultSeconds: Int) {
        capturingTeam = null
        capturingPlayer = null
        capturingPlayerName = null
        remainingSeconds = defaultSeconds
        elapsedSeconds = 0
        state = ArenaState.RUNNING
    }
}

enum class ArenaState {
    WAITING, // KoTH is not active
    RUNNING, // KoTH is active, someone might be capturing
    CONTESTED, // Players from more than one team in the zone
    ENDED, // Capture finished, waiting for cleanup
}
