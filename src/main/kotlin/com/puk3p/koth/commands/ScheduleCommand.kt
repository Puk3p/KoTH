package com.puk3p.koth.commands

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.config.Messages
import com.puk3p.koth.scheduler.ScheduleEntry
import com.puk3p.koth.scheduler.ScheduleParser
import com.puk3p.koth.util.formatDuration
import org.bukkit.command.CommandSender
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * /koth schedule: when arenas start, edited in game. Changes are saved to the arena's `schedule`
 * in arenas.yml and used by the scheduler right away (no /koth reload).
 *
 *   /koth schedule [list] [arena]               scheduled arenas and the next start
 *   /koth schedule add <arena> <days> <HH:mm>...  e.g. add nether friday 18:00 22:00
 *   /koth schedule remove <arena> <days> [HH:mm...] without times: everything on those days
 *   /koth schedule clear <arena>
 *
 * <days>: monday..sunday, mon..sun, Romanian names, daily, or several with commas (sat,sun).
 * Times are in settings.timezone.
 */
class ScheduleCommand(private val plugin: KoTHPlugin) {
    private val messages: Messages get() = plugin.configManager.messages
    private val arenas get() = plugin.arenaManager
    private val scheduler get() = plugin.kothScheduler

    /** [args] are the /koth arguments, args[0] being "schedule". */
    fun execute(
        sender: CommandSender,
        label: String,
        args: Array<String>,
    ) {
        when (args.getOrNull(1)?.lowercase()) {
            null, "list" -> list(sender, label, args.getOrNull(2))
            "add" -> add(sender, label, args)
            "remove" -> remove(sender, label, args)
            "clear" -> clear(sender, label, args)
            else -> usage(sender, "/$label schedule <list|add|remove|clear>")
        }
    }

    fun complete(args: Array<String>): List<String> {
        val action = args.getOrNull(1)?.lowercase()
        return when {
            args.size == 2 -> ACTIONS
            args.size == 3 && action in ACTIONS -> arenas.getArenas().map { it.name }
            args.size == 4 && (action == "add" || action == "remove") ->
                listOf(ScheduleParser.DAILY) + DayOfWeek.values().map { it.name.lowercase() }
            args.size >= 5 && action == "remove" -> {
                // Times the arena actually has on those days
                val arena = arenas.getArena(args[2]) ?: return emptyList()
                val days = ScheduleParser.parseDays(args[3]) ?: return emptyList()
                arena.schedule.filter { it.day in days }.map { ScheduleParser.formatTime(it.time) }.distinct()
            }
            else -> emptyList()
        }
    }

    private fun list(
        sender: CommandSender,
        label: String,
        arenaName: String?,
    ) {
        val single = arenaName?.let { findArena(sender, it) ?: return }
        if (single != null && single.schedule.isEmpty()) {
            sender.sendMessage(messages.get("schedule.arena-empty", "%arena%" to single.name, "%label%" to label))
            return
        }

        val shown =
            single?.let { listOf(it) }
                ?: arenas.getArenas().filter { it.schedule.isNotEmpty() }.sortedBy { it.name.lowercase() }
        if (shown.isEmpty()) {
            sender.sendMessage(messages.get("schedule.list-empty", "%label%" to label))
            return
        }

        sender.sendMessage(messages.get("schedule.list-header", "%timezone%" to scheduler.zoneId.id))
        for (arena in shown) {
            sender.sendMessage(
                messages.raw(
                    "schedule.list-entry",
                    "%arena%" to arena.name,
                    "%type%" to arena.type,
                    "%disabled%" to if (arena.isEnabled) "" else messages.raw("schedule.list-disabled"),
                    "%slots%" to ScheduleParser.describe(arena.schedule),
                ),
            )
        }

        val next =
            if (single != null) {
                scheduler.nextStartOf(single)?.takeIf { single.isEnabled }?.let { single to it }
            } else {
                scheduler.nextStart()
            }
        next?.let { (arena, at) -> sender.sendMessage(nextLine(arena, at)) }
    }

    private fun add(
        sender: CommandSender,
        label: String,
        args: Array<String>,
    ) {
        if (args.size < 5) return usage(sender, "/$label schedule add <arena> <day> <HH:mm> [HH:mm...]")
        val arena = findArena(sender, args[2]) ?: return
        val days = parseDays(sender, args[3]) ?: return
        val times = parseTimes(sender, args.drop(4)) ?: return

        val added = days.flatMap { day -> times.map { ScheduleEntry(day, it) } }.filter { it !in arena.schedule }
        if (added.isEmpty()) {
            sender.sendMessage(messages.get("schedule.already-scheduled", "%arena%" to arena.name))
            return
        }
        arenas.setSchedule(arena, arena.schedule + added)
        sender.sendMessage(
            messages.get("schedule.added", "%arena%" to arena.name, "%slots%" to ScheduleParser.describe(added)),
        )
        if (plugin.kothArena.isEventArena(arena)) {
            sender.sendMessage(
                messages.get(
                    "schedule.event-note",
                    "%arena%" to arena.name,
                    "%time%" to formatDuration(plugin.kothArena.joinTime.toLong()),
                ),
            )
        }
        afterChange(sender, label, arena)
    }

    private fun remove(
        sender: CommandSender,
        label: String,
        args: Array<String>,
    ) {
        if (args.size < 4) return usage(sender, "/$label schedule remove <arena> <day> [HH:mm...]")
        val arena = findArena(sender, args[2]) ?: return
        val days = parseDays(sender, args[3]) ?: return
        val times = if (args.size > 4) parseTimes(sender, args.drop(4)) ?: return else null

        val removed = arena.schedule.filter { it.day in days && (times == null || it.time in times) }
        if (removed.isEmpty()) {
            sender.sendMessage(messages.get("schedule.not-scheduled", "%arena%" to arena.name))
            return
        }
        arenas.setSchedule(arena, arena.schedule - removed.toSet())
        sender.sendMessage(
            messages.get("schedule.removed", "%arena%" to arena.name, "%slots%" to ScheduleParser.describe(removed)),
        )
        afterChange(sender, label, arena)
    }

    private fun clear(
        sender: CommandSender,
        label: String,
        args: Array<String>,
    ) {
        if (args.size < 3) return usage(sender, "/$label schedule clear <arena>")
        val arena = findArena(sender, args[2]) ?: return
        val count = arena.schedule.size
        if (count == 0) {
            sender.sendMessage(messages.get("schedule.arena-empty", "%arena%" to arena.name, "%label%" to label))
            return
        }
        arenas.setSchedule(arena, emptyList())
        sender.sendMessage(messages.get("schedule.cleared", "%arena%" to arena.name, "%count%" to count.toString()))
    }

    /** The arena's next start, or that it has none left; and a warning if it's disabled. */
    private fun afterChange(
        sender: CommandSender,
        label: String,
        arena: Arena,
    ) {
        if (!arena.isEnabled) {
            sender.sendMessage(messages.get("schedule.arena-disabled", "%arena%" to arena.name))
            return
        }
        val next = scheduler.nextStartOf(arena)
        if (next == null) {
            sender.sendMessage(messages.get("schedule.arena-empty", "%arena%" to arena.name, "%label%" to label))
        } else {
            sender.sendMessage(nextLine(arena, next))
        }
    }

    private fun nextLine(
        arena: Arena,
        at: ZonedDateTime,
    ): String {
        return messages.get(
            "schedule.next",
            "%arena%" to arena.name,
            "%when%" to at.format(WHEN_FORMAT),
            "%in%" to formatDuration(Duration.between(ZonedDateTime.now(at.zone), at).seconds),
        )
    }

    private fun findArena(
        sender: CommandSender,
        name: String,
    ): Arena? {
        val arena = arenas.getArena(name)
        if (arena == null) sender.sendMessage(messages.get("admin.arena-not-found", "%arena%" to name))
        return arena
    }

    private fun parseDays(
        sender: CommandSender,
        input: String,
    ): List<DayOfWeek>? {
        val days = ScheduleParser.parseDays(input)
        if (days == null) sender.sendMessage(messages.get("schedule.invalid-day", "%day%" to input))
        return days
    }

    private fun parseTimes(
        sender: CommandSender,
        inputs: List<String>,
    ): List<LocalTime>? {
        val times = mutableListOf<LocalTime>()
        for (input in inputs) {
            val time = ScheduleParser.parseTime(input)
            if (time == null) {
                sender.sendMessage(messages.get("schedule.invalid-time", "%time%" to input))
                return null
            }
            times += time
        }
        return times.distinct()
    }

    private fun usage(
        sender: CommandSender,
        usage: String,
    ) {
        sender.sendMessage(messages.get("general.invalid-args", "%usage%" to usage))
    }

    companion object {
        private val ACTIONS = listOf("list", "add", "remove", "clear")
        private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    }
}
