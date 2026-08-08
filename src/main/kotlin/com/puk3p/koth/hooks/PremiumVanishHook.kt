package com.puk3p.koth.hooks

import de.myzelyam.api.vanish.VanishAPI
import org.bukkit.entity.Player

class PremiumVanishHook {

    fun isVanished(player: Player): Boolean {
        return try {
            VanishAPI.isInvisible(player)
        } catch (e: Exception) {
            false
        }
    }
}