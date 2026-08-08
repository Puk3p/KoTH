package com.puk3p.koth.utils

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player

object SoundUtil {

    fun playTypeSound(plugin: KoTHPlugin, type: String, key: String, target: Player? = null) {
        val path = "types.$type.sounds.$key"
        if (!plugin.configManager.config.getBoolean("$path.enabled", true)) return

        val soundName = plugin.configManager.config.getString("$path.sound") ?: return
        val volume = plugin.configManager.config.getDouble("$path.volume", 1.0).toFloat()
        val pitch = plugin.configManager.config.getDouble("$path.pitch", 1.0).toFloat()

        val sound = try {
            Sound.valueOf(soundName.uppercase())
        } catch (e: IllegalArgumentException) {
            plugin.logger.warning("Invalid sound name in config: $soundName")
            return
        }

        if (target != null) {
            target.playSound(target.location, sound, volume, pitch)
        } else {
            for (player in Bukkit.getOnlinePlayers()) {
                player.playSound(player.location, sound, volume, pitch)
            }
        }
    }
}