package com.puk3p.koth.factionwins

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.util.ConfigLocations
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.scheduler.BukkitTask
import java.lang.reflect.Method

/**
 * Top factions hologram through DecentHolograms (config.yml -> faction-wins.hologram).
 * Uses DecentHolograms' DHAPI by reflection, so it's an optional runtime dependency.
 *
 * One instance per configuration: [FactionWinsModule] stops it and starts a new one on reload.
 * The hologram is created on the first refresh that finds its location (the world may be loaded
 * later than this plugin, e.g. by Multiverse) and removed again by [stop].
 */
class TopHologram(
    private val plugin: KoTHPlugin,
    private val service: FactionWinsService,
) {
    private var task: BukkitTask? = null
    private var warnedMissingApi = false

    // True once this instance has put the hologram at the configured location
    private var placed = false

    private val config get() = plugin.configManager.config
    private val id: String get() = config.getString("faction-wins.hologram.id", "fkoth_top5")

    fun start() {
        if (!config.getBoolean("faction-wins.hologram.enabled", true)) return
        if (plugin.server.pluginManager.getPlugin("DecentHolograms") == null) {
            plugin.logger.info("[FKoth] DecentHolograms not found; top hologram disabled.")
            return
        }

        val refreshTicks = config.getLong("faction-wins.hologram.refresh-seconds", 15L).coerceAtLeast(5L) * 20L
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { update() }, 1L, refreshTicks)
        if (location() == null) {
            plugin.logger.info("[FKoth] Top hologram has no usable location yet (/fkoth sethologram); it appears once it does.")
        }
    }

    /** Stops refreshing and removes the hologram (it isn't saved by DecentHolograms, so nothing is left behind). */
    fun stop() {
        task?.cancel()
        task = null
        if (placed) {
            dhApi()?.let { remove(it) }
            placed = false
        }
    }

    /** For /fkoth debug. */
    fun status(): String =
        when {
            task == null -> "DISABLED"
            placed -> "ENABLED"
            else -> "WAITING FOR LOCATION"
        }

    private fun location(): Location? {
        return ConfigLocations.read(config.getConfigurationSection("faction-wins.hologram-location"))
    }

    private fun update() {
        val location = location() ?: return
        val dhApi = dhApi() ?: return
        val lines = buildLines()

        var hologram: Any? = null
        if (placed) {
            hologram = getHologram(dhApi)
        } else {
            // A hologram left under this id (an old location, a previous run) would keep its old
            // position: DHAPI only sets the location on creation. Start clean.
            remove(dhApi)
        }
        if (hologram == null) {
            hologram = create(dhApi, location, lines) ?: getHologram(dhApi)
        }
        placed = hologram != null
        if (hologram == null) return

        val setLines = dhApi.methods.firstOrNull { it.name == "setHologramLines" && it.parameterCount == 2 } ?: return
        runCatching { setLines.invoke(null, hologram, lines) }
            .onFailure { plugin.logger.warning("[FKoth] Could not update the hologram: ${it.message}") }
    }

    private fun buildLines(): List<String> {
        val entries = config.getInt("faction-wins.hologram.entries", 5).coerceIn(1, 10)
        val title = config.getString("faction-wins.hologram.title", "&6&lTop 5 KOTH Factions")
        val lineFormat = config.getString("faction-wins.hologram.line-format", "&e#%position%. &f%faction% &7- &6%wins%")
        val emptyFormat = config.getString("faction-wins.hologram.empty-line-format", "&e#%position%. &7-")

        val top = service.top(entries)
        val lines = mutableListOf(color(title))
        for (position in 1..entries) {
            val item = top.getOrNull(position - 1)
            val line =
                if (item == null) {
                    emptyFormat.replace("%position%", position.toString())
                } else {
                    lineFormat
                        .replace("%position%", position.toString())
                        .replace("%faction%", item.faction)
                        .replace("%wins%", item.wins.toString())
                }
            lines.add(color(line))
        }
        return lines
    }

    private fun dhApi(): Class<*>? {
        val api = runCatching { Class.forName("eu.decentsoftware.holograms.api.DHAPI") }.getOrNull()
        if (api == null && !warnedMissingApi) {
            warnedMissingApi = true
            plugin.logger.warning("[FKoth] DHAPI class not found. Is DecentHolograms up to date?")
        }
        return api
    }

    private fun getHologram(dhApi: Class<*>): Any? {
        val method = dhApi.methods.firstOrNull { it.name == "getHologram" && it.parameterCount == 1 } ?: return null
        return runCatching { method.invoke(null, id) }.getOrNull()
    }

    private fun remove(dhApi: Class<*>) {
        val method =
            dhApi.methods.firstOrNull {
                it.name == "removeHologram" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
            } ?: return
        runCatching { method.invoke(null, id) }
    }

    /** DHAPI.createHologram has a few overloads across versions; use whichever exists. */
    private fun create(
        dhApi: Class<*>,
        location: Location,
        lines: List<String>,
    ): Any? {
        val creates: List<Method> = dhApi.methods.filter { it.name == "createHologram" }

        fun Method.takes(vararg types: Class<*>): Boolean =
            parameterCount == types.size && types.indices.all { types[it].isAssignableFrom(parameterTypes[it]) }

        // List::class.java is java.util.List
        val withLines = creates.firstOrNull { it.takes(String::class.java, Location::class.java, List::class.java) }
        val withPersistent =
            creates.firstOrNull {
                it.parameterCount == 4 && it.parameterTypes[0] == String::class.java &&
                    Location::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                    (it.parameterTypes[2] == Boolean::class.javaPrimitiveType || it.parameterTypes[2] == Boolean::class.javaObjectType) &&
                    List::class.java.isAssignableFrom(it.parameterTypes[3])
            }
        val simple = creates.firstOrNull { it.takes(String::class.java, Location::class.java) }

        return runCatching {
            when {
                withLines != null -> withLines.invoke(null, id, location, lines)
                withPersistent != null -> withPersistent.invoke(null, id, location, false, lines)
                simple != null -> simple.invoke(null, id, location)
                else -> null
            }
        }.getOrNull()
    }

    private fun color(text: String): String = ChatColor.translateAlternateColorCodes('&', text)
}
