package com.puk3p.koth.arena

import org.bukkit.Location
import java.util.UUID

class Arena(
    val name: String,
    val type: String, // Maps to the config type (e.g., "superkoth")
    val region: Region,
    var spawnPoint: Location? = null,
    var isEnabled: Boolean = true
) {
    var state: ArenaState = ArenaState.WAITING
    var capturingPlayer: UUID? = null
    var remainingSeconds: Int = 0

    // Cached set of players inside the region.
    // Updated by PlayerMoveEvent, read by the CaptureTickTask.
    val playersInside = mutableSetOf<UUID>()

    val isActive: Boolean
        get() = state == ArenaState.RUNNING || state == ArenaState.CONTESTED

    fun resetCapture(defaultSeconds: Int) {
        capturingPlayer = null
        remainingSeconds = defaultSeconds
        state = ArenaState.RUNNING
    }
}

enum class ArenaState {
    WAITING,    // KoTH is not active
    RUNNING,    // KoTH is active, someone might be capturing
    CONTESTED,  // Multiple eligible players in the zone
    ENDED       // Capture finished, waiting for cleanup
}