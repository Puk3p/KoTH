package com.puk3p.koth.scheduler

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.event.KothStartEvent
import com.puk3p.koth.tasks.TournamentTask
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitRunnable
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.logging.Level

class KothScheduler(private val plugin: KoTHPlugin) {

    // Map of ArenaName -> List of Pair<DayOfWeek, String(HH:mm)>
    private val schedules = mutableMapOf<String, MutableList<Pair<DayOfWeek, String>>>()
    private var timezone: ZoneId = ZoneId.of("UTC")
    private var lastTriggeredMinute = -1

    fun loadSchedules() {
        schedules.clear()

        // 1. Setup Timezone
        val tzString = plugin.configManager.config.getString("settings.timezone", "UTC")
        timezone = try {
            ZoneId.of(tzString)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Invalid timezone: $tzString. Defaulting to UTC.")
            ZoneId.of("UTC")
        }

        // 2. Parse Arenas Config
        val config = plugin.configManager.arenasConfig
        val section = config.getConfigurationSection("arenas") ?: return

        for (arenaName in section.getKeys(false)) {
            val scheduleSection = config.getConfigurationSection("arenas.$arenaName.schedule") ?: continue
            val arenaSchedules = mutableListOf<Pair<DayOfWeek, String>>()

            for (dayString in scheduleSection.getKeys(false)) {
                val dayOfWeek = parseDayOfWeek(dayString)
                if (dayOfWeek == null) {
                    plugin.logger.warning("Invalid day of week in schedule for $arenaName: $dayString")
                    continue
                }

                val times = scheduleSection.getStringList(dayString)
                for (time in times) {
                    // Regex ensures format is exactly HH:mm (e.g., 18:00, 09:30)
                    if (time.matches(Regex("^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$"))) {
                        arenaSchedules.add(Pair(dayOfWeek, time))
                    } else {
                        plugin.logger.warning("Invalid time format in schedule for $arenaName: $time. Use HH:mm")
                    }
                }
            }
            if (arenaSchedules.isNotEmpty()) {
                schedules[arenaName] = arenaSchedules
            }
        }
        plugin.logger.info("Loaded schedules for ${schedules.size} arenas utilizing timezone $timezone.")
    }

    fun startTask() {
        // Runs every 20 ticks (1 second) to check the time
        object : BukkitRunnable() {
            override fun run() {
                tick()
            }
        }.runTaskTimer(plugin, 20L, 20L)
    }

    private fun tick() {
        val now = ZonedDateTime.now(timezone)
        val currentMinute = now.minute

        // Ensure we only trigger once per actual real-world minute
        if (currentMinute == lastTriggeredMinute) return
        lastTriggeredMinute = currentMinute

        val currentDay = now.dayOfWeek
        val currentTimeString = String.format("%02d:%02d", now.hour, now.minute)

        for ((arenaName, arenaSchedules) in schedules) {
            for (schedule in arenaSchedules) {
                if (schedule.first == currentDay && schedule.second == currentTimeString) {
                    triggerArenaStart(arenaName)
                }
            }
        }
    }

    private fun triggerArenaStart(arenaName: String) {
        if (plugin.arenaManager.getActiveArena() != null) {
            plugin.logger.info("Scheduler skipped starting $arenaName because another KoTH is active.")
            return
        }

        val arena = plugin.arenaManager.getArena(arenaName)
        if (arena == null || !arena.isEnabled) return

        // NEW: Minimum Player Check
        val minPlayers = plugin.configManager.config.getInt("types.${arena.type}.minimum-players-online", 0)
        if (Bukkit.getOnlinePlayers().size < minPlayers) {
            plugin.logger.info("Scheduler skipped $arenaName. Required: $minPlayers, Online: ${Bukkit.getOnlinePlayers().size}")
            // Optional: Broadcast a message that it was skipped due to low playercount
            return
        }

        val defaultTime = plugin.configManager.config.getInt("types.${arena.type}.capture-time", 300)
        arena.resetCapture(defaultTime)

        val event = KothStartEvent(arena)
        Bukkit.getPluginManager().callEvent(event)

        if (!event.isCancelled) {
            val isTournament = plugin.configManager.config.getBoolean("types.${arena.type}.tournament-mode.enabled", false)
            if (isTournament) {
                TournamentTask(plugin, arena).runTaskTimer(plugin, 0L, 20L)
            } else {
                val msg = plugin.configManager.messages.get("events.started", "%arena%" to arena.name, "%type%" to arena.type)
                Bukkit.broadcastMessage(msg)
            }
            plugin.discordService.sendKothStarted(arena)
        }
    }

    private fun parseDayOfWeek(day: String): DayOfWeek? {
        return try {
            DayOfWeek.valueOf(day.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}