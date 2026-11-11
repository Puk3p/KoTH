package com.puk3p.koth.hooks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.ArenaState
import com.puk3p.koth.kotharena.KothArenaManager
import com.puk3p.koth.util.formatDuration
import com.puk3p.koth.util.formatTime
import me.clip.placeholderapi.PlaceholderAPIPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * %koth_<placeholder>% for PlaceholderAPI. Only instantiate when PlaceholderAPI is installed.
 *
 * Placeholders may be requested off the main thread (scoreboards, async chat), so this only
 * does single reads of plugin state and never iterates live collections.
 *
 * | Placeholder | Value |
 * |---|---|
 * | active, active_type | Arena running or used by a KOTH Arena event, and its type |
 * | state | RUNNING, CONTESTED, JOIN or POST_CAPTURE |
 * | time_left, time_left_seconds | Capture time left (mm:ss / seconds) |
 * | capper, capper_faction | Player credited with the capture, and their faction |
 * | players_in_zone, contested, in_zone | Zone counts; in_zone is for the viewing player |
 * | next, next_time, next_in | Next scheduled arena, when ("Mon 18:00"), and how long until ("2h 15m") |
 * | join_open, join_time_left, participants, joined | KOTH Arena: join phase, players in the arena |
 * | status, capture_time, capturer, capturer_faction | Older names: "WAITING", "00:00", "None" when idle |
 */
class PlaceholderAPIHook(private val plugin: KoTHPlugin) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "koth"

    override fun getAuthor(): String = plugin.description.authors.joinToString()

    override fun getVersion(): String = plugin.description.version

    // Stay registered across /papi reload; KoTHPlugin unregisters on disable.
    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val arenas = plugin.arenaManager
        val kothArena = plugin.kothArena
        val active = arenas.getActiveArena()
        val busy = active ?: kothArena.arena
        val none = plugin.configManager.messages.raw("placeholders.none")

        return when (params.lowercase()) {
            "active" -> busy?.name ?: none
            "active_type" -> busy?.type ?: none
            "state" -> when {
                active != null -> active.state.name
                kothArena.phase == KothArenaManager.Phase.JOIN -> "JOIN"
                kothArena.phase == KothArenaManager.Phase.POST_CAPTURE -> "POST_CAPTURE"
                else -> none
            }
            "time_left" -> active?.let { formatTime(it.remainingSeconds) } ?: none
            "time_left_seconds" -> (active?.remainingSeconds ?: 0).toString()
            "capper" -> active?.capturingPlayerName ?: none
            "capper_faction" -> {
                val capper = active?.capturingPlayer?.let { Bukkit.getPlayer(it) }
                when {
                    capper == null -> none
                    else -> plugin.factionsHook?.getFaction(capper) ?: FactionsHook.NO_FACTION
                }
            }
            "players_in_zone" -> (active?.playersInside?.size ?: 0).toString()
            "contested" -> bool(active?.state == ArenaState.CONTESTED)
            "in_zone" -> bool(player != null && active?.playersInside?.contains(player.uniqueId) == true)

            "next" -> plugin.kothScheduler.nextStart()?.first?.name ?: none
            "next_time" -> plugin.kothScheduler.nextStart()?.second?.format(NEXT_TIME_FORMAT) ?: none
            "next_in" -> plugin.kothScheduler.nextStart()?.let { (_, at) ->
                formatDuration(Duration.between(ZonedDateTime.now(at.zone), at).seconds)
            } ?: none

            "join_open" -> bool(kothArena.isJoinOpen)
            "join_time_left" -> if (kothArena.isJoinOpen) formatTime(kothArena.secondsLeft) else none
            "participants" -> kothArena.participantCount().toString()
            "joined" -> bool(player != null && kothArena.isParticipant(player.uniqueId))

            // Older names, kept with their original values so existing scoreboards keep working
            "status" -> active?.state?.name ?: "WAITING"
            "capture_time" -> formatTime(active?.remainingSeconds ?: 0)
            "capturer" -> if (active == null) "None" else active.capturingPlayerName ?: "None"
            "capturer_faction" -> {
                val capperId = active?.capturingPlayer
                val capper = capperId?.let { Bukkit.getPlayer(it) }
                when {
                    capperId == null -> "None"
                    capper == null -> FactionsHook.NO_FACTION
                    else -> plugin.factionsHook?.getFaction(capper) ?: FactionsHook.NO_FACTION
                }
            }

            else -> null // unknown placeholder: PlaceholderAPI leaves it as typed
        }
    }

    private fun bool(value: Boolean): String {
        return if (value) PlaceholderAPIPlugin.booleanTrue() else PlaceholderAPIPlugin.booleanFalse()
    }

    companion object {
        private val NEXT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    }
}
