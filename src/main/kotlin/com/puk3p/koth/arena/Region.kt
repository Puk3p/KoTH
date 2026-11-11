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
            return of(loc1.world.name, loc1.blockX, loc1.blockY, loc1.blockZ, loc2.blockX, loc2.blockY, loc2.blockZ)
        }

        /** Builds a region from two corners in any order. */
        fun of(worldName: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): Region {
            return Region(
                worldName = worldName,
                minX = min(x1, x2),
                minY = min(y1, y2),
                minZ = min(z1, z2),
                maxX = max(x1, x2),
                maxY = max(y1, y2),
                maxZ = max(z1, z2)
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