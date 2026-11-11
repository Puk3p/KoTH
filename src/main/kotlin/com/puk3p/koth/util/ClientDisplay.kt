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
 * A failure while sending is also logged once and turns the feature off, so a broken packet can
 * never break the capture loop that calls this every second.
 */
object ClientDisplay {
    private class Nms(
        // IChatBaseComponent.ChatSerializer.a(String)
        val serialize: Method,
        // PacketPlayOutChat(IChatBaseComponent, byte)
        val chatPacket: Constructor<*>,
        // PacketPlayOutTitle(int, int, int)
        val timesPacket: Constructor<*>?,
        val getHandle: Method,
        val playerConnection: Field,
        val sendPacket: Method,
    )

    private var nms: Nms? = null
    private var initialized = false

    private fun nms(logger: Logger): Nms? {
        if (initialized) return nms
        initialized = true
        nms =
            try {
                lookUp()
            } catch (e: ReflectiveOperationException) {
                unavailable(logger, e)
            } catch (e: RuntimeException) {
                // e.g. an unexpected CraftBukkit package name
                unavailable(logger, e)
            }
        return nms
    }

    private fun lookUp(): Nms {
        val version = Bukkit.getServer().javaClass.getPackage().name.split(".")[3]

        fun nmsClass(name: String) = Class.forName("net.minecraft.server.$version.$name")

        val component = nmsClass("IChatBaseComponent")
        val packet = nmsClass("Packet")
        val craftPlayer = Class.forName("org.bukkit.craftbukkit.$version.entity.CraftPlayer")
        val getHandle = craftPlayer.getMethod("getHandle")
        val connectionField = getHandle.returnType.getField("playerConnection")
        val int = Int::class.javaPrimitiveType
        val timesPacket =
            try {
                nmsClass("PacketPlayOutTitle").getConstructor(int, int, int)
            } catch (e: ReflectiveOperationException) {
                null // titles then use the client's default timings
            }

        return Nms(
            serialize = nmsClass("IChatBaseComponent\$ChatSerializer").getMethod("a", String::class.java),
            chatPacket = nmsClass("PacketPlayOutChat").getConstructor(component, Byte::class.javaPrimitiveType),
            timesPacket = timesPacket,
            getHandle = getHandle,
            playerConnection = connectionField,
            sendPacket = connectionField.type.getMethod("sendPacket", packet),
        )
    }

    private fun unavailable(
        logger: Logger,
        e: Exception,
    ): Nms? {
        logger.warning("Action bars and title timings are unavailable on this server build: ${e.javaClass.simpleName}")
        return null
    }

    /** Shows [message] (already colored) above the hotbar. */
    fun actionBar(
        player: Player,
        message: String,
        logger: Logger,
    ) {
        val nms = nms(logger) ?: return
        // Gson escapes quotes, backslashes and the like that would break hand-written JSON
        val json = JsonObject().apply { addProperty("text", message) }.toString()
        safely(logger) {
            val component = nms.serialize.invoke(null, json)
            send(nms, player, nms.chatPacket.newInstance(component, 2.toByte())) // 2 = action bar
        }
    }

    /**
     * Shows a title. The 1.8.8 API's sendTitle has no timings, so they're sent first as their own
     * packet when possible; times are in ticks.
     */
    fun title(
        player: Player,
        title: String,
        subtitle: String,
        fadeIn: Int,
        stay: Int,
        fadeOut: Int,
        logger: Logger,
    ) {
        val nms = nms(logger)
        val times = nms?.timesPacket
        if (nms != null && times != null) {
            safely(logger) { send(nms, player, times.newInstance(fadeIn, stay, fadeOut)) }
        }
        @Suppress("DEPRECATION")
        player.sendTitle(title, subtitle)
    }

    /** Runs a packet send; on failure, logs once and turns packets off for the rest of the run. */
    private inline fun safely(
        logger: Logger,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: ReflectiveOperationException) {
            disable(logger, e)
        } catch (e: RuntimeException) {
            disable(logger, e)
        }
    }

    private fun disable(
        logger: Logger,
        e: Exception,
    ) {
        if (nms == null) return
        nms = null
        logger.warning("Sending action bars / title timings failed (${e.javaClass.simpleName}); they're off until the next restart.")
    }

    private fun send(
        nms: Nms,
        player: Player,
        packet: Any,
    ) {
        val handle = nms.getHandle.invoke(player)
        val connection = nms.playerConnection.get(handle) ?: return
        nms.sendPacket.invoke(connection, packet)
    }
}
