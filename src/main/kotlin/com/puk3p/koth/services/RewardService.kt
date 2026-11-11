package com.puk3p.koth.services

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.hooks.FactionsHook
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.logging.Level

class RewardService(private val plugin: KoTHPlugin) {

    /**
     * Runs `types.<type>.rewards` from the console on the next tick.
     * Placeholders: %player%, %arena% (or %koth%), %faction%, %type%, plus PlaceholderAPI's.
     */
    fun giveRewards(arena: Arena, player: Player) {
        val type = arena.type
        val rewards = plugin.configManager.config.getStringList("types.$type.rewards")
        if (rewards.isEmpty()) return

        val factionTag = plugin.factionsHook?.getFaction(player) ?: FactionsHook.NO_FACTION

        // Ensure commands execute synchronously
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (command in rewards) {
                var parsedCommand = command
                    .replace("%player%", player.name)
                    .replace("%koth%", arena.name)
                    .replace("%arena%", arena.name)
                    .replace("%faction%", factionTag)
                    .replace("%type%", type)

                // One broken reward (a typo, a command that throws) must not cost the winner the others
                try {
                    // If PlaceholderAPI is enabled, let it parse any extra placeholders
                    if (plugin.placeholderAPIEnabled) {
                        parsedCommand = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, parsedCommand)
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand)
                } catch (e: Exception) {
                    plugin.logger.log(Level.SEVERE, "Reward command failed for ${player.name}: $parsedCommand", e)
                }
            }
        })
    }
}
