package com.puk3p.koth.arena

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.enums.ArenaState
import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class ArenaManager(private val plugin: KoTHPlugin) {

    // Thread-safe map mapped by lowercase arena name
    private val arenas = ConcurrentHashMap<String, Arena>()

    fun loadArenas() {
        clearArenas()
        val config = plugin.configManager.arenasConfig
        val section = config.getConfigurationSection("arenas")

        if (section == null) {
            plugin.logger.info("No arenas found in arenas.yml to load.")
            return
        }

        var loadedCount = 0

        for (key in section.getKeys(false)) {
            try {
                val path = "arenas.$key"
                val type = config.getString("$path.type") ?: "koth"
                val isEnabled = config.getBoolean("$path.enabled", true)
                val worldName = config.getString("$path.world") ?: continue

                val minX = config.getInt("$path.region.min-x")
                val minY = config.getInt("$path.region.min-y")
                val minZ = config.getInt("$path.region.min-z")
                val maxX = config.getInt("$path.region.max-x")
                val maxY = config.getInt("$path.region.max-y")
                val maxZ = config.getInt("$path.region.max-z")

                val region = Region(worldName, minX, minY, minZ, maxX, maxY, maxZ)
                val arena = Arena(key, type, region)
                arena.isEnabled = isEnabled

                addArena(arena)
                loadedCount++
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Failed to load arena: $key. Check your arenas.yml!", e)
            }
        }

        plugin.logger.info("Successfully loaded $loadedCount arenas from memory.")
    }

    fun addArena(arena: Arena) {
        arenas[arena.name.lowercase()] = arena
    }

    fun removeArena(name: String) {
        arenas.remove(name.lowercase())
    }

    fun getArena(name: String): Arena? {
        return arenas[name.lowercase()]
    }

    fun getArenas(): Collection<Arena> {
        return arenas.values
    }

    /**
     * Returns the currently active arena, if any.
     * Assumes only one KoTH runs at a time based on standard faction server logic.
     */
    fun getActiveArena(): Arena? {
        return arenas.values.firstOrNull { it.isActive }
    }

    /**
     * Quickly checks if a location is inside ANY active arena region.
     */
    fun getArenaAt(location: Location): Arena? {
        return arenas.values.firstOrNull { it.region.contains(location) }
    }

    fun clearArenas() {
        // First, stop any active events cleanly
        getActiveArena()?.let { activeArena ->
            activeArena.state = ArenaState.WAITING
            activeArena.capturingPlayer = null
            activeArena.playersInside.clear()
        }
        arenas.clear()
    }
}