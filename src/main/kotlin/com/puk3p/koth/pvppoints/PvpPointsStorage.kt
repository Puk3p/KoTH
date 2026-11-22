package com.puk3p.koth.pvppoints

import java.util.concurrent.Executor
import java.util.function.Consumer

/** Why a faction's points changed; stored in the MySQL history (`<prefix>pvp_faction_points_log.reason`). */
enum class PointsReason(val logged: Boolean) {
    /** The faction changed its tag: only the stored tag is updated, no history line. */
    NAME(false),
    KILL(true),
    DEATH(true),
    CAPTURE(true),

    /** /pvppoints add */
    ADD(true),
    TAKE(true),
    SET(true),

    /** /pvppoints reset confirm (end of a wipe). */
    RESET(true),

    /** The faction disbanded (pvp-points.delete-on-faction-disband). */
    DISBAND(true),

    /** Copied from the YAML file when switching to MySQL. */
    IMPORT(true),
}

/** One change to a faction's points: [balance] is the new total, [delta] how much it moved. */
data class PointsChange(
    val factionId: String,
    val tag: String,
    val delta: Int,
    val balance: Int,
    val reason: PointsReason,
    /** The member it went through: the killer, the victim, the capturer, the player named in /pvppoints add. */
    val player: String?,
    /** The victim or killer, the arena, or who ran the command. */
    val detail: String?,
    val time: Long,
)

/** A line of a faction's history (/pvppoints history). */
data class HistoryEntry(
    val delta: Int,
    val balance: Int,
    val reason: String,
    val player: String?,
    val detail: String?,
    val time: Long,
)

/**
 * Where faction points are kept. [PvpPointsStore] holds every faction's points in memory and
 * reports each change here; implementations make them durable.
 */
interface PvpPointsStorage {
    /** Everything stored, read once at start. Throws if it can't be read, so nothing runs on missing data. */
    fun loadAll(): List<FactionPointsEntry>

    /** Called on the main thread right after each change. */
    fun record(change: PointsChange)

    /** Called every save-interval on the main thread; [runWrite] runs slow disk work off it. */
    fun flush(
        snapshot: () -> Collection<FactionPointsEntry>,
        runWrite: Executor,
    )

    /** On disable: write whatever is left, blocking. */
    fun close(snapshot: () -> Collection<FactionPointsEntry>)

    /** Whether [history] has anything to show. */
    val keepsHistory: Boolean

    /** The faction's latest changes, newest first, given to [callback] on [callbackThread] (null on error). */
    fun history(
        factionId: String,
        limit: Int,
        callbackThread: Executor,
        callback: Consumer<List<HistoryEntry>?>,
    )
}
