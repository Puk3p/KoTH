package com.puk3p.koth.arena

import org.bukkit.Location
import kotlin.math.max
import kotlin.math.min

data class Region(
    val worldName: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    companion object {
        fun fromLocations(loc1: Location, loc2: Location): Region {
            require(loc1.world == loc2.world) { "Locations must be in the same world" }
            return Region(
                worldName = loc1.world.name,
                minX = min(loc1.blockX, loc2.blockX),
                minY = min(loc1.blockY, loc2.blockY),
                minZ = min(loc1.blockZ, loc2.blockZ),
                maxX = max(loc1.blockX, loc2.blockX),
                maxY = max(loc1.blockY, loc2.blockY),
                maxZ = max(loc1.blockZ, loc2.blockZ)
            )
        }
    }

    /**
     * Highly optimized check. Avoids heavy Bukkit API calls.
     */
    fun contains(location: Location): Boolean {
        if (location.world.name != worldName) return false
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }
}