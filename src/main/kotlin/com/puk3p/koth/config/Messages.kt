package com.puk3p.koth.config

import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration

class Messages(private val config: FileConfiguration) {

    /**
     * Fetches a string from messages.yml, translates color codes, and applies placeholders.
     * Usage: messages.get("koth-started", "%koth%" to arena.name)
     */
    fun get(path: String, vararg placeholders: Pair<String, String>): String {
        var message = config.getString(path) ?: "&cMessage not found: $path"
        message = ChatColor.translateAlternateColorCodes('&', message)

        placeholders.forEach { (key, value) ->
            message = message.replace(key, value)
        }

        return message
    }

    /**
     * Fetches a multiline string list from messages.yml.
     */
    fun getList(path: String, vararg placeholders: Pair<String, String>): List<String> {
        return config.getStringList(path).map { line ->
            var formatted = ChatColor.translateAlternateColorCodes('&', line)
            placeholders.forEach { (key, value) ->
                formatted = formatted.replace(key, value)
            }
            formatted
        }
    }
}