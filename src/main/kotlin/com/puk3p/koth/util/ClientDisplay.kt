package com.puk3p.koth.util

import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * Action bar messages and title timings for 1.8.8, which has no API for either: both are sent as
 * NMS packets through reflection. The reflective lookups run once; if they fail (a server build
 * with different internals) the features turn off with a single warning instead of throwing.
 */
object ClientDisplay {

    private class Nms(
        val serialize: Method, // IChatBaseComponent.ChatSerializer.a(String)
        val chatPacket: Constructor<*>, // PacketPlayOutChat(IChatBaseComponent, byte)
        val timesPacket: Constructor<*>?, // PacketPlayOutTitle(int, int, int)
        val getHandle: Method,
        val playerConnection: Field,
        val sendPacket: Method,
    )

    private var nms: Nms? = null
    private var initialized = false

    private fun nms(logger: Logger): Nms? {
        if (initialized) return nms
        initialized = true
        nms = try {
            val version = Bukkit.getServer().javaClass.getPackage().name.split(".")[3]
            fun nmsClass(name: String) = Class.forName("net.minecraft.server.$version.$name")

            val component = nmsClass("IChatBaseComponent")
            val packet = nmsClass("Packet")
            val craftPlayer = Class.forName("org.bukkit.craftbukkit.$version.entity.CraftPlayer")
            val getHandle = craftPlayer.getMethod("getHandle")
            val connectionField = getHandle.returnType.getField("playerConnection")
            val timesPacket = runCatching {
                nmsClass("PacketPlayOutTitle").getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            }.getOrNull()

            Nms(
                serialize = nmsClass("IChatBaseComponent\$ChatSerializer").getMethod("a", String::class.java),
                chatPacket = nmsClass("PacketPlayOutChat").getConstructor(component, Byte::class.javaPrimitiveType),
                timesPacket = timesPacket,
                getHandle = getHandle,
                playerConnection = connectionField,
                sendPacket = connectionField.type.getMethod("sendPacket", packet),
            )
        } catch (e: Exception) {
            logger.warning("Action bars and title timings are unavailable on this server build: ${e.javaClass.simpleName}")
            null
        }
        return nms
    }

    /** Shows [message] (already colored) above the hotbar. */
    fun actionBar(player: Player, message: String, logger: Logger) {
        val nms = nms(logger) ?: return
        try {
            // Gson escapes quotes, backslashes and the like that would break hand-written JSON
            val json = JsonObject().apply { addProperty("text", message) }.toString()
            val component = nms.serialize.invoke(null, json)
            send(nms, player, nms.chatPacket.newInstance(component, 2.toByte())) // 2 = action bar
        } catch (e: Exception) {
            // A player who logged out mid-send, for example: nothing worth reporting
        }
    }

    /**
     * Shows a title. The 1.8.8 API's sendTitle has no timings, so they're sent first as their own
     * packet when possible; times are in ticks.
     */
    fun title(player: Player, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int, logger: Logger) {
        val nms = nms(logger)
        if (nms?.timesPacket != null) {
            try {
                send(nms, player, nms.timesPacket.newInstance(fadeIn, stay, fadeOut))
            } catch (e: Exception) {
                // fall back to the client's default timings
            }
        }
        @Suppress("DEPRECATION")
        player.sendTitle(title, subtitle)
    }

    private fun send(nms: Nms, player: Player, packet: Any) {
        val handle = nms.getHandle.invoke(player)
        val connection = nms.playerConnection.get(handle) ?: return
        nms.sendPacket.invoke(connection, packet)
    }
}
