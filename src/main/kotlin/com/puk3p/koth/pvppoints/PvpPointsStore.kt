package com.puk3p.koth.pvppoints

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer

data class PvpPointsEntry(
    val uuid: UUID,
    val name: String,
    val points: Int,
)

/**
 * Every player's pvp-points, in memory: one server owns them, so this is the truth and answers
 * instantly (placeholders, /pvppoints take). Each change is passed to [storage] (YAML or MySQL)
 * with its reason, which makes it durable.
 * Reads are safe from other threads (PlaceholderAPI); changes happen on the main thread.
 */
class PvpPointsStore(private val storage: PvpPointsStorage) {
    private val players = ConcurrentHashMap<UUID, PvpPointsEntry>()

    @Volatile
    private var ranking: Ranking? = null

    init {
        storage.loadAll().forEach { players[it.uuid] = it }
    }

    val keepsHistory: Boolean get() = storage.keepsHistory

    /** Adds entries already saved by the storage (an import), without saving them again. */
    fun restore(entries: Collection<PvpPointsEntry>) {
        entries.forEach { players[it.uuid] = it }
        ranking = null
    }

    fun points(uuid: UUID): Int = players[uuid]?.points ?: 0

    fun entry(uuid: UUID): PvpPointsEntry? = players[uuid]

    /** A known player by name, ignoring case. */
    fun findByName(name: String): PvpPointsEntry? = players.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun trackedCount(): Int = players.size

    /**
     * Records a player on join: new players start with [startingPoints]; known players get their
     * current name. @return true if the player was new
     */
    fun track(
        uuid: UUID,
        name: String,
        startingPoints: Int,
    ): Boolean {
        val existing = players[uuid]
        if (existing != null) {
            if (existing.name != name) apply(existing.copy(name = name), PointsReason.NAME, null)
            return false
        }
        val start = startingPoints.coerceAtLeast(0)
        apply(PvpPointsEntry(uuid, name, start), if (start > 0) PointsReason.START else PointsReason.JOIN, null)
        return true
    }

    /** Adds [amount] (negative to remove), never going below 0 or past Int.MAX_VALUE. @return the new balance */
    fun add(
        uuid: UUID,
        name: String,
        amount: Int,
        reason: PointsReason,
        detail: String? = null,
    ): Int {
        // Long arithmetic: a huge amount must cap at Int.MAX_VALUE, not wrap around
        val value = (points(uuid).toLong() + amount).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        apply(PvpPointsEntry(uuid, name, value), reason, detail)
        return value
    }

    /** Takes [amount] only if the player has that many. @return the new balance, or null if they have too few */
    fun take(
        uuid: UUID,
        name: String,
        amount: Int,
        detail: String? = null,
    ): Int? {
        if (points(uuid) < amount) return null
        return add(uuid, name, -amount, PointsReason.TAKE, detail)
    }

    fun set(
        uuid: UUID,
        name: String,
        amount: Int,
        detail: String? = null,
    ): Int {
        val value = amount.coerceAtLeast(0)
        apply(PvpPointsEntry(uuid, name, value), PointsReason.SET, detail)
        return value
    }

    /** Players with points, most first; ties by name, so the order is stable. */
    fun top(limit: Int): List<PvpPointsEntry> {
        val sorted = ranking().sorted
        return if (limit >= sorted.size) sorted else sorted.subList(0, limit.coerceAtLeast(0))
    }

    /** Position in [top] (1 = most points), or null for a player with no points. */
    fun rank(uuid: UUID): Int? = ranking().positions[uuid]

    /** Periodic save (YAML: rewrites the file if anything changed; [runWrite] does the disk work). */
    fun flush(runWrite: Executor) = storage.flush({ players.values.toList() }, runWrite)

    /** On disable: writes everything left, blocking. */
    fun close() = storage.close { players.values.toList() }

    /** The player's latest changes, newest first (MySQL only), given to [callback] on [callbackThread]. */
    fun history(
        uuid: UUID,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    ) = storage.history(uuid, limit, callbackThread, callback)

    private fun apply(
        entry: PvpPointsEntry,
        reason: PointsReason,
        detail: String?,
    ) {
        val old = players[entry.uuid]
        val delta = entry.points - (old?.points ?: 0)
        // Nothing changed (a give at the cap, a set to the same value): nothing to store
        if (old != null && delta == 0 && old.name == entry.name) return

        players[entry.uuid] = entry
        ranking = null
        storage.record(PointsChange(entry.uuid, entry.name, delta, entry.points, reason, detail, System.currentTimeMillis()))
    }

    private fun ranking(): Ranking {
        ranking?.let { return it }
        // Placeholders ask for ranks many times a second; the ranking only changes with points, so sort once per change
        val sorted =
            players.values
                .filter { it.points > 0 }
                .sortedWith(compareByDescending<PvpPointsEntry> { it.points }.thenBy { it.name.lowercase(Locale.ROOT) })
        val positions = HashMap<UUID, Int>(sorted.size * 2)
        sorted.forEachIndexed { index, entry -> positions[entry.uuid] = index + 1 }
        return Ranking(sorted, positions).also { ranking = it }
    }

    private class Ranking(val sorted: List<PvpPointsEntry>, val positions: Map<UUID, Int>)
}
