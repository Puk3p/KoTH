package com.puk3p.koth.arena

import com.puk3p.koth.enums.ArenaState
import org.bukkit.Location
import java.util.UUID

class Arena(
    val name: String,
    val type: String,
    val region: Region,
    var spawnPoint: Location? = null,
    var isEnabled: Boolean = true
) {
    var state: ArenaState = ArenaState.WAITING
    var capturingPlayer: UUID? = null
    var remainingSeconds: Int = 0

    // NEW: Tracks total event duration
    var totalActiveSeconds: Int = 0

    var isRegistrationOpen: Boolean = false

    val playersInside = mutableSetOf<UUID>()

    val isActive: Boolean
        get() = state == ArenaState.RUNNING || state == ArenaState.CONTESTED

    fun resetCapture(defaultSeconds: Int) {
        capturingPlayer = null
        remainingSeconds = defaultSeconds
        totalActiveSeconds = 0 // Reset total time
        state = ArenaState.RUNNING
    }
}