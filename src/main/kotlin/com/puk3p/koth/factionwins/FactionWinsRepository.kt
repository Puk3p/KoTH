package com.puk3p.koth.factionwins

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

data class FactionWins(
    val faction: String,
    val wins: Int,
)

/**
 * Faction wins stored in a YAML file, same format as the FKothWins addon so its file can be reused:
 * ```
 * wins:
 *   <base64 of the name>:
 *     name: "FactionTag"
 *     amount: 12
 * ```
 * Faction names are matched ignoring case. Every change is written to disk right away.
 * Reads are safe from other threads (PlaceholderAPI); writes happen on the main thread.
 */
class FactionWinsRepository(
    private val file: File,
    private val logger: Logger,
) {
    private val winsByFaction = ConcurrentHashMap<String, Int>()

    init {
        file.parentFile?.let { folder ->
            try {
                Files.createDirectories(folder.toPath())
            } catch (e: IOException) {
                logger.log(Level.SEVERE, "Could not create ${folder.path}; faction wins won't be saved", e)
            }
        }
        if (file.exists()) {
            load()
        }
    }

    fun getWins(faction: String): Int {
        val key = resolveExistingKey(faction) ?: return 0
        return winsByFaction[key] ?: 0
    }

    fun isTracked(faction: String): Boolean = resolveExistingKey(faction) != null

    fun addWins(
        faction: String,
        amount: Int,
    ): Int {
        val key = resolveExistingKey(faction) ?: faction
        // Long arithmetic: a huge amount must cap at Int.MAX_VALUE, not wrap around to 0
        val newValue = ((winsByFaction[key] ?: 0).toLong() + amount).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        winsByFaction[key] = newValue
        save()
        return newValue
    }

    /** Never creates an entry: removing from a faction that isn't tracked (a typo) does nothing. */
    fun removeWins(
        faction: String,
        amount: Int,
    ): Int {
        val key = resolveExistingKey(faction) ?: return 0
        return addWins(key, -amount)
    }

    /**
     * A faction changed its tag: its wins follow it. Anything stored under the new name belongs to
     * a faction that no longer exists (two factions can't share a tag) and is replaced.
     * @return the wins moved, or null if the old name had none
     */
    fun rename(
        oldName: String,
        newName: String,
    ): Int? {
        val oldKey = resolveExistingKey(oldName) ?: return null
        val wins = winsByFaction.remove(oldKey) ?: return null
        resolveExistingKey(newName)?.let { winsByFaction.remove(it) }
        winsByFaction[newName] = wins
        save()
        return wins
    }

    fun setWins(
        faction: String,
        amount: Int,
    ): Int {
        val key = resolveExistingKey(faction) ?: faction
        val value = amount.coerceAtLeast(0)
        winsByFaction[key] = value
        save()
        return value
    }

    fun removeFaction(faction: String) {
        val key = resolveExistingKey(faction) ?: return
        winsByFaction.remove(key)
        save()
    }

    /** Most wins first; ties by name, so the order is stable. */
    fun top(limit: Int): List<FactionWins> {
        // Placeholders ask for this many times a second (every top_<n> line, every rank); the
        // ranking only changes when wins change, so sort once per change.
        val sorted =
            sortedCache ?: winsByFaction.entries
                .map { FactionWins(it.key, it.value) }
                .sortedWith(compareByDescending<FactionWins> { it.wins }.thenBy { it.faction.lowercase(Locale.ROOT) })
                .also { sortedCache = it }
        return if (limit >= sorted.size) sorted else sorted.subList(0, limit.coerceAtLeast(0))
    }

    @Volatile
    private var sortedCache: List<FactionWins>? = null

    fun trackedFactionCount(): Int = winsByFaction.size

    fun save() {
        sortedCache = null // every change ends in save()
        val yaml = YamlConfiguration()
        val winsSection = yaml.createSection("wins")
        for ((faction, wins) in winsByFaction) {
            val entry = winsSection.createSection(encodeKey(faction))
            entry.set("name", faction)
            entry.set("amount", wins)
        }
        try {
            Files.write(file.toPath(), yaml.saveToString().toByteArray(StandardCharsets.UTF_8))
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "Could not save ${file.name}", e)
        }
    }

    private fun load() {
        winsByFaction.clear()
        sortedCache = null
        val yaml = YamlConfiguration()
        try {
            yaml.loadFromString(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "Could not read ${file.name}", e)
            return
        } catch (e: InvalidConfigurationException) {
            logger.log(Level.SEVERE, "${file.name} is not valid YAML; starting with no faction wins", e)
            return
        }

        val winsSection = yaml.getConfigurationSection("wins")
        if (winsSection != null) {
            for (key in winsSection.getKeys(false)) {
                val entry = winsSection.getConfigurationSection(key) ?: continue
                val factionName = entry.getString("name")?.trim().orEmpty()
                if (factionName.isEmpty()) continue
                winsByFaction[factionName] = entry.getInt("amount", 0).coerceAtLeast(0)
            }
            return
        }

        // Legacy format of early FKothWins versions: faction names used directly as YAML paths,
        // so a name like "Team.One" became nested sections. Rebuild the names.
        loadLegacySection(yaml, "")
    }

    private fun loadLegacySection(
        section: ConfigurationSection,
        prefix: String,
    ) {
        for (key in section.getKeys(false)) {
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            if (section.isInt(key)) {
                winsByFaction[fullKey] = section.getInt(key, 0).coerceAtLeast(0)
                continue
            }
            val child = section.getConfigurationSection(key) ?: continue
            loadLegacySection(child, fullKey)
        }
    }

    private fun resolveExistingKey(faction: String): String? {
        if (winsByFaction.containsKey(faction)) return faction
        val needle = faction.lowercase(Locale.ROOT)
        return winsByFaction.keys.firstOrNull { it.lowercase(Locale.ROOT) == needle }
    }

    private fun encodeKey(raw: String): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }
}
