package com.puk3p.koth.pvppoints

import com.puk3p.koth.database.Database
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Faction pvp-points in MySQL (pvp-points.storage: mysql), with a history of every change.
 *
 * Tables (prefix from database.table-prefix):
 * - `pvp_faction_points`: one row per faction (Factions id, tag, points), the current balance;
 * - `pvp_faction_points_log`: one row per change (delta, balance after it, reason, member, detail, time).
 * The per-player tables of 1.1.2 (`pvp_points`, `pvp_points_log`) are left as they are.
 *
 * One server writes the table, so the balances in memory are the truth and every change is
 * stored as the new balance: writing it twice (a retry) gives the same result. Changes go to MySQL
 * in order on one background thread, in batches, each batch in one transaction with its history.
 *
 * If MySQL is down, writes wait and retry; the server keeps running on the balances in memory.
 * On shutdown, changes MySQL still hasn't taken are saved to [pendingFile] and written first
 * thing on the next start ([loadAll]), so nothing is lost.
 */
class MySqlPvpPointsStorage(
    private val database: Database,
    private val logger: Logger,
    private val pendingFile: File,
) : PvpPointsStorage {
    private val points = database.table("pvp_faction_points")
    private val log = database.table("pvp_faction_points_log")

    private val queue = LinkedBlockingQueue<Task>()
    private val worker = Thread({ runWorker() }, "KoTH-PvpPoints-MySQL").apply { isDaemon = true }

    @Volatile
    private var stopping = false

    // Changes not written because MySQL failed during shutdown; saved to pendingFile
    private val unsaved = ArrayList<PointsChange>()

    @Volatile
    private var inFlight: List<PointsChange> = emptyList()
    private val retryLock = Object()

    override val keepsHistory: Boolean = true

    private sealed class Task {
        class Write(val change: PointsChange) : Task()

        class Read(val action: (Connection) -> Unit, val onError: (Exception) -> Unit) : Task()

        object Stop : Task()
    }

    /** Creates or updates the tables, writes changes left over from the last shutdown, then reads every balance. */
    override fun loadAll(): List<FactionPointsEntry> {
        database.migrate(COMPONENT, schema())
        replayPending()

        val entries = ArrayList<FactionPointsEntry>()
        database.connection().use { c ->
            c.prepareStatement("SELECT faction_id, tag, points FROM $points").use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        entries += FactionPointsEntry(rs.getString(1), rs.getString(2), rs.getInt(3).coerceAtLeast(0))
                    }
                }
            }
        }
        worker.start()
        return entries
    }

    /** Copies balances from the YAML file into an empty table (switching from yaml to mysql). Blocking. */
    fun importAll(
        entries: Collection<FactionPointsEntry>,
        source: String,
    ) {
        val now = System.currentTimeMillis()
        val changes =
            entries.map { PointsChange(it.factionId, it.tag, it.points, it.points, PointsReason.IMPORT, null, source, now) }
        changes.chunked(BATCH_SIZE).forEach { writeBatch(it) }
    }

    override fun record(change: PointsChange) {
        queue.put(Task.Write(change))
    }

    // Changes are written as they happen; nothing to do on the save timer.
    override fun flush(
        snapshot: () -> Collection<FactionPointsEntry>,
        runWrite: Executor,
    ) = Unit

    override fun close(snapshot: () -> Collection<FactionPointsEntry>) {
        stopping = true
        queue.put(Task.Stop)
        synchronized(retryLock) { retryLock.notifyAll() }
        if (worker.isAlive) worker.join(CLOSE_TIMEOUT_MILLIS)

        val leftover = ArrayList<PointsChange>()
        synchronized(unsaved) { leftover += unsaved }
        if (worker.isAlive) {
            // Stuck on a dead connection: its batch may or may not have been written. Saving it again is
            // safe for balances (they're totals, not additions); at worst history gets a duplicate line.
            leftover += inFlight
        }
        queue.forEach { if (it is Task.Write) leftover += it.change }
        if (leftover.isNotEmpty()) savePending(leftover)
    }

    override fun history(
        factionId: String,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    ) {
        // Queued behind pending writes, so the history includes every change made before the request
        queue.put(
            Task.Read(
                action = { c ->
                    val lines = ArrayList<HistoryEntry>()
                    c.prepareStatement(
                        "SELECT delta, balance, reason, player, detail, created_at FROM $log WHERE faction_id = ? " +
                            "ORDER BY id DESC LIMIT ?",
                    ).use { ps ->
                        ps.setString(1, factionId)
                        ps.setInt(2, limit)
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                lines +=
                                    HistoryEntry(
                                        rs.getInt(1),
                                        rs.getInt(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getLong(6),
                                    )
                            }
                        }
                    }
                    callbackThread.execute { callback.accept(lines) }
                },
                onError = { callbackThread.execute { callback.accept(null) } },
            ),
        )
    }

    /** Deletes history older than [olderThanMillis] (epoch ms), in small steps so the table never locks for long. */
    fun deleteHistoryBefore(olderThanMillis: Long) {
        queue.put(
            Task.Read(
                action = { c ->
                    var deleted = 0
                    c.prepareStatement("DELETE FROM $log WHERE created_at < ? LIMIT $DELETE_STEP").use { ps ->
                        ps.setLong(1, olderThanMillis)
                        do {
                            val step = ps.executeUpdate()
                            deleted += step
                        } while (step == DELETE_STEP && !stopping)
                    }
                    if (deleted > 0) logger.info("[PvpPoints] Deleted $deleted old history line(s).")
                },
                onError = { e -> logger.log(Level.WARNING, "[PvpPoints] Could not delete old history", e) },
            ),
        )
    }

    private fun runWorker() {
        while (true) {
            when (val task = queue.take()) {
                is Task.Stop -> return
                is Task.Read -> runRead(task)
                is Task.Write -> {
                    val batch = arrayListOf(task.change)
                    // Take the writes queued right behind it too (not past a read, to keep the order)
                    while (batch.size < BATCH_SIZE) {
                        val next = queue.peek() as? Task.Write ?: break
                        queue.poll()
                        batch += next.change
                    }
                    writeWithRetry(batch)
                }
            }
        }
    }

    // Runs even while stopping: it was asked for before the shutdown, and close() waits a bounded time
    private fun runRead(task: Task.Read) {
        try {
            database.connection().use { task.action(it) }
        } catch (e: SQLException) {
            task.onError(e)
        } catch (e: RuntimeException) {
            task.onError(e)
        }
    }

    private fun writeWithRetry(batch: List<PointsChange>) {
        inFlight = batch
        var delay = FIRST_RETRY_MILLIS
        var failing = false
        while (true) {
            if (stopping && failing) {
                synchronized(unsaved) { unsaved += batch }
                break
            }
            try {
                writeBatch(batch)
                if (failing) logger.info("[PvpPoints] MySQL is back; saved the changes that were waiting.")
                break
            } catch (e: SQLException) {
                if (!failing) logSaveFailure(e)
                failing = true
            } catch (e: RuntimeException) {
                // Never let the worker die: every later change would silently stay unsaved
                if (!failing) logSaveFailure(e)
                failing = true
            }
            if (!stopping) {
                synchronized(retryLock) { retryLock.wait(delay) }
                delay = (delay * 2).coerceAtMost(MAX_RETRY_MILLIS)
            }
        }
        inFlight = emptyList()
    }

    private fun logSaveFailure(e: Exception) {
        logger.log(
            Level.SEVERE,
            "[PvpPoints] Could not save to MySQL; retrying until it works. Points keep working in memory meanwhile.",
            e,
        )
    }

    @Throws(SQLException::class)
    private fun writeBatch(batch: List<PointsChange>) {
        database.connection().use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    "INSERT INTO $points (faction_id, tag, points, created_at, updated_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE tag = VALUES(tag), points = VALUES(points), updated_at = VALUES(updated_at)",
                ).use { ps ->
                    for (change in batch) {
                        ps.setString(1, change.factionId)
                        ps.setString(2, change.tag.take(TAG_LENGTH))
                        ps.setInt(3, change.balance)
                        ps.setLong(4, change.time)
                        ps.setLong(5, change.time)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                val logged = batch.filter { it.reason.logged }
                if (logged.isNotEmpty()) {
                    c.prepareStatement(
                        "INSERT INTO $log (faction_id, tag, delta, balance, reason, player, detail, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    ).use { ps ->
                        for (change in logged) {
                            ps.setString(1, change.factionId)
                            ps.setString(2, change.tag.take(TAG_LENGTH))
                            ps.setInt(3, change.delta)
                            ps.setInt(4, change.balance)
                            ps.setString(5, change.reason.name)
                            ps.setString(6, change.player?.take(PLAYER_LENGTH))
                            ps.setString(7, change.detail?.take(DETAIL_LENGTH))
                            ps.setLong(8, change.time)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                c.commit()
            } catch (e: SQLException) {
                rollbackQuietly(c)
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    private fun rollbackQuietly(c: Connection) {
        try {
            c.rollback()
        } catch (e: SQLException) {
            logger.log(Level.FINE, "[PvpPoints] Rollback failed", e)
        }
    }

    private fun savePending(changes: List<PointsChange>) {
        // Append to what an earlier failed start may have left
        val yaml = YamlConfiguration()
        val existing = if (pendingFile.exists()) readPending() else emptyList()
        yaml.set("changes", (existing + changes).map { toMap(it) })
        try {
            Files.write(pendingFile.toPath(), yaml.saveToString().toByteArray(StandardCharsets.UTF_8))
            logger.severe(
                "[PvpPoints] MySQL didn't take ${changes.size} change(s) before shutdown. They're saved in " +
                    "${pendingFile.name} and will be written on the next start.",
            )
        } catch (e: IOException) {
            logger.log(Level.SEVERE, "[PvpPoints] Could not save unsaved changes to ${pendingFile.name}; they are:", e)
            changes.forEach { logger.severe("[PvpPoints]   $it") }
        }
    }

    private fun replayPending() {
        if (!pendingFile.exists()) return
        val changes = readPending()
        changes.chunked(BATCH_SIZE).forEach { writeBatch(it) }
        Files.delete(pendingFile.toPath())
        logger.info("[PvpPoints] Wrote ${changes.size} change(s) left over from the last shutdown (${pendingFile.name}).")
    }

    private fun readPending(): List<PointsChange> {
        val yaml = YamlConfiguration()
        yaml.loadFromString(String(Files.readAllBytes(pendingFile.toPath()), StandardCharsets.UTF_8))
        return yaml.getMapList("changes").mapNotNull { fromMap(it) }
    }

    private fun toMap(change: PointsChange): Map<String, Any?> =
        linkedMapOf(
            "faction_id" to change.factionId,
            "tag" to change.tag,
            "delta" to change.delta,
            "balance" to change.balance,
            "reason" to change.reason.name,
            "player" to change.player,
            "detail" to change.detail,
            "time" to change.time,
        )

    private fun fromMap(map: Map<*, *>): PointsChange? {
        return try {
            PointsChange(
                factionId = map["faction_id"]!!.toString(),
                tag = map["tag"]!!.toString(),
                delta = (map["delta"] as Number).toInt(),
                balance = (map["balance"] as Number).toInt(),
                reason = PointsReason.valueOf(map["reason"].toString()),
                player = map["player"]?.toString(),
                detail = map["detail"]?.toString(),
                time = (map["time"] as Number).toLong(),
            )
        } catch (e: RuntimeException) {
            logger.warning("[PvpPoints] ${pendingFile.name}: skipping an unreadable change $map")
            null
        }
    }

    private fun schema(): List<List<String>> =
        listOf(
            // Version 1
            listOf(
                "CREATE TABLE IF NOT EXISTS $points (" +
                    "faction_id VARCHAR($ID_LENGTH) NOT NULL PRIMARY KEY, " +
                    "tag VARCHAR($TAG_LENGTH) NOT NULL, " +
                    "points INT NOT NULL DEFAULT 0, " +
                    "created_at BIGINT NOT NULL, " +
                    "updated_at BIGINT NOT NULL, " +
                    "INDEX idx_points (points), " +
                    "INDEX idx_tag (tag)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                "CREATE TABLE IF NOT EXISTS $log (" +
                    "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, " +
                    "faction_id VARCHAR($ID_LENGTH) NOT NULL, " +
                    "tag VARCHAR($TAG_LENGTH) NOT NULL, " +
                    "delta INT NOT NULL, " +
                    "balance INT NOT NULL, " +
                    "reason VARCHAR(16) NOT NULL, " +
                    "player VARCHAR($PLAYER_LENGTH) NULL, " +
                    "detail VARCHAR($DETAIL_LENGTH) NULL, " +
                    "created_at BIGINT NOT NULL, " +
                    "INDEX idx_faction_time (faction_id, created_at), " +
                    "INDEX idx_player (player), " +
                    "INDEX idx_time (created_at)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            ),
        )

    companion object {
        const val COMPONENT = "pvp-faction-points"
        private const val BATCH_SIZE = 200
        private const val DELETE_STEP = 5000
        private const val ID_LENGTH = 36
        private const val TAG_LENGTH = 32
        private const val PLAYER_LENGTH = 32
        private const val DETAIL_LENGTH = 64
        private const val FIRST_RETRY_MILLIS = 1000L
        private const val MAX_RETRY_MILLIS = 30_000L
        private const val CLOSE_TIMEOUT_MILLIS = 15_000L
    }
}
