package com.puk3p.koth.scheduler

import org.bukkit.configuration.ConfigurationSection
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.logging.Logger

/** One weekly start slot, in the timezone from `settings.timezone`. */
data class ScheduleEntry(val day: DayOfWeek, val time: LocalTime) {
    companion object {
        val ORDER: Comparator<ScheduleEntry> = compareBy({ it.day }, { it.time })
    }
}

object ScheduleParser {
    const val DAILY = "daily"

    private val TIME_PATTERN = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val EVERY_DAY_KEYS = setOf(DAILY, "everyday", "every-day", "zilnic")

    // English names and 3-letter abbreviations, plus Romanian names with and without diacritics
    private val DAY_NAMES: Map<String, DayOfWeek> =
        buildMap {
            for (day in DayOfWeek.values()) {
                val name = day.name.lowercase()
                put(name, day)
                put(name.take(3), day)
            }
            put("luni", DayOfWeek.MONDAY)
            put("marti", DayOfWeek.TUESDAY)
            put("marți", DayOfWeek.TUESDAY)
            put("marţi", DayOfWeek.TUESDAY)
            put("miercuri", DayOfWeek.WEDNESDAY)
            put("joi", DayOfWeek.THURSDAY)
            put("vineri", DayOfWeek.FRIDAY)
            put("sambata", DayOfWeek.SATURDAY)
            put("sâmbătă", DayOfWeek.SATURDAY)
            put("duminica", DayOfWeek.SUNDAY)
            put("duminică", DayOfWeek.SUNDAY)
        }

    /**
     * Parses an arena's `schedule` section:
     * ```
     * schedule:
     *   monday: ["18:00", "22:00"]
     *   daily: "12:00"            # every day; a single time works too
     *   sat,sun: ["15:00"]        # several days
     * ```
     * See [parseDays] for the day keys. Invalid days or times are skipped with a warning.
     */
    fun parse(
        section: ConfigurationSection?,
        arenaName: String,
        logger: Logger,
    ): List<ScheduleEntry> {
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
        return entries.distinct().sortedWith(ScheduleEntry.ORDER)
    }

    /**
     * Days from a schedule key or command argument: English day names or their first three letters,
     * Romanian day names, `daily`, or several separated by commas (`mon,fri`). Any case.
     * Null if any part is unknown.
     */
    fun parseDays(key: String): List<DayOfWeek>? {
        val days = sortedSetOf<DayOfWeek>()
        for (part in key.lowercase(Locale.ROOT).split(',').map { it.trim() }) {
            when {
                part.isEmpty() -> continue
                part in EVERY_DAY_KEYS -> days.addAll(DayOfWeek.values())
                else -> days.add(DAY_NAMES[part] ?: return null)
            }
        }
        return days.toList().ifEmpty { null }
    }

    fun parseTime(value: Any?): LocalTime? {
        return when (value) {
            // YAML 1.1 reads an unquoted 18:00 as the base-60 integer 1080 (18 * 60 + 0)
            is Int, is Long -> {
                val minutes = (value as Number).toInt()
                if (minutes in 0 until 24 * 60) LocalTime.of(minutes / 60, minutes % 60) else null
            }
            is String ->
                TIME_PATTERN.matchEntire(value.trim())?.let {
                    LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt())
                }
            else -> null
        }
    }

    fun formatTime(time: LocalTime): String = TIME_FORMAT.format(time)

    /**
     * The `schedule` section for these entries: times set on every day go under `daily`,
     * the rest under their day (`monday: ["18:00", "22:00"]`). Keys in week order.
     */
    fun toConfig(entries: Collection<ScheduleEntry>): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        val daily = dailyTimes(entries)
        if (daily.isNotEmpty()) result[DAILY] = daily.map { formatTime(it) }
        for ((day, times) in timesByDay(entries, daily)) {
            result[day.name.lowercase()] = times.map { formatTime(it) }
        }
        return result
    }

    /** For chat: "daily 12:00 · Mon 18:00, 22:00 · Fri 21:00". */
    fun describe(entries: Collection<ScheduleEntry>): String {
        val daily = dailyTimes(entries)
        val parts = mutableListOf<String>()
        if (daily.isNotEmpty()) parts += "$DAILY " + daily.joinToString(", ") { formatTime(it) }
        for ((day, times) in timesByDay(entries, daily)) {
            parts += day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + times.joinToString(", ") { formatTime(it) }
        }
        return parts.joinToString(" · ")
    }

    /** Times scheduled on all seven days. */
    private fun dailyTimes(entries: Collection<ScheduleEntry>): List<LocalTime> {
        return entries.groupBy { it.time }
            .filterValues { slots -> slots.map { it.day }.toSet().size == DayOfWeek.values().size }
            .keys
            .sorted()
    }

    private fun timesByDay(
        entries: Collection<ScheduleEntry>,
        except: Collection<LocalTime>,
    ): List<Pair<DayOfWeek, List<LocalTime>>> {
        return DayOfWeek.values().mapNotNull { day ->
            val times = entries.filter { it.day == day && it.time !in except }.map { it.time }.distinct().sorted()
            if (times.isEmpty()) null else day to times
        }
    }
}
