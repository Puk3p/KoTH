package com.puk3p.koth.pvppoints

import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Faction pvp-points in a YAML file (pvp-points.storage: yaml), no history:
 * ```
 * factions:
 * - id: "12"
 *   tag: "Warriors"
 *   points: 120
 * ```
 * A list rather than keys, because Bukkit would read a dot in a key as a nested section.
 * The whole file is rewritten every save-interval if anything changed, off the main thread,
 * and on disable. A file from 1.1.2 (points per player, under `players:`) is kept aside as
 * `<file>.per-player` and the faction points start empty.
 */
class YamlPvpPointsStorage(
    private val file: File,
    private val logger: Logger,
) : PvpPointsStorage {
    // Bumped on every change; a write records the version it contains, so an older write never
    // replaces a newer one and an unchanged store isn't written again.
    private val version = AtomicLong()

    @Volatile
    private var queuedVersion = 0L

    @Volatile
    private var writtenVersion = 0L
    private val writeLock = Any()

    override val keepsHistory: Boolean = false

    override fun loadAll(): List<FactionPointsEntry> {
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        if (!file.exists()) return emptyList()

        val yaml = YamlConfiguration()
        try {
            yaml.loadFromString(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        } catch (e: InvalidConfigurationException) {
            // Starting empty would overwrite every faction's points at the next save
            throw IOException("${file.name} is not valid YAML; fix it or restore a backup", e)
        }
        if (!yaml.contains("factions") && yaml.isConfigurationSection("players")) {
            keepPerPlayerFile()
            return emptyList()
        }

        val entries = ArrayList<FactionPointsEntry>()
        for (map in yaml.getMapList("factions")) {
            val id = map["id"]?.toString()?.trim().orEmpty()
            if (id.isEmpty()) {
                logger.warning("${file.name}: skipping an entry without an id: $map")
                continue
            }
            val tag = map["tag"]?.toString()?.trim().orEmpty().ifEmpty { id }
            val points = (map["points"] as? Number)?.toLong()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
            entries += FactionPointsEntry(id, tag, points)
        }
        return entries
    }

    /** The 1.1.2 per-player file: moved aside, never overwritten. */
    private fun keepPerPlayerFile() {
        var target = File(file.parentFile, "${file.name}.per-player")
        var n = 2
        while (target.exists()) target = File(file.parentFile, "${file.name}.per-player-${n++}")
        Files.move(file.toPath(), target.toPath())
        logger.warning(
            "[PvpPoints] ${file.name} has points per player (KoTH 1.1.2). Points now belong to factions, so it was " +
                "renamed to ${target.name} and faction points start at 0.",
        )
    }

    override fun record(change: PointsChange) {
        version.incrementAndGet()
    }

    override fun flush(
        snapshot: () -> Collection<FactionPointsEntry>,
        runWrite: Executor,
    ) {
        val current = version.get()
        if (current == writtenVersion || current == queuedVersion) return
        queuedVersion = current
        val content = serialize(snapshot())
        runWrite.execute { write(current, content) }
    }

    override fun close(snapshot: () -> Collection<FactionPointsEntry>) {
        flush(snapshot) { it.run() }
    }

    override fun history(
        factionId: String,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    ) {
        callbackThread.execute { callback.accept(emptyList()) }
    }

    private fun serialize(entries: Collection<FactionPointsEntry>): String {
        val yaml = YamlConfiguration()
        val list =
            entries.sortedBy { it.tag.lowercase(Locale.ROOT) }.map {
                linkedMapOf<String, Any>("id" to it.factionId, "tag" to it.tag, "points" to it.points)
            }
        yaml.set("factions", list)
        return yaml.saveToString()
    }

    private fun write(
        contentVersion: Long,
        content: String,
    ) {
        synchronized(writeLock) {
            if (contentVersion <= writtenVersion) return
            // Write a temporary file and move it over the old one, so a crash mid-write never leaves half a file
            val target = file.toPath()
            val temp = target.resolveSibling("${file.name}.tmp")
            try {
                Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                writtenVersion = contentVersion
            } catch (e: IOException) {
                logger.log(Level.SEVERE, "Could not save ${file.name}", e)
            }
        }
    }
}
