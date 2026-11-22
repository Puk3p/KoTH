package com.puk3p.koth.util

import org.bukkit.ChatColor

/** [text] without § and & color codes, trimmed (/f who puts the relation color in front of tags). */
fun stripColorCodes(text: String): String {
    val translated = ChatColor.translateAlternateColorCodes('&', text)
    return ChatColor.stripColor(translated)?.trim().orEmpty()
}
