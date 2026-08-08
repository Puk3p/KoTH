package com.puk3p.koth.services

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import org.bukkit.Bukkit
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.logging.Level

class DiscordService(private val plugin: KoTHPlugin) {

    private val isEnabled: Boolean
        get() = plugin.configManager.config.getBoolean("discord.enabled", false)

    private val webhookUrl: String?
        get() = plugin.configManager.config.getString("discord.webhook-url")

    private val serverIp: String
        get() = plugin.configManager.config.getString("discord.server-ip", "play.yourserver.net") ?: "play.yourserver.net"

    private val footerText: String
        get() = plugin.configManager.config.getString("discord.footer-text", "KoTH System") ?: "KoTH System"

    fun sendKothStarted(arena: Arena) {
        if (!isEnabled || webhookUrl.isNullOrBlank()) return

        val colorHex = plugin.configManager.config.getString("discord.colors.started", "#00FF00") ?: "#00FF00"
        val colorDecimal = hexToDecimal(colorHex)

        val embed = JsonObject().apply {
            addProperty("title", "⚔️ **KoTH Event Active**")
            addProperty("description", "The **${arena.name}** KoTH is now active! Head to the warzone and battle for control.\n\nType `/koth` in-game for more info.")
            addProperty("color", colorDecimal)
            addProperty("timestamp", Instant.now().toString()) // Adds dynamic Discord time

            val footer = JsonObject().apply {
                addProperty("text", "$footerText • $serverIp")
            }
            add("footer", footer)
        }

        sendWebhook(embed)
    }

    fun sendKothCaptured(arena: Arena, playerName: String, factionName: String, durationStr: String) {
        if (!isEnabled || webhookUrl.isNullOrBlank()) return

        val colorHex = plugin.configManager.config.getString("discord.colors.captured", "#FFA500") ?: "#FFA500"
        val colorDecimal = hexToDecimal(colorHex)

        val embed = JsonObject().apply {
            addProperty("title", "🏆 **KoTH Conquered!**")
            addProperty("description", "The **${arena.name}** event has been successfully captured!")
            addProperty("color", colorDecimal)
            addProperty("timestamp", Instant.now().toString())

            // NEW: Fetches the 3D head of the player who won!
            val thumbnail = JsonObject().apply {
                addProperty("url", "https://mc-heads.net/avatar/$playerName/100")
            }
            add("thumbnail", thumbnail)

            val footer = JsonObject().apply {
                addProperty("text", "$footerText • $serverIp")
            }
            add("footer", footer)

            val fields = JsonArray()

            val playerField = JsonObject().apply {
                addProperty("name", "👑 Champion")
                addProperty("value", "`$playerName`")
                addProperty("inline", true)
            }
            val factionField = JsonObject().apply {
                addProperty("name", "🛡️ Faction")
                addProperty("value", "`$factionName`")
                addProperty("inline", true)
            }
            val durationField = JsonObject().apply {
                addProperty("name", "⏱️ Capture Time")
                addProperty("value", "`$durationStr`")
                addProperty("inline", false)
            }

            fields.add(playerField)
            fields.add(factionField)
            fields.add(durationField)

            add("fields", fields)
        }

        sendWebhook(embed)
    }

    /**
     * Sends the actual HTTP POST request asynchronously.
     */
    private fun sendWebhook(embed: JsonObject) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val payload = JsonObject()

                // Set the main bot name/avatar if desired
                payload.addProperty("username", "KoTH Alerts")
                payload.addProperty("avatar_url", "https://mc-heads.net/avatar/MHF_Chest/100")

                val embedsArray = JsonArray()
                embedsArray.add(embed)
                payload.add("embeds", embedsArray)

                val url = URL(webhookUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "KoTH-Plugin")
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    plugin.logger.log(Level.WARNING, "Discord webhook failed with response code: $responseCode")
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to send Discord webhook", e)
            }
        })
    }

    /**
     * Converts a Hex color string (e.g., "#FF0000") to a Discord-compatible decimal integer.
     */
    private fun hexToDecimal(hex: String): Int {
        return try {
            Integer.parseInt(hex.replace("#", ""), 16)
        } catch (e: NumberFormatException) {
            0 // Default to black on error
        }
    }
}