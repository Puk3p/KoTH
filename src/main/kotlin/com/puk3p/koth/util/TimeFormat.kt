package com.puk3p.koth.util

/** Formats seconds as mm:ss (e.g. 300 -> "05:00"). */
fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}

/** Formats a longer span compactly: "2d 3h", "1h 15m", "45m", "30s". */
fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val days = s / 86_400
    val hours = s % 86_400 / 3_600
    val minutes = s % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${s}s"
    }
}

/**
 * Spoken duration, e.g. "3 minute", "1 minut", "10 secunde", "2 minute 30 de secunde".
 * Each unit has plural forms [one, few, many] (Romanian rules: few = 0 or 2..19 mod 100,
 * many = 20+ with "de"). For English, give the same word for few and many.
 */
fun formatSpoken(
    totalSeconds: Int,
    minuteForms: List<String>,
    secondForms: List<String>,
): String {
    val s = totalSeconds.coerceAtLeast(0)
    val minutes = s / 60
    val seconds = s % 60
    return when {
        minutes > 0 && seconds > 0 -> "${quantity(minutes, minuteForms)} ${quantity(seconds, secondForms)}"
        minutes > 0 -> quantity(minutes, minuteForms)
        else -> quantity(seconds, secondForms)
    }
}

private fun quantity(
    n: Int,
    forms: List<String>,
): String {
    val one = forms.getOrElse(0) { "" }
    val few = forms.getOrElse(1) { one }
    val many = forms.getOrElse(2) { few }
    val word =
        when {
            n == 1 -> one
            n == 0 || n % 100 in 1..19 -> few
            else -> many
        }
    return "$n $word".trimEnd()
}

/** Seconds left at which countdowns are broadcast, besides every full minute. */
private val COUNTDOWN_ANNOUNCE_AT = setOf(30, 10, 5, 4, 3, 2, 1)

fun shouldAnnounce(secondsLeft: Int): Boolean = secondsLeft % 60 == 0 || secondsLeft in COUNTDOWN_ANNOUNCE_AT
