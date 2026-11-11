package com.puk3p.koth.scheduler

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.ArenaManager.StartResult
import org.bukkit.scheduler.BukkitRunnable
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Starts arenas at the times listed in their `schedule` (arenas.yml).
 *
 * Runs every second on the main thread and acts once per new minute. Minutes are tracked as
 * epoch minutes, so a timezone change on reload never replays slots, and up to
 * [MAX_CATCH_UP_MINUTES] minutes skipped by a lag spike are still processed.
 * DST follows the configured zone: slots in a skipped hour don't fire, slots in a repeated hour fire twice.
 */
class KothScheduler(private val plugin: KoTHPlugin) : BukkitRunnable() {
    @Volatile
    private var zone: ZoneId = ZoneOffset.UTC

    // Start one minute back so a slot in the minute the server boots still fires.
    private var lastMinute = currentMinute() - 1

    /** Re-reads `settings.timezone`. Call on enable and after /koth reload. */
    fun reload() {
        nextStartCache = null
        val id = plugin.configManager.config.getString("settings.timezone", "UTC")
        zone =
            try {
                ZoneId.of(id)
            } catch (e: DateTimeException) {
                plugin.logger.warning("Invalid settings.timezone '$id', using UTC. Examples: Europe/Bucharest, UTC.")
                ZoneOffset.UTC
            }
    }

    override fun run() {
        val now = currentMinute()
        if (now <= lastMinute) return

        val from = maxOf(lastMinute + 1, now - MAX_CATCH_UP_MINUTES + 1)
        lastMinute = now
        for (minute in from..now) {
            fireSlotsAt(minute)
        }
    }

    private fun fireSlotsAt(epochMinute: Long) {
        // KOTH Arena events open /koth join join-time early, so the KoTH itself starts on the slot
        val kothArena = plugin.kothArena
        val joinLeadMinutes = (kothArena.joinTime + 59) / 60L

        // Sorted so that, when two arenas share a slot, the same one wins every time.
        for (arena in plugin.arenaManager.getArenas().sortedBy { it.name.lowercase() }) {
            val lead = if (kothArena.isEventArena(arena)) joinLeadMinutes else 0L
            val time = Instant.ofEpochSecond((epochMinute + lead) * 60).atZone(zone)
            if (ScheduleEntry(time.dayOfWeek, time.toLocalTime()) in arena.schedule) {
                startScheduled(arena)
            }
        }
    }

    private fun startScheduled(arena: Arena) {
        if (!arena.isEnabled) {
            plugin.logger.info("Skipped scheduled KoTH '${arena.name}': arena is disabled.")
            return
        }

        // A KOTH Arena join opens a whole number of minutes early. If join-time isn't a multiple
        // of 60 (say 90s -> 2 minutes early), wait the difference so the KoTH still starts on the slot.
        val kothArena = plugin.kothArena
        val waitSeconds = if (kothArena.isEventArena(arena)) (60 - kothArena.joinTime % 60) % 60 else 0
        if (waitSeconds == 0) {
            launchScheduled(arena)
            return
        }
        plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable {
                // /koth reload in the meantime replaces the arena objects
                if (plugin.arenaManager.getArena(arena.name) === arena) {
                    launchScheduled(arena)
                }
            },
            waitSeconds * 20L,
        )
    }

    private fun launchScheduled(arena: Arena) {
        val log = plugin.logger

        // minimum-players-online: a scheduled KoTH on an empty server is skipped (manual starts aren't)
        val minOnline = plugin.configManager.config.getInt("types.${arena.type}.minimum-players-online", 0)
        val online = plugin.server.onlinePlayers.size
        if (online < minOnline) {
            log.info("Skipped scheduled KoTH '${arena.name}': $online player(s) online, $minOnline required.")
            return
        }

        when (plugin.arenaManager.launch(arena)) {
            StartResult.STARTED -> log.info("Started scheduled KoTH '${arena.name}'.")
            StartResult.JOIN_OPENED -> log.info("Opened /koth join for scheduled KOTH Arena '${arena.name}'.")
            StartResult.NO_ARENA_LOCATION ->
                log.warning("Skipped scheduled KOTH Arena '${arena.name}': koth-arena.arena-location is not set or its world isn't loaded.")
            StartResult.ALREADY_RUNNING -> log.info("Scheduled KoTH '${arena.name}' is already running.")
            StartResult.ANOTHER_RUNNING ->
                log.warning("Skipped scheduled KoTH '${arena.name}': '${plugin.arenaManager.busyArena()?.name}' is still running.")
            StartResult.CANCELLED -> log.info("Scheduled KoTH '${arena.name}' was cancelled by another plugin.")
        }
    }

    /**
     * The next enabled arena due to start after now, and when (in the configured timezone).
     * Safe to call from other threads (used by PlaceholderAPI).
     */
    fun nextStart(): Pair<Arena, ZonedDateTime>? {
        // Scoreboards ask for this several times a second per player; recompute at most once a second
        val cached = nextStartCache
        val nowMillis = System.currentTimeMillis()
        if (cached != null && nowMillis - cached.computedAt < NEXT_START_CACHE_MS) return cached.value

        val now = ZonedDateTime.now(zone)
        val next =
            plugin.arenaManager.getArenas()
                .filter { it.isEnabled }
                .flatMap { arena -> arena.schedule.map { arena to nextOccurrence(it, now) } }
                .minByOrNull { it.second }
        nextStartCache = NextStart(next, nowMillis)
        return next
    }

    private class NextStart(val value: Pair<Arena, ZonedDateTime>?, val computedAt: Long)

    @Volatile
    private var nextStartCache: NextStart? = null

    private fun currentMinute(): Long = System.currentTimeMillis() / 60_000

    companion object {
        private const val MAX_CATCH_UP_MINUTES = 5L
        private const val NEXT_START_CACHE_MS = 1000L

        /** First time strictly after [now] that matches [entry], in [now]'s zone. */
        fun nextOccurrence(
            entry: ScheduleEntry,
            now: ZonedDateTime,
        ): ZonedDateTime {
            val candidate = now.with(TemporalAdjusters.nextOrSame(entry.day)).with(entry.time)
            return if (candidate.isAfter(now)) candidate else candidate.plusWeeks(1)
        }
    }
}
