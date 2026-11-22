package com.puk3p.koth.pvppoints

import com.puk3p.koth.hooks.FactionsHook
import com.puk3p.koth.util.stripColorCodes
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.util.Locale
import java.util.Objects

/**
 * %pvppoints_...%, all about factions:
 * - the viewer's faction: faction, points, points_formatted (1,234), rank (3), rank_formatted (#3);
 * - the top: top_<n>_name, top_<n>_points, top_<n>_points_formatted (any n: top 3, top 5, ...);
 * - any faction by tag: faction_points_<tag>, faction_points_formatted_<tag>, faction_rank_<tag>,
 *   faction_rank_formatted_<tag>. In SaberFactions' /f who (config.yml -> show) write
 *   %pvppoints_faction_points_{faction}%: Factions puts the shown faction's tag in place of
 *   {faction} before PlaceholderAPI runs.
 * Nothing to show (no faction, no points, no such place in the top) gives "-" for names and
 * ranks and "0" for points. Only instantiate when PlaceholderAPI is installed.
 */
class PvpPointsPlaceholders(
    private val store: PvpPointsStore,
    private val factions: FactionsHook,
    private val version: String,
) : PlaceholderExpansion() {
    private val topRegex = Regex("top_(\\d+)_(name|points|points_formatted)")

    override fun getIdentifier(): String = "pvppoints"

    override fun getAuthor(): String = "puk3p"

    override fun getVersion(): String = version

    // Stay registered across /papi reload; PvpPointsModule unregisters on disable.
    override fun persist(): Boolean = true

    // PlaceholderExpansion.equals compares identifier, author and version; keep hashCode consistent
    override fun hashCode(): Int = Objects.hash(identifier, author, version)

    override fun onRequest(
        player: OfflinePlayer?,
        params: String,
    ): String? {
        val lower = params.lowercase(Locale.ROOT)
        // Longest prefixes first: faction_points_formatted_ also starts with faction_points_
        for ((prefix, value) in BY_TAG) {
            if (lower.startsWith(prefix)) return byTag(params.substring(prefix.length), value)
        }

        when (lower) {
            "faction" -> return player?.let { factions.factionOf(it.uniqueId) }?.tag ?: NONE
            "points", "points_formatted", "rank", "rank_formatted" -> {
                val id = player?.let { factions.factionOf(it.uniqueId) }?.id
                return value(id, lower)
            }
        }

        val match = topRegex.matchEntire(lower) ?: return null
        val position = match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val field = match.groupValues[2]
        val entry = store.top(position).getOrNull(position - 1)
        return when (field) {
            "name" -> entry?.tag ?: NONE
            "points" -> (entry?.points ?: 0).toString()
            else -> formatted(entry?.points ?: 0)
        }
    }

    private fun byTag(
        tag: String,
        value: String,
    ): String {
        val id = factions.factionByTag(tag)?.id ?: store.findByTag(stripColorCodes(tag))?.factionId
        return value(id, value)
    }

    /** points, points_formatted, rank or rank_formatted of the faction with [factionId] (null = none). */
    private fun value(
        factionId: String?,
        value: String,
    ): String {
        val points = factionId?.let { store.points(it) } ?: 0
        val rank = factionId?.let { store.rank(it) }
        return when (value) {
            "points" -> points.toString()
            "points_formatted" -> formatted(points)
            "rank" -> rank?.toString() ?: NONE
            else -> rank?.let { "#$it" } ?: NONE
        }
    }

    private fun formatted(points: Int): String = String.format(Locale.US, "%,d", points)

    private companion object {
        const val NONE = "-"

        val BY_TAG =
            listOf(
                "faction_points_formatted_" to "points_formatted",
                "faction_rank_formatted_" to "rank_formatted",
                "faction_points_" to "points",
                "faction_rank_" to "rank",
            )
    }
}
