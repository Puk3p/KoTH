package com.puk3p.koth.services

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import org.bukkit.Bukkit
import org.bukkit.entity.Player

class RewardService(private val plugin: KoTHPlugin) {

    fun giveRewards(arena: Arena, player: Player) {
        val type = arena.type
        val rewards = plugin.configManager.config.getStringList("types.$type.rewards")

        val factionTag = plugin.factionsHook?.getFaction(player) ?: "No Faction"

        // Ensure commands execute synchronously
        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (command in rewards) {
                var parsedCommand = command
                    .replace("%player%", player.name)
                    .replace("%koth%", arena.name)
                    .replace("%faction%", factionTag)
                    .replace("%type%", type)

                // If PlaceholderAPI is enabled, let it parse any extra placeholders
                if (plugin.placeholderAPIEnabled) {
                    parsedCommand = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, parsedCommand)
                }

                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand)
            }
        })
    }
}