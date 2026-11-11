package com.puk3p.koth.services

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant

/**
 * Posts KoTH events to a Discord webhook (`discord` in config.yml) as embeds.
 * Texts come from the `discord` section of messages.yml. Payloads are built on the main thread
 * and sent asynchronously; nothing is sent while the plugin is shutting down.
 */
class DiscordService(private val plugin: KoTHPlugin) {

    private var warnedMisconfigured = false

    fun kothStarted(arena: Arena) {
        send("started", "%arena%" to arena.name, "%type%" to arena.type)
    }

    fun kothCaptured(arena: Arena, playerName: String, faction: String) {
        send("captured", "%arena%" to arena.name, "%type%" to arena.type, "%player%" to playerName, "%faction%" to faction)
    }

    /** A KoTH stopped without a capture (/koth stop, KOTH Arena cancelled or emptied, shutdown). */
    fun kothEnded(arena: Arena) {
        send("ended", "%arena%" to arena.name, "%type%" to arena.type)
    }

    private fun send(event: String, vararg placeholders: Pair<String, String>) {
        val config = plugin.configManager.config
        if (!config.getBoolean("discord.enabled", false)) return
        // Async tasks can't be scheduled while disabling, and blocking shutdown on HTTP isn't worth it.
        if (!plugin.isEnabled) return

        val url = config.getString("discord.webhook-url", "").trim()
        if (!url.startsWith("https://") || url.contains("your-webhook-url-here")) {
            if (!warnedMisconfigured) {
                warnedMisconfigured = true
                plugin.logger.warning("discord.enabled is true but discord.webhook-url is not set; skipping Discord messages.")
            }
            return
        }

        val messages = plugin.configManager.messages
        val payload = buildPayload(
            title = ChatColor.stripColor(messages.raw("discord.$event-title", *placeholders)),
            description = ChatColor.stripColor(messages.raw("discord.$event-description", *placeholders)),
            color = parseColor(config.getString("discord.colors.$event", "")),
            timestamp = Instant.now()
        )
        val userAgent = "KoTH-Plugin/${plugin.description.version}"
        val logger = plugin.logger

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            postJson(url, payload, userAgent)?.let { logger.warning(it) }
        })
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val DEFAULT_COLOR = 0x2F3136

        /** A webhook body with one embed. Gson handles escaping. */
        fun buildPayload(title: String, description: String, color: Int, timestamp: Instant): String {
            val embed = JsonObject().apply {
                addProperty("title", title)
                addProperty("description", description)
                addProperty("color", color)
                addProperty("timestamp", timestamp.toString())
            }
            return JsonObject().apply {
                add("embeds", JsonArray().apply { add(embed) })
            }.toString()
        }

        /** "#FFA500" or "FFA500" -> 0xFFA500; anything else -> Discord's default embed color. */
        fun parseColor(hex: String): Int {
            return hex.trim().removePrefix("#").toIntOrNull(16)?.takeIf { it in 0..0xFFFFFF } ?: DEFAULT_COLOR
        }

        /**
         * POSTs [payload] to the webhook. Blocking: call off the main thread.
         * @return a warning to log on failure, or null on success. Never contains the URL (it holds the token).
         */
        fun postJson(url: String, payload: String, userAgent: String): String? {
            return try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = TIMEOUT_MS
                    connection.readTimeout = TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    // Discord (Cloudflare) rejects Java's default User-Agent
                    connection.setRequestProperty("User-Agent", userAgent)
                    connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                    val code = connection.responseCode
                    if (code in 200..299) null else "Discord webhook returned HTTP $code."
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                "Could not reach the Discord webhook: ${e.javaClass.simpleName}"
            } catch (e: URISyntaxException) {
                "discord.webhook-url is not a valid URL."
            } catch (e: IllegalArgumentException) {
                "discord.webhook-url is not a valid URL."
            }
        }
    }
}
