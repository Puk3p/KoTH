package com.puk3p.koth.services

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.ThreadLocalRandom

class RewardService(private val plugin: KoTHPlugin) {

    fun giveRewards(arena: Arena, player: Player) {
        val type = arena.type
        val typePath = "types.$type.rewards"
        val factionTag = plugin.factionsHook?.getFaction(player) ?: "No Faction"

        val mode = plugin.configManager.config.getString("$typePath.mode", "ALL")?.uppercase() ?: "ALL"

        // Legacy compatibility check (if configuration uses raw list)
        if (plugin.configManager.config.isList(typePath)) {
            val legacyRewards = plugin.configManager.config.getStringList(typePath)
            executeCommands(player, arena, factionTag, legacyRewards)
            return
        }

        val commandsList = mutableListOf<String>()
        val commandSections = plugin.configManager.config.getMapList("$typePath.commands")

        for (map in commandSections) {
            val cmd = map["command"]?.toString() ?: continue
            val chance = (map["chance"]?.toString()?.toDoubleOrNull()) ?: 100.0

            if (mode == "CHANCE") {
                val roll = ThreadLocalRandom.current().nextDouble(0.0, 100.0)
                if (roll <= chance) {
                    commandsList.add(cmd)
                }
            } else {
                commandsList.add(cmd)
            }
        }

        executeCommands(player, arena, factionTag, commandsList)
    }

    private fun executeCommands(player: Player, arena: Arena, factionTag: String, commands: List<String>) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            commands.forEach { rawCommand ->
                var parsedCommand = rawCommand
                    .replace("%player%", player.name)
                    .replace("%koth%", arena.name)
                    .replace("%faction%", factionTag)
                    .replace("%type%", arena.type)

                if (plugin.placeholderAPIEnabled) {
                    parsedCommand = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, parsedCommand)
                }

                val finalCommand = parsedCommand.removePrefix("/")
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand)
            }
        })
    }
}