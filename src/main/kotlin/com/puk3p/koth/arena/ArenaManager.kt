package com.puk3p.koth.arena

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.event.KothStartEvent
import com.puk3p.koth.scheduler.ScheduleParser
import com.puk3p.koth.util.ClientDisplay
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.util.UUID
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
     * Returns the first arena whose region contains the location (active or not).
     */
    fun getArenaAt(location: Location): Arena? {
        return arenas.values.firstOrNull { it.region.contains(location) }
    }

    fun clearArenas() {
        arenas.clear()
    }

    // ------------------------------------------------------------------ //
    //  KoTH types (config.yml)
    // ------------------------------------------------------------------ //

    fun getTypes(): Set<String> {
        return plugin.configManager.config.getConfigurationSection("types")?.getKeys(false) ?: emptySet()
    }

    /** Resolves a type typed by a user to its exact config.yml key, ignoring case. */
    fun findType(input: String): String? {
        return getTypes().firstOrNull { it.equals(input, ignoreCase = true) }
    }

    /**
     * Whether a player may count towards capturing this arena.
     * In a KOTH Arena event, only players who joined (and are still in) may enter the zone.
     */
    fun canEnter(arena: Arena, uuid: UUID): Boolean {
        val kothArena = plugin.kothArena
        if (!kothArena.isEventArena(arena)) return true
        return kothArena.isFighting && kothArena.isParticipant(uuid)
    }

    // ------------------------------------------------------------------ //
    //  Persistence (arenas.yml)
    // ------------------------------------------------------------------ //

    /**
     * Replaces the in-memory arenas with the contents of arenas.yml.
     * Invalid entries are skipped with a warning (and left untouched on disk).
     */
    fun loadArenas() {
        arenas.clear()
        val root = plugin.configManager.arenasConfig.getConfigurationSection(ROOT)
        if (root != null) {
            for (name in root.getKeys(false)) {
                val section = root.getConfigurationSection(name)
                if (section == null) {
                    plugin.logger.warning("Skipping arena '$name': not a valid section in arenas.yml.")
                    continue
                }
                parseArena(name, section)?.let { addArena(it) }
            }
        }
        val slots = arenas.values.sumOf { it.schedule.size }
        plugin.logger.info("Loaded ${arenas.size} arena(s) with $slots scheduled start(s) per week.")
    }

    private fun parseArena(name: String, section: ConfigurationSection): Arena? {
        val type = section.getString("type")?.let { findType(it) }
        if (type == null) {
            plugin.logger.warning("Skipping arena '$name': type '${section.getString("type")}' is not defined in config.yml.")
            return null
        }

        val worldName = section.getString("world")
        val region = section.getConfigurationSection("region")
        if (worldName == null || region == null || REGION_KEYS.any { !region.isInt(it) }) {
            plugin.logger.warning("Skipping arena '$name': missing 'world' or integer region coordinates.")
            return null
        }

        val spawn = section.getConfigurationSection("spawn")?.let { sp ->
            val world = Bukkit.getWorld(worldName)
            if (world == null) {
                plugin.logger.warning("Arena '$name': world '$worldName' is not loaded, ignoring its spawn point.")
                null
            } else {
                Location(
                    world,
                    sp.getDouble("x"), sp.getDouble("y"), sp.getDouble("z"),
                    sp.getDouble("yaw").toFloat(), sp.getDouble("pitch").toFloat()
                )
            }
        }

        return Arena(
            name = name,
            type = type,
            region = Region.of(
                worldName,
                region.getInt("min-x"), region.getInt("min-y"), region.getInt("min-z"),
                region.getInt("max-x"), region.getInt("max-y"), region.getInt("max-z")
            ),
            spawnPoint = spawn,
            isEnabled = section.getBoolean("enabled", true),
            schedule = ScheduleParser.parse(section.getConfigurationSection("schedule"), name, plugin.logger)
        )
    }

    /**
     * Writes the arena to arenas.yml and saves the file.
     * Keys this class doesn't manage (e.g. `schedule`) are left as they are.
     */
    fun saveArena(arena: Arena) {
        val config = plugin.configManager.arenasConfig
        val path = "$ROOT.${arena.name}"
        val region = arena.region

        config.set("$path.type", arena.type)
        config.set("$path.enabled", arena.isEnabled)
        config.set("$path.world", region.worldName)
        config.set("$path.region.min-x", region.minX)
        config.set("$path.region.min-y", region.minY)
        config.set("$path.region.min-z", region.minZ)
        config.set("$path.region.max-x", region.maxX)
        config.set("$path.region.max-y", region.maxY)
        config.set("$path.region.max-z", region.maxZ)

        // Only written when known: a spawn skipped at load time (world not loaded) must not be erased.
        arena.spawnPoint?.let { spawn ->
            config.set("$path.spawn.x", spawn.x)
            config.set("$path.spawn.y", spawn.y)
            config.set("$path.spawn.z", spawn.z)
            config.set("$path.spawn.yaw", spawn.yaw.toDouble())
            config.set("$path.spawn.pitch", spawn.pitch.toDouble())
        }

        plugin.configManager.saveArenas()
    }

    fun deleteArena(arena: Arena) {
        removeArena(arena.name)
        plugin.configManager.arenasConfig.set("$ROOT.${arena.name}", null)
        plugin.configManager.saveArenas()
    }

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    enum class StartResult { STARTED, JOIN_OPENED, ALREADY_RUNNING, ANOTHER_RUNNING, CANCELLED, NO_ARENA_LOCATION }

    /** The arena that is running, or used by a KOTH Arena event (join or post-capture phase), if any. */
    fun busyArena(): Arena? {
        return getActiveArena() ?: plugin.kothArena.arena
    }

    /**
     * Entry point for /koth start and the scheduler. KOTH Arena types open /koth join first
     * (the KoTH itself starts when the countdown ends); other types start right away.
     * Ignores the arena's `enabled` flag; that is checked by the scheduler.
     */
    fun launch(arena: Arena): StartResult {
        if (arena == busyArena()) return StartResult.ALREADY_RUNNING
        if (busyArena() != null) return StartResult.ANOTHER_RUNNING
        if (plugin.kothArena.isEventArena(arena)) {
            return if (plugin.kothArena.openJoin(arena)) StartResult.JOIN_OPENED else StartResult.NO_ARENA_LOCATION
        }
        return startArena(arena)
    }

    /**
     * Starts the KoTH itself. Use [launch] instead, except for a KOTH Arena event
     * starting its own arena when the join countdown ends.
     */
    fun startArena(arena: Arena): StartResult {
        if (arena.isActive) return StartResult.ALREADY_RUNNING
        if (getActiveArena() != null) return StartResult.ANOTHER_RUNNING

        val event = KothStartEvent(arena)
        Bukkit.getPluginManager().callEvent(event)
        if (event.isCancelled) return StartResult.CANCELLED

        arena.playersInside.clear()
        arena.resetCapture(captureTime(arena))

        // PlayerMoveEvent only fires on movement, so count players already standing in the zone.
        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.isDead && arena.region.contains(player.location) && canEnter(arena, player.uniqueId)) {
                arena.playersInside.add(player.uniqueId)
            }
        }

        Bukkit.broadcastMessage(
            plugin.configManager.messages.get(
                "events.started",
                "%type%" to arena.type,
                "%arena%" to arena.name
            )
        )
        playSound(arena, "start")
        showTitle(arena, "title-on-start", "%arena%" to arena.name, "%type%" to arena.type)
        plugin.discordService.kothStarted(arena)
        return StartResult.STARTED
    }

    /**
     * Force-stops the arena, announcing it unless [broadcast] is false.
     * @return false if the arena was already idle.
     */
    fun stopArena(arena: Arena, broadcast: Boolean = true): Boolean {
        if (arena.state == ArenaState.WAITING) return false
        resetToIdle(arena)
        if (broadcast) {
            Bukkit.broadcastMessage(plugin.configManager.messages.get("events.stopped", "%arena%" to arena.name))
        }
        plugin.discordService.kothEnded(arena)
        return true
    }

    /** Called by the capture task after a successful capture. */
    fun finishArena(arena: Arena) {
        playSound(arena, "captured")
        resetToIdle(arena)
    }

    private fun resetToIdle(arena: Arena) {
        arena.state = ArenaState.WAITING
        arena.capturingTeam = null
        arena.capturingPlayer = null
        arena.capturingPlayerName = null
        arena.remainingSeconds = 0
        arena.elapsedSeconds = 0
        arena.playersInside.clear()
        plugin.kothArena.onArenaEnded(arena)
    }

    /** Seconds a player must hold the zone, from `types.<type>.capture-time`. */
    fun captureTime(arena: Arena): Int {
        return plugin.configManager.config.getInt("types.${arena.type}.capture-time", 300).coerceAtLeast(1)
    }

    /**
     * Plays `types.<type>.sounds.<key>` to the given players (everyone online by default), if configured.
     * The entry is either a sound name or a section `{ sound, volume, pitch, enabled }`.
     * An invalid sound name is logged once per config path, not on every call.
     */
    fun playSound(arena: Arena, key: String, players: Collection<Player> = Bukkit.getOnlinePlayers()) {
        val config = plugin.configManager.config
        val base = "types.${arena.type}.sounds"
        // Older configs name the tick sound "capture-tick"
        val path = (listOf(key) + SOUND_ALIASES[key].orEmpty()).map { "$base.$it" }.firstOrNull { config.contains(it) } ?: return

        val name: String
        var volume = 1f
        var pitch = 1f
        if (config.isConfigurationSection(path)) {
            if (!config.getBoolean("$path.enabled", true)) return
            name = config.getString("$path.sound") ?: return
            volume = config.getDouble("$path.volume", 1.0).toFloat()
            pitch = config.getDouble("$path.pitch", 1.0).toFloat()
        } else {
            name = config.getString(path) ?: return
        }

        val sound = try {
            Sound.valueOf(name.uppercase())
        } catch (e: IllegalArgumentException) {
            if (invalidSoundPaths.add(path)) {
                plugin.logger.warning("Invalid sound '$name' at $path (use 1.8 Sound names).")
            }
            return
        }
        for (player in players) {
            player.playSound(player.location, sound, volume, pitch)
        }
    }

    private val invalidSoundPaths = mutableSetOf<String>()

    /**
     * Shows `types.<type>.visuals.<key>` (`{ enabled, title, subtitle, fade-in, stay, fade-out }`,
     * times in ticks) to everyone online, if configured.
     */
    fun showTitle(arena: Arena, key: String, vararg placeholders: Pair<String, String>) {
        val config = plugin.configManager.config
        val path = "types.${arena.type}.visuals.$key"
        if (!config.isConfigurationSection(path) || !config.getBoolean("$path.enabled", true)) return

        fun text(field: String): String {
            val raw = ChatColor.translateAlternateColorCodes('&', config.getString("$path.$field", ""))
            return placeholders.fold(raw) { acc, (from, to) -> acc.replace(from, to) }
        }
        val title = text("title")
        val subtitle = text("subtitle")
        val fadeIn = config.getInt("$path.fade-in", 10)
        val stay = config.getInt("$path.stay", 40)
        val fadeOut = config.getInt("$path.fade-out", 10)
        for (player in Bukkit.getOnlinePlayers()) {
            ClientDisplay.title(player, title, subtitle, fadeIn, stay, fadeOut, plugin.logger)
        }
    }

    companion object {
        private const val ROOT = "arenas"
        private val REGION_KEYS = listOf("min-x", "min-y", "min-z", "max-x", "max-y", "max-z")
        private val SOUND_ALIASES = mapOf("capturing-tick" to listOf("capture-tick"))
    }
}
