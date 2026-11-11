package com.puk3p.koth.config

import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration

class Messages(private val config: FileConfiguration) {

    /**
     * Fetches a string from messages.yml, prepends the configured prefix, translates color codes
     * and applies placeholders.
     * Usage: messages.get("events.started", "%arena%" to arena.name)
     */
    fun get(path: String, vararg placeholders: Pair<String, String>): String {
        return color(config.getString("prefix", "")) + raw(path, *placeholders)
    }

    /**
     * Same as [get], without the prefix. Use for lines that belong to a block (list entries, headers).
     */
    fun raw(path: String, vararg placeholders: Pair<String, String>): String {
        var message = color(config.getString(path) ?: "&cMessage not found: $path")

        placeholders.forEach { (key, value) ->
            message = message.replace(key, value)
        }

        return message
    }

    /**
     * Fetches a multiline string list from messages.yml (no prefix).
     */
    fun getList(path: String, vararg placeholders: Pair<String, String>): List<String> {
        return config.getStringList(path).map { line ->
            var formatted = color(line)
            placeholders.forEach { (key, value) ->
                formatted = formatted.replace(key, value)
            }
            formatted
        }
    }

    private fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)
}
