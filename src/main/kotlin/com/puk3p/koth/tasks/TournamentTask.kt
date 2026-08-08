package com.puk3p.koth.tasks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable

class TournamentTask(
    private val plugin: KoTHPlugin,
    private val arena: Arena
) : BukkitRunnable() {

    private var timeLeft = plugin.configManager.config.getInt("types.${arena.type}.tournament-mode.registration-time", 300)

    init {
        plugin.tournamentManager.openRegistration()
        arena.isRegistrationOpen = true // Lock capturing
    }

    override fun run() {
        // If arena was stopped manually during registration, cancel the task
        if (!arena.isActive) {
            arena.isRegistrationOpen = false
            cancel()
            return
        }

        if (timeLeft <= 0) {
            // Close registration phase
            plugin.tournamentManager.closeRegistration()
            arena.isRegistrationOpen = false // Unlock capturing!

            // FIX: Sweep the arena and kick unregistered campers (but ignore admins/vanished players)
            val unregisteredCampers = arena.playersInside.filter { !plugin.tournamentManager.isParticipant(it) }
            for (uuid in unregisteredCampers) {
                val player = Bukkit.getPlayer(uuid)
                if (player != null) {
                    if (player.hasPermission("koth.admin") || plugin.premiumVanishHook?.isVanished(player) == true) {
                        continue // Let staff and vanished players stay!
                    }
                    // Teleport them to the server spawn
                    player.teleport(player.world.spawnLocation)
                    player.sendMessage(plugin.configManager.messages.get("tournament.not-registered"))
                }
                // Remove them from the cached list of players in the zone
                arena.playersInside.remove(uuid)
            }

            val msg = plugin.configManager.messages.get("tournament.registration-closed", "%arena%" to arena.name)
            Bukkit.broadcastMessage(msg)

            cancel()
            return
        }

        // Broadcast countdowns (e.g., at 5 mins, 4 mins, 3 mins, 2 mins, 1 min, 30s, 10s)
        if (timeLeft % 60 == 0 || timeLeft == 30 || timeLeft <= 10) {
            val msg = plugin.configManager.messages.get("tournament.registration-opened",
                "%arena%" to arena.name,
                "%time%" to formatTime(timeLeft)
            )
            Bukkit.broadcastMessage(msg)
        }

        timeLeft--
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }
}