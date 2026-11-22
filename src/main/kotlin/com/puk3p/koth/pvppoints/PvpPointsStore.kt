package com.puk3p.koth.pvppoints

import com.puk3p.koth.hooks.FactionRef
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.function.Consumer

/** A faction's points. Keyed by the Factions id, which survives renames; [tag] is the last tag seen. */
data class FactionPointsEntry(
    val factionId: String,
    val tag: String,
    val points: Int,
)

/**
 * Every faction's pvp-points, in memory: one server owns them, so this is the truth and answers
 * instantly (placeholders, /pvppoints take). Each change is passed to [storage] (YAML or MySQL)
 * with its reason, which makes it durable.
 * Reads are safe from other threads (PlaceholderAPI, /f who); changes happen on the main thread.
 */
class PvpPointsStore(private val storage: PvpPointsStorage) {
    private val factions = ConcurrentHashMap<String, FactionPointsEntry>()

    @Volatile
    private var ranking: Ranking? = null

    init {
        storage.loadAll().forEach { factions[it.factionId] = it }
    }

    val keepsHistory: Boolean get() = storage.keepsHistory

    /** Adds entries already saved by the storage (an import), without saving them again. */
    fun restore(entries: Collection<FactionPointsEntry>) {
        entries.forEach { factions[it.factionId] = it }
        ranking = null
    }

    fun points(factionId: String): Int = factions[factionId]?.points ?: 0

    fun entry(factionId: String): FactionPointsEntry? = factions[factionId]

    fun entries(): List<FactionPointsEntry> = factions.values.toList()

    /** A stored faction by its last known tag, ignoring case (for factions Factions no longer has). */
    fun findByTag(tag: String): FactionPointsEntry? = factions.values.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

    fun trackedCount(): Int = factions.size

    /** Adds [amount] (negative to remove), never going below 0 or past Int.MAX_VALUE. @return the new balance */
    fun add(
        faction: FactionRef,
        amount: Int,
        reason: PointsReason,
        player: String? = null,
        detail: String? = null,
    ): Int {
        // Long arithmetic: a huge amount must cap at Int.MAX_VALUE, not wrap around
        val value = (points(faction.id).toLong() + amount).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        apply(FactionPointsEntry(faction.id, faction.tag, value), reason, player, detail)
        return value
    }

    /** Takes [amount] only if the faction has that many. @return the new balance, or null if it has too few */
    fun take(
        faction: FactionRef,
        amount: Int,
        player: String? = null,
        detail: String? = null,
    ): Int? {
        if (points(faction.id) < amount) return null
        return add(faction, -amount, PointsReason.TAKE, player, detail)
    }

    fun set(
        faction: FactionRef,
        amount: Int,
        reason: PointsReason = PointsReason.SET,
        player: String? = null,
        detail: String? = null,
    ): Int {
        val value = amount.coerceAtLeast(0)
        apply(FactionPointsEntry(faction.id, faction.tag, value), reason, player, detail)
        return value
    }

    /** The faction now has [tag]: updates the stored one (no history line). Does nothing for untracked factions. */
    fun rename(
        factionId: String,
        tag: String,
    ) {
        val existing = factions[factionId] ?: return
        if (existing.tag != tag) apply(existing.copy(tag = tag), PointsReason.NAME, null, null)
    }

    /** Sets every faction to 0 (end of a wipe); each one gets a RESET line in the history. @return the factions reset */
    fun resetAll(detail: String?): Int {
        val withPoints = factions.values.filter { it.points > 0 }
        withPoints.forEach { apply(it.copy(points = 0), PointsReason.RESET, null, detail) }
        return withPoints.size
    }

    /** Factions with points, most first; ties by tag, so the order is stable. */
    fun top(limit: Int): List<FactionPointsEntry> {
        val sorted = ranking().sorted
        return if (limit >= sorted.size) sorted else sorted.subList(0, limit.coerceAtLeast(0))
    }

    /** Position in [top] (1 = most points), or null for a faction with no points. */
    fun rank(factionId: String): Int? = ranking().positions[factionId]

    /** Periodic save (YAML: rewrites the file if anything changed; [runWrite] does the disk work). */
    fun flush(runWrite: Executor) = storage.flush({ factions.values.toList() }, runWrite)

    /** On disable: writes everything left, blocking. */
    fun close() = storage.close { factions.values.toList() }

    /** The faction's latest changes, newest first (MySQL only), given to [callback] on [callbackThread]. */
    fun history(
        factionId: String,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    ) = storage.history(factionId, limit, callbackThread, callback)

    private fun apply(
        entry: FactionPointsEntry,
        reason: PointsReason,
        player: String?,
        detail: String?,
    ) {
        val old = factions[entry.factionId]
        val delta = entry.points - (old?.points ?: 0)
        // Nothing changed (an add at the cap, a set to the same value): nothing to store
        if (delta == 0 && (old == null || old.tag == entry.tag)) return

        factions[entry.factionId] = entry
        ranking = null
        storage.record(
            PointsChange(entry.factionId, entry.tag, delta, entry.points, reason, player, detail, System.currentTimeMillis()),
        )
    }

    private fun ranking(): Ranking {
        ranking?.let { return it }
        // Placeholders ask for ranks many times a second; the ranking only changes with points, so sort once per change
        val sorted =
            factions.values
                .filter { it.points > 0 }
                .sortedWith(compareByDescending<FactionPointsEntry> { it.points }.thenBy { it.tag.lowercase(Locale.ROOT) })
        val positions = HashMap<String, Int>(sorted.size * 2)
        sorted.forEachIndexed { index, entry -> positions[entry.factionId] = index + 1 }
        return Ranking(sorted, positions).also { ranking = it }
    }

    private class Ranking(val sorted: List<FactionPointsEntry>, val positions: Map<String, Int>)
}
