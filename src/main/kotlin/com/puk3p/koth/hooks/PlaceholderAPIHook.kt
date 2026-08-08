package com.puk3p.koth.hooks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class PlaceholderAPIHook(private val plugin: KoTHPlugin) : PlaceholderExpansion() {

    override fun getIdentifier(): String {
        return "koth"
    }

    override fun getAuthor(): String {
        return plugin.description.authors.joinToString(", ")
    }

    override fun getVersion(): String {
        return plugin.description.version
    }

    override fun persist(): Boolean {
        return true
    }

    override fun canRegister(): Boolean {
        return true
    }

    override fun onPlaceholderRequest(player: Player?, params: String): String {
        val activeArena = plugin.arenaManager.getActiveArena()

        if (activeArena == null) {
            return when (params.lowercase()) {
                "active" -> "None"
                "status" -> "WAITING"
                "capture_time" -> "00:00"
                "capturer" -> "None"
                "capturer_faction" -> "None"
                else -> ""
            }
        }

        return when (params.lowercase()) {
            "active" -> activeArena.name
            "status" -> activeArena.state.name
            "capture_time" -> formatTime(activeArena.remainingSeconds)
            "capturer" -> {
                val capperId = activeArena.capturingPlayer
                if (capperId != null) Bukkit.getPlayer(capperId)?.name ?: "Unknown" else "None"
            }
            "capturer_faction" -> {
                val capperId = activeArena.capturingPlayer
                if (capperId != null) {
                    val capper = Bukkit.getPlayer(capperId)
                    if (capper != null && plugin.factionsHook != null) {
                        plugin.factionsHook!!.getFaction(capper)
                    } else "No Faction"
                } else "None"
            }
            // Add next_name, next_time once Scheduler is implemented
            else -> ""
        }
    }

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return String.format("%02d:%02d", m, s)
    }
}