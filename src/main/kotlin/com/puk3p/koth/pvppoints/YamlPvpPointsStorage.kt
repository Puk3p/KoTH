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
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * pvp-points in a YAML file (pvp-points.storage: yaml), no history:
 * ```
 * players:
 *   <uuid>:
 *     name: "Puk3p"
 *     points: 120
 * ```
 * The whole file is rewritten every save-interval if anything changed, off the main thread,
 * and on disable.
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

    override fun loadAll(): List<PvpPointsEntry> {
        file.parentFile?.let { Files.createDirectories(it.toPath()) }
        if (!file.exists()) return emptyList()

        val yaml = YamlConfiguration()
        try {
            yaml.loadFromString(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        } catch (e: InvalidConfigurationException) {
            // Starting empty would overwrite everyone's points at the next save
            throw IOException("${file.name} is not valid YAML; fix it or restore a backup", e)
        }
        val section = yaml.getConfigurationSection("players") ?: return emptyList()
        val entries = ArrayList<PvpPointsEntry>()
        for (key in section.getKeys(false)) {
            val uuid =
                try {
                    UUID.fromString(key)
                } catch (e: IllegalArgumentException) {
                    logger.warning("${file.name}: skipping '$key', not a player UUID.")
                    continue
                }
            val player = section.getConfigurationSection(key) ?: continue
            val name = player.getString("name")?.trim().orEmpty().ifEmpty { key }
            entries += PvpPointsEntry(uuid, name, player.getInt("points", 0).coerceAtLeast(0))
        }
        return entries
    }

    override fun record(change: PointsChange) {
        version.incrementAndGet()
    }

    override fun flush(
        snapshot: () -> Collection<PvpPointsEntry>,
        runWrite: Executor,
    ) {
        val current = version.get()
        if (current == writtenVersion || current == queuedVersion) return
        queuedVersion = current
        val content = serialize(snapshot())
        runWrite.execute { write(current, content) }
    }

    override fun close(snapshot: () -> Collection<PvpPointsEntry>) {
        flush(snapshot) { it.run() }
    }

    override fun history(
        uuid: UUID,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    ) {
        callbackThread.execute { callback.accept(emptyList()) }
    }

    private fun serialize(entries: Collection<PvpPointsEntry>): String {
        val yaml = YamlConfiguration()
        val section = yaml.createSection("players")
        for (entry in entries.sortedBy { it.name.lowercase(Locale.ROOT) }) {
            val player = section.createSection(entry.uuid.toString())
            player.set("name", entry.name)
            player.set("points", entry.points)
        }
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
