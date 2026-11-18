package com.puk3p.koth.pvppoints

import java.util.UUID
import java.util.concurrent.Executor
import java.util.function.Consumer

/** Why points changed; stored in the MySQL history (`<prefix>pvp_points_log.reason`). */
enum class PointsReason(val logged: Boolean) {
    /** First join with 0 starting points: the player is recorded, no history line. */
    JOIN(false),

    /** First join with starting points. */
    START(true),

    /** A known player joined with a new name: only the name is updated. */
    NAME(false),
    KILL(true),
    DEATH(true),
    CAPTURE(true),
    GIVE(true),
    TAKE(true),
    SET(true),

    /** Copied from pvp-points.yml when switching to MySQL. */
    IMPORT(true),
}

/** One change to a player's points: [balance] is the new total, [delta] how much it moved. */
data class PointsChange(
    val uuid: UUID,
    val name: String,
    val delta: Int,
    val balance: Int,
    val reason: PointsReason,
    val detail: String?,
    val time: Long,
)

/** A line of a player's history (/pvppoints history). */
data class HistoryEntry(
    val delta: Int,
    val balance: Int,
    val reason: String,
    val detail: String?,
    val time: Long,
)

/**
 * Where pvp-points are kept. [PvpPointsStore] holds every player's points in memory and reports
 * each change here; implementations make them durable.
 */
interface PvpPointsStorage {
    /** Everything stored, read once at start. Throws if it can't be read, so nothing runs on missing data. */
    fun loadAll(): List<PvpPointsEntry>

    /** Called on the main thread right after each change. */
    fun record(change: PointsChange)

    /** Called every save-interval on the main thread; [runWrite] runs slow disk work off it. */
    fun flush(
        snapshot: () -> Collection<PvpPointsEntry>,
        runWrite: Executor,
    )

    /** On disable: write whatever is left, blocking. */
    fun close(snapshot: () -> Collection<PvpPointsEntry>)

    /** Whether [history] has anything to show. */
    val keepsHistory: Boolean

    /** The player's latest changes, newest first, given to [callback] on [callbackThread] (null on error). */
    fun history(
        uuid: UUID,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    )
}
