package com.puk3p.koth.tasks

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.ArenaState
import com.puk3p.koth.event.KothCaptureEvent
import com.puk3p.koth.hooks.FactionsHook
import com.puk3p.koth.util.ClientDisplay
import com.puk3p.koth.util.formatTime
import com.puk3p.koth.util.shouldAnnounce
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.GameMode
import org.bukkit.entity.Firework
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

        // max-run-time: a KoTH nobody captures would otherwise block every later scheduled start
        // (max-duration is the older name of the same setting)
        activeArena.elapsedSeconds++
        val typePath = "types.${activeArena.type}"
        val maxRunTime = config.getInt("$typePath.max-run-time", config.getInt("$typePath.max-duration", 0))
        if (maxRunTime > 0 && activeArena.elapsedSeconds >= maxRunTime) {
            Bukkit.broadcastMessage(plugin.configManager.messages.get("events.timed-out", "%arena%" to activeArena.name))
            plugin.arenaManager.stopArena(activeArena, broadcast = false) // a KOTH Arena event ends with it
            return
        }

        // playersInside keeps insertion order and groupBy preserves it,
        // so each team's first member is the one who entered first.
        val factionCapture = config.getBoolean("settings.faction-capture", true)
        val ignoreCreative = config.getBoolean("settings.ignore-creative", true)
        val inside = synchronized(playersInside) { playersInside.toList() }
        val teams =
            inside
                .mapNotNull { Bukkit.getPlayer(it) }
                .filter { countsInZone(it, ignoreCreative) }
                .groupBy { teamOf(it, factionCapture) }

        when (teams.size) {
            0 -> handleEmptyZone(activeArena)
            1 -> {
                val (team, members) = teams.entries.first()
                handleCapturing(activeArena, team, members)
            }
            else -> handleContested(activeArena, teams.values.flatten())
        }
    }

    /**
     * Whether a player standing in the zone can capture or contest. Spectators, vanished staff
     * (the "vanished" metadata set by Essentials, SuperVanish, ...) and, unless
     * `settings.ignore-creative` is false, creative players are in the zone but don't count:
     * they can't be killed, so they'd win or block every KoTH. Checked every tick because these
     * can change while the player stands still.
     */
    private fun countsInZone(
        player: Player,
        ignoreCreative: Boolean,
    ): Boolean {
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
    private fun teamOf(
        player: Player,
        factionCapture: Boolean,
    ): String {
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
    private fun handleCapturing(
        arena: Arena,
        team: String,
        members: List<Player>,
    ) {
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
        val capper =
            members.firstOrNull { it.uniqueId == arena.capturingPlayer }
                ?: members.first().also { credit(arena, it) }

        if (isNewTeam) {
            broadcastCapturing(arena, capper)
        }

        if (arena.remainingSeconds > 0) {
            arena.remainingSeconds--
        }
        plugin.arenaManager.playSound(arena, "capturing-tick", members)
        actionBar(arena, members, "text", DEFAULT_CAPTURING_BAR)

        if (arena.remainingSeconds <= 0) {
            tryCapture(arena, capper)
            return
        }

        // Progress updates: every full minute and during the final countdown
        if (!isNewTeam && shouldAnnounce(arena.remainingSeconds)) {
            broadcastCapturing(arena, capper)
        }
    }

    private fun tryCapture(
        arena: Arena,
        player: Player,
    ) {
        val event = KothCaptureEvent(arena, player)
        Bukkit.getPluginManager().callEvent(event)
        // Cancelled: the KoTH keeps running and the capture is retried on the next tick.
        if (event.isCancelled) return

        arena.state = ArenaState.ENDED

        val faction = factionOf(player)
        val kothArena = plugin.kothArena
        val isArenaEvent = kothArena.isEventArena(arena)
        val msg =
            if (isArenaEvent) {
                kothArena.prefixed("captured", "%player%" to player.name, "%faction%" to faction)
            } else {
                plugin.configManager.messages.get(
                    "events.captured",
                    "%player%" to player.name,
                    "%faction%" to faction,
                    "%arena%" to arena.name,
                )
            }
        Bukkit.broadcastMessage(msg)
        plugin.arenaManager.showTitle(
            arena,
            "title-on-capture",
            "%player%" to player.name,
            "%faction%" to faction,
            "%arena%" to arena.name,
        )
        if (plugin.configManager.config.getBoolean("types.${arena.type}.visuals.firework-on-capture", true)) {
            spawnVictoryFirework(player)
        }
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
    private fun handleContested(
        arena: Arena,
        playersInZone: List<Player>,
    ) {
        if (arena.state != ArenaState.CONTESTED) {
            arena.state = ArenaState.CONTESTED
            val msg = plugin.configManager.messages.get("events.contested", "%arena%" to arena.name)
            Bukkit.broadcastMessage(msg)
            plugin.arenaManager.playSound(arena, "contested", playersInZone)
        }
        // In a contested state, timer does not decrement. Tell the players in the zone it's paused.
        actionBar(arena, playersInZone, "contested-text", DEFAULT_CONTESTED_BAR)
    }

    /** `types.<type>.visuals.action-bar.<key>` to [players], if the action bar is enabled. */
    private fun actionBar(
        arena: Arena,
        players: List<Player>,
        key: String,
        default: String,
    ) {
        val config = plugin.configManager.config
        val path = "types.${arena.type}.visuals.action-bar"
        if (!config.getBoolean("$path.enabled", true)) return
        val text =
            ChatColor.translateAlternateColorCodes('&', config.getString("$path.$key", default))
                .replace("%arena%", arena.name)
                .replace("%time%", formatTime(arena.remainingSeconds))
        for (player in players) {
            ClientDisplay.actionBar(player, text, plugin.logger)
        }
    }

    private fun spawnVictoryFirework(player: Player) {
        val firework = player.world.spawn(player.location, Firework::class.java)
        val meta = firework.fireworkMeta
        meta.addEffect(
            FireworkEffect.builder()
                .withColor(Color.ORANGE, Color.YELLOW)
                .withFade(Color.RED)
                .with(FireworkEffect.Type.BALL_LARGE)
                .trail(true)
                .flicker(true)
                .build(),
        )
        meta.power = 1
        firework.fireworkMeta = meta
    }

    /**
     * The team holding the zone (if any) lost it: announce it and apply `knock-behavior`.
     * PAUSE keeps the remaining time for whoever caps next; RESET (default) restores the full time.
     */
    private fun credit(
        arena: Arena,
        player: Player,
    ) {
        arena.capturingPlayer = player.uniqueId
        arena.capturingPlayerName = player.name
    }

    private fun loseControl(arena: Arena) {
        if (arena.capturingPlayer == null) return
        val playerName = arena.capturingPlayerName ?: "Someone"
        arena.capturingTeam = null
        arena.capturingPlayer = null
        arena.capturingPlayerName = null

        val msg =
            plugin.configManager.messages.get(
                "events.lost-control",
                "%player%" to playerName,
                "%arena%" to arena.name,
            )
        Bukkit.broadcastMessage(msg)

        val behavior = plugin.configManager.config.getString("types.${arena.type}.knock-behavior", "RESET")
        if (!behavior.equals("PAUSE", ignoreCase = true)) {
            arena.remainingSeconds = plugin.arenaManager.captureTime(arena)
        }
    }

    private fun broadcastCapturing(
        arena: Arena,
        player: Player,
    ) {
        val msg =
            plugin.configManager.messages.get(
                "events.capturing",
                "%player%" to player.name,
                "%faction%" to factionOf(player),
                "%arena%" to arena.name,
                "%time%" to formatTime(arena.remainingSeconds),
            )
        Bukkit.broadcastMessage(msg)
    }

    private fun factionOf(player: Player): String {
        return plugin.factionsHook?.getFaction(player) ?: FactionsHook.NO_FACTION
    }

    companion object {
        private const val DEFAULT_CAPTURING_BAR = "&b&lCapturing %arena% &8» &f%time%"
        private const val DEFAULT_CONTESTED_BAR = "&c&lCONTESTED! &7Clear the zone!"
    }
}
