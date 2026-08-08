package com.puk3p.koth.tournament

import com.puk3p.koth.KoTHPlugin
import java.util.UUID

class TournamentManager(private val plugin: KoTHPlugin) {

    // We store UUIDs of players who typed /koth join
    private val participants = mutableSetOf<UUID>()

    // We store UUIDs of players who died during the tournament
    private val eliminated = mutableSetOf<UUID>()

    // State flag determining if people can still join
    private var registrationClosed = false

    fun isParticipant(uuid: UUID): Boolean {
        return participants.contains(uuid)
    }

    fun isEliminated(uuid: UUID): Boolean {
        return eliminated.contains(uuid)
    }

    fun eliminatePlayer(uuid: UUID) {
        if (participants.contains(uuid)) {
            eliminated.add(uuid)
        }
    }

    fun isRegistrationClosed(): Boolean {
        return registrationClosed
    }

    fun openRegistration() {
        participants.clear()
        eliminated.clear()
        registrationClosed = false
    }

    fun closeRegistration() {
        registrationClosed = true
    }

    fun addParticipant(uuid: UUID): Boolean {
        if (registrationClosed || isEliminated(uuid)) return false
        return participants.add(uuid)
    }

    fun removeParticipant(uuid: UUID) {
        participants.remove(uuid)
    }
}