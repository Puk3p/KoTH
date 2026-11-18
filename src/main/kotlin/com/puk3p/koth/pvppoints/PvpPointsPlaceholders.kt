package com.puk3p.koth.pvppoints

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.util.Locale
import java.util.Objects

/**
 * %pvppoints_...%: points, points_formatted (1,234), rank, top_<n>_name, top_<n>_points.
 * Only instantiate when PlaceholderAPI is installed.
 */
class PvpPointsPlaceholders(
    private val store: PvpPointsStore,
    private val version: String,
) : PlaceholderExpansion() {
    private val topRegex = Regex("top_(\\d+)_(name|points)")

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
        when (params.lowercase(Locale.ROOT)) {
            "points" -> return player?.let { store.points(it.uniqueId) }?.toString() ?: "0"
            "points_formatted" -> return String.format(Locale.US, "%,d", player?.let { store.points(it.uniqueId) } ?: 0)
            "rank" -> return player?.let { store.rank(it.uniqueId) }?.toString() ?: "-"
        }
        val match = topRegex.matchEntire(params.lowercase(Locale.ROOT)) ?: return null
        val position = match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
        val isName = match.groupValues[2] == "name"
        val entry = store.top(position).getOrNull(position - 1) ?: return if (isName) "-" else "0"
        return if (isName) entry.name else entry.points.toString()
    }
}
