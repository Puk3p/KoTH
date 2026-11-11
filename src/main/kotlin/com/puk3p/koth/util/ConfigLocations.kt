package com.puk3p.koth.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.configuration.ConfigurationSection
import java.util.Locale

/** Locations stored in config.yml as world / x / y / z / yaw / pitch. */
object ConfigLocations {

    /** @return null if the section is missing or its world isn't loaded. */
    fun read(section: ConfigurationSection?): Location? {
        if (section == null) return null
        val world = section.getString("world")?.let { Bukkit.getWorld(it) } ?: return null
        return Location(
            world,
            section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
            section.getDouble("yaw").toFloat(), section.getDouble("pitch").toFloat()
        )
    }

    fun toYamlLines(location: Location): List<String> {
        return toYamlLines(location.world.name, location.x, location.y, location.z, location.yaw, location.pitch)
    }

    fun toYamlLines(world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): List<String> {
        return listOf(
            "world: \"${world.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
            "x: ${number(x)}",
            "y: ${number(y)}",
            "z: ${number(z)}",
            "yaw: ${number(yaw.toDouble())}",
            "pitch: ${number(pitch.toDouble())}"
        )
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /**
     * Replaces the `parent.child` block in YAML [text] with `child:` followed by [childLines],
     * or adds it if missing. Every other line, comments included, stays as it was.
     *
     * The child block is the `child:` line plus the more-indented lines right after it
     * (a blank line ends it). An inline value (`child: {...}`) is replaced the same way.
     */
    fun setBlock(text: String, parent: String, child: String, childLines: List<String>): String {
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val lines = text.split("\n").map { it.removeSuffix("\r") }.toMutableList()

        val parentIndex = lines.indexOfFirst { isKeyLine(it, parent, 0) }
        if (parentIndex < 0) {
            val insertAt = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.size - 1 else lines.size
            val block = listOf("", "$parent:", "  $child:") + childLines.map { "    $it" }
            lines.addAll(insertAt, block)
            return lines.joinToString(newline)
        }

        // The parent's block runs until the next top-level key
        var parentEnd = parentIndex + 1
        while (parentEnd < lines.size && !isTopLevelKey(lines[parentEnd])) parentEnd++

        val childIndent = (parentIndex + 1 until parentEnd)
            .map { lines[it] }
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.let { indentOf(it) }
            ?: 2
        val pad = " ".repeat(childIndent)
        val block = listOf("$pad$child:") + childLines.map { "$pad  $it" }

        val childIndex = (parentIndex + 1 until parentEnd).firstOrNull { isKeyLine(lines[it], child, childIndent) }
        if (childIndex == null) {
            lines.addAll(parentIndex + 1, block)
        } else {
            var childEnd = childIndex + 1
            while (childEnd < parentEnd && lines[childEnd].isNotBlank() && indentOf(lines[childEnd]) > childIndent) {
                childEnd++
            }
            repeat(childEnd - childIndex) { lines.removeAt(childIndex) }
            lines.addAll(childIndex, block)
        }
        return lines.joinToString(newline)
    }

    private fun isKeyLine(line: String, key: String, indent: Int): Boolean {
        return indentOf(line) == indent && line.trimStart().startsWith("$key:")
    }

    private fun isTopLevelKey(line: String): Boolean {
        return line.isNotBlank() && indentOf(line) == 0 && !line.startsWith("#")
    }

    private fun indentOf(line: String): Int = line.length - line.trimStart(' ').length
}
