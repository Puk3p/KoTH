package com.puk3p.koth.arena

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Location
import java.util.concurrent.ConcurrentHashMap

class ArenaManager(private val plugin: KoTHPlugin) {

    // Thread-safe map mapped by lowercase arena name
    private val arenas = ConcurrentHashMap<String, Arena>()

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
        arenas.clear()
    }
}