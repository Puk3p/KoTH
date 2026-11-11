package com.puk3p.koth.tasks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.ArenaState
import com.puk3p.koth.event.KothCaptureEvent
import com.puk3p.koth.hooks.FactionsHook
import com.puk3p.koth.util.formatTime
import com.puk3p.koth.util.shouldAnnounce
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable

/**
 * Runs once per second on the main thread and advances the active KoTH.
 */
class CaptureTickTask(private val plugin: KoTHPlugin) : BukkitRunnable() {

    override fun run() {
        // getActiveArena() only returns RUNNING or CONTESTED arenas
        val activeArena = plugin.arenaManager.getActiveArena() ?: return
        val playersInside = activeArena.playersInside

        // Stale entries; quitting players are normally removed by PlayerListener
        playersInside.removeIf { Bukkit.getPlayer(it) == null }

        // KOTH Arena: only players still in the event count (remove any that snuck in)
        val kothArena = plugin.kothArena
        if (kothArena.isEventArena(activeArena)) {
            playersInside.removeIf { !kothArena.isParticipant(it) }
        }

        val config = plugin.configManager.config

        // max-duration: a KoTH nobody captures would otherwise block every later scheduled start
        activeArena.elapsedSeconds++
        val maxDuration = config.getInt("types.${activeArena.type}.max-duration", 0)
        if (maxDuration > 0 && activeArena.elapsedSeconds >= maxDuration) {
            Bukkit.broadcastMessage(plugin.configManager.messages.get("events.timed-out", "%arena%" to activeArena.name))
            plugin.arenaManager.stopArena(activeArena, broadcast = false) // a KOTH Arena event ends with it
            return
        }

        // playersInside keeps insertion order and groupBy preserves it,
        // so each team's first member is the one who entered first.
        val factionCapture = config.getBoolean("settings.faction-capture", true)
        val ignoreCreative = config.getBoolean("settings.ignore-creative", true)
        val inside = synchronized(playersInside) { playersInside.toList() }
        val teams = inside
            .mapNotNull { Bukkit.getPlayer(it) }
            .filter { countsInZone(it, ignoreCreative) }
            .groupBy { teamOf(it, factionCapture) }

        when (teams.size) {
            0 -> handleEmptyZone(activeArena)
            1 -> {
                val (team, members) = teams.entries.first()
                handleCapturing(activeArena, team, members)
            }
            else -> handleContested(activeArena)
        }
    }

    /**
     * Whether a player standing in the zone can capture or contest. Spectators, vanished staff
     * (the "vanished" metadata set by Essentials, SuperVanish, ...) and, unless
     * `settings.ignore-creative` is false, creative players are in the zone but don't count:
     * they can't be killed, so they'd win or block every KoTH. Checked every tick because these
     * can change while the player stands still.
     */
    private fun countsInZone(player: Player, ignoreCreative: Boolean): Boolean {
        if (player.isDead) return false
        when (player.gameMode) {
            GameMode.SPECTATOR -> return false
            GameMode.CREATIVE -> if (ignoreCreative) return false
            else -> Unit
        }
        return player.getMetadata("vanished").none { it.asBoolean() }
    }

    /**
     * Team key used to decide who contests whom: the player's faction when faction capture is on
     * and they have one, otherwise the player alone.
     */
    private fun teamOf(player: Player, factionCapture: Boolean): String {
        if (factionCapture) {
            val factionId = plugin.factionsHook?.getFactionId(player)
            if (!factionId.isNullOrEmpty()) return "f:$factionId"
        }
        return "p:${player.uniqueId}"
    }

    private fun handleEmptyZone(arena: Arena) {
        loseControl(arena)
        arena.state = ArenaState.RUNNING
    }

    /** Exactly one team is in the zone. [members] is non-empty, in order of entry. */
    private fun handleCapturing(arena: Arena, team: String, members: List<Player>) {
        // A single team in the zone also ends a contest
        arena.state = ArenaState.RUNNING

        val isNewTeam = arena.capturingTeam != team
        if (isNewTeam) {
            // If another team was holding the zone (e.g. they lost a contest), that's a knock-off
            loseControl(arena)
            arena.capturingTeam = team
            credit(arena, members.first())
        }

        // The credited player left but teammates still hold the zone: hand over, no knock-off
        val capper = members.firstOrNull { it.uniqueId == arena.capturingPlayer }
            ?: members.first().also { credit(arena, it) }

        if (isNewTeam) {
            broadcastCapturing(arena, capper)
        }

        if (arena.remainingSeconds > 0) {
            arena.remainingSeconds--
        }
        plugin.arenaManager.playSound(arena, "capture-tick", members)

        if (arena.remainingSeconds <= 0) {
            tryCapture(arena, capper)
            return
        }

        // Progress updates: every full minute and during the final countdown
        if (!isNewTeam && shouldAnnounce(arena.remainingSeconds)) {
            broadcastCapturing(arena, capper)
        }
    }

    private fun tryCapture(arena: Arena, player: Player) {
        val event = KothCaptureEvent(arena, player)
        Bukkit.getPluginManager().callEvent(event)
        // Cancelled: the KoTH keeps running and the capture is retried on the next tick.
        if (event.isCancelled) return

        arena.state = ArenaState.ENDED

        val faction = factionOf(player)
        val kothArena = plugin.kothArena
        val isArenaEvent = kothArena.isEventArena(arena)
        val msg = if (isArenaEvent) {
            kothArena.prefixed("captured", "%player%" to player.name, "%faction%" to faction)
        } else {
            plugin.configManager.messages.get(
                "events.captured",
                "%player%" to player.name,
                "%faction%" to faction,
                "%arena%" to arena.name
            )
        }
        Bukkit.broadcastMessage(msg)
        plugin.discordService.kothCaptured(arena, player.name, faction)

        // Give Rewards
        plugin.rewardService.giveRewards(arena, player)

        // KOTH Arena: start the post-capture countdown (before the arena goes idle below)
        if (isArenaEvent) {
            kothArena.onCaptured(arena, player.name)
        }

        // End event: play the captured sound and return the arena to WAITING
        plugin.arenaManager.finishArena(arena)
    }

    /** Players from more than one team are in the zone. */
    private fun handleContested(arena: Arena) {
        if (arena.state != ArenaState.CONTESTED) {
            arena.state = ArenaState.CONTESTED
            val msg = plugin.configManager.messages.get("events.contested", "%arena%" to arena.name)
            Bukkit.broadcastMessage(msg)
        }
        // In a contested state, timer does not decrement.
    }

    /**
     * The team holding the zone (if any) lost it: announce it and apply `knock-behavior`.
     * PAUSE keeps the remaining time for whoever caps next; RESET (default) restores the full time.
     */
    private fun credit(arena: Arena, player: Player) {
        arena.capturingPlayer = player.uniqueId
        arena.capturingPlayerName = player.name
    }

    private fun loseControl(arena: Arena) {
        if (arena.capturingPlayer == null) return
        val playerName = arena.capturingPlayerName ?: "Someone"
        arena.capturingTeam = null
        arena.capturingPlayer = null
        arena.capturingPlayerName = null

        val msg = plugin.configManager.messages.get(
            "events.lost-control",
            "%player%" to playerName,
            "%arena%" to arena.name
        )
        Bukkit.broadcastMessage(msg)

        val behavior = plugin.configManager.config.getString("types.${arena.type}.knock-behavior", "RESET")
        if (!behavior.equals("PAUSE", ignoreCase = true)) {
            arena.remainingSeconds = plugin.arenaManager.captureTime(arena)
        }
    }

    private fun broadcastCapturing(arena: Arena, player: Player) {
        val msg = plugin.configManager.messages.get(
            "events.capturing",
            "%player%" to player.name,
            "%faction%" to factionOf(player),
            "%arena%" to arena.name,
            "%time%" to formatTime(arena.remainingSeconds)
        )
        Bukkit.broadcastMessage(msg)
    }

    private fun factionOf(player: Player): String {
        return plugin.factionsHook?.getFaction(player) ?: FactionsHook.NO_FACTION
    }

}
