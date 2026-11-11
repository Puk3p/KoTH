package com.puk3p.koth.scheduler

import org.bukkit.configuration.ConfigurationSection
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.logging.Logger

/** One weekly start slot, in the timezone from `settings.timezone`. */
data class ScheduleEntry(val day: DayOfWeek, val time: LocalTime)

object ScheduleParser {

    private val TIME_PATTERN = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val EVERY_DAY_KEYS = setOf("daily", "everyday", "every-day")

    /**
     * Parses an arena's `schedule` section:
     * ```
     * schedule:
     *   monday: ["18:00", "22:00"]
     *   daily: "12:00"            # every day; a single time works too
     * ```
     * Day keys are English day names (any case) or `daily`. Invalid days or times are skipped with a warning.
     */
    fun parse(section: ConfigurationSection?, arenaName: String, logger: Logger): List<ScheduleEntry> {
        if (section == null) return emptyList()

        val entries = mutableListOf<ScheduleEntry>()
        for (key in section.getKeys(false)) {
            val days = parseDays(key)
            if (days == null) {
                logger.warning("Arena '$arenaName': unknown schedule day '$key' (use monday..sunday or daily).")
                continue
            }

            val values: List<Any?> = if (section.isList(key)) section.getList(key) else listOf(section.get(key))
            for (value in values) {
                val time = parseTime(value)
                if (time == null) {
                    logger.warning("Arena '$arenaName': invalid schedule time '$value' under '$key' (use \"HH:mm\").")
                    continue
                }
                days.forEach { entries.add(ScheduleEntry(it, time)) }
            }
        }
        return entries.distinct().sortedWith(compareBy({ it.day }, { it.time }))
    }

    fun parseDays(key: String): List<DayOfWeek>? {
        if (key.lowercase() in EVERY_DAY_KEYS) return DayOfWeek.values().toList()
        return DayOfWeek.values().firstOrNull { it.name.equals(key, ignoreCase = true) }?.let { listOf(it) }
    }

    fun parseTime(value: Any?): LocalTime? {
        return when (value) {
            // YAML 1.1 reads an unquoted 18:00 as the base-60 integer 1080 (18 * 60 + 0)
            is Int, is Long -> {
                val minutes = (value as Number).toInt()
                if (minutes in 0 until 24 * 60) LocalTime.of(minutes / 60, minutes % 60) else null
            }
            is String -> TIME_PATTERN.matchEntire(value.trim())?.let {
                LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())
            }
            else -> null
        }
    }
}
