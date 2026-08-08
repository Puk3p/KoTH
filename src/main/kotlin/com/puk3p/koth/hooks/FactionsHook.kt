package com.puk3p.koth.hooks

import com.massivecraft.factions.FPlayers
import dev.kitteh.factions.FPlayers
import org.bukkit.entity.Player

class FactionsHook {

    fun getFaction(player: Player): String {
        return try {
            val fPlayer = FPlayers.getInstance().getByPlayer(player)
            if (fPlayer.hasFaction()) {
                fPlayer.faction.tag
            } else {
                "No Faction"
            }
        } catch (e: Exception) {
            "No Faction" // Fallback in case of API mismatch
        }
    }

    fun getFactionId(player: Player): String {
        return try {
            val fPlayer = FPlayers.getInstance().getByPlayer(player)
            if (fPlayer.hasFaction()) fPlayer.faction.id else ""
        } catch (e: Exception) {
            ""
        }
    }
}