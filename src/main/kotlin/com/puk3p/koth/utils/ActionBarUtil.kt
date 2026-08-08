package com.puk3p.koth.utils

import org.bukkit.Bukkit
import org.bukkit.entity.Player

object ActionBarUtil {
    private val nmsVersion = Bukkit.getServer().javaClass.getPackage().name.split(".")[3]

    fun sendActionBar(player: Player, message: String) {
        try {
            val chatCompClass = Class.forName("net.minecraft.server.$nmsVersion.IChatBaseComponent")
            val chatSerClass = Class.forName("net.minecraft.server.$nmsVersion.IChatBaseComponent\$ChatSerializer")
            val packetClass = Class.forName("net.minecraft.server.$nmsVersion.PacketPlayOutChat")

            // Create the JSON component
            val component = chatSerClass.getMethod("a", String::class.java).invoke(null, "{\"text\": \"$message\"}")

            // 2.toByte() is the position for Action Bar in 1.8
            val packet = packetClass.getConstructor(chatCompClass, Byte::class.javaPrimitiveType).newInstance(component, 2.toByte())

            val craftPlayer = player.javaClass.getMethod("getHandle").invoke(player)
            val playerConnection = craftPlayer.javaClass.getField("playerConnection").get(craftPlayer)

            val sendPacketMethod = playerConnection.javaClass.getMethod("sendPacket", Class.forName("net.minecraft.server.$nmsVersion.Packet"))
            sendPacketMethod.invoke(playerConnection, packet)
        } catch (e: Exception) {
            // Fails silently if API changes, preventing server crashes
        }
    }
}