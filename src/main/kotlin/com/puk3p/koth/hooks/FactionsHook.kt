package com.puk3p.koth.hooks

import dev.kitteh.factions.FPlayers
import org.bukkit.entity.Player

class FactionsHook {

    fun getFaction(player: Player): String {
        return try {
            val fPlayer = FPlayers.fPlayers().get(player)

            if (fPlayer.hasFaction()) {
                // Force it to a String so it matches "No Faction"
                fPlayer.faction().tag().toString()
            } else {
                "No Faction"
            }
        } catch (e: Exception) {
            "No Faction" // Fallback in case of API mismatch
        }
    }

    fun getFactionId(player: Player): String {
        return try {
            val fPlayer = FPlayers.fPlayers().get(player)

            if (fPlayer.hasFaction()) {
                // Convert the UUID object to a String
                fPlayer.faction().id().toString()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}