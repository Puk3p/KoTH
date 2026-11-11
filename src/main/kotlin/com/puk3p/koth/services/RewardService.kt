package com.puk3p.koth.services

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.hooks.FactionsHook
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.ThreadLocalRandom
import java.util.logging.Level

class RewardService(private val plugin: KoTHPlugin) {
    /**
     * Runs `types.<type>.rewards` from the console on the next tick. Two formats:
     * ```
     * rewards:                      # every command runs
     *   - "give %player% diamond 16"
     *
     * rewards:
     *   mode: "CHANCE"              # ALL: every command runs; CHANCE: each one rolls its chance
     *   commands:
     *     - command: "crate give %player% admin 1"
     *       chance: 15.0            # percent
     * ```
     * Placeholders: %player%, %arena% (or %koth%), %faction%, %type%, plus PlaceholderAPI's.
     */
    fun giveRewards(
        arena: Arena,
        player: Player,
    ) {
        val config = plugin.configManager.config
        val path = "types.${arena.type}.rewards"

        val commands =
            if (config.isList(path)) {
                config.getStringList(path)
            } else {
                val rollChances = config.getString("$path.mode", "ALL").equals("CHANCE", ignoreCase = true)
                config.getMapList("$path.commands").mapNotNull { entry ->
                    val command = entry["command"]?.toString() ?: return@mapNotNull null
                    val chance = entry["chance"]?.toString()?.toDoubleOrNull() ?: 100.0
                    if (rollChances && ThreadLocalRandom.current().nextDouble(0.0, 100.0) >= chance) null else command
                }
            }
        if (commands.isEmpty()) return

        val factionTag = plugin.factionsHook?.getFaction(player) ?: FactionsHook.NO_FACTION

        // Ensure commands execute synchronously
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                for (command in commands) {
                    var parsedCommand =
                        command
                            .replace("%player%", player.name)
                            .replace("%koth%", arena.name)
                            .replace("%arena%", arena.name)
                            .replace("%faction%", factionTag)
                            .replace("%type%", arena.type)

                    // One broken reward (a typo, a command that throws) must not cost the winner the others
                    try {
                        // If PlaceholderAPI is enabled, let it parse any extra placeholders
                        if (plugin.placeholderAPIEnabled) {
                            parsedCommand = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, parsedCommand)
                        }
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsedCommand.removePrefix("/"))
                    } catch (e: Exception) {
                        plugin.logger.log(Level.SEVERE, "Reward command failed for ${player.name}: $parsedCommand", e)
                    }
                }
            },
        )
    }
}
