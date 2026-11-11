package com.puk3p.koth.factionwins

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.util.Objects

/**
 * %fkoth_...% placeholders, same names as the FKothWins addon so existing scoreboards keep working:
 * faction_name, faction_wins, faction_rank, top_size, top_<n>_name, top_<n>_wins,
 * player_faction_wins_<player>. Only instantiate when PlaceholderAPI is installed.
 */
class FKothPlaceholders(
    private val service: FactionWinsService,
    private val version: String,
) : PlaceholderExpansion() {
    private val topRegex = Regex("top_(\\d+)_(name|wins)")
    private val playerFactionWinsRegex = Regex("player_faction_wins_(.+)")

    override fun getIdentifier(): String = "fkoth"

    override fun getAuthor(): String = "puk3p"

    override fun getVersion(): String = version

    // Stay registered across /papi reload; FactionWinsModule unregisters on disable.
    override fun persist(): Boolean = true

    // PlaceholderExpansion.equals compares identifier, author and version; keep hashCode consistent
    override fun hashCode(): Int = Objects.hash(identifier, author, version)

    override fun onRequest(
        player: OfflinePlayer?,
        params: String,
    ): String {
        playerFactionWinsRegex.matchEntire(params)?.let { match ->
            val playerName = match.groupValues[1]
            if (playerName.isBlank()) return "0"
            val faction = service.factionOfName(playerName) ?: return "0"
            return service.getWinsForFaction(faction).toString()
        }

        when (params.lowercase()) {
            "top_size" -> return service.trackedFactionCount().toString()
            "faction_name" -> return player?.let { service.factionOf(it.uniqueId) } ?: "-"
            "faction_wins" -> {
                val faction = player?.let { service.factionOf(it.uniqueId) } ?: return "0"
                return service.getWinsForFaction(faction).toString()
            }
            "faction_rank" -> {
                val faction = player?.let { service.factionOf(it.uniqueId) } ?: return "-"
                return service.getRankForFaction(faction)?.toString() ?: "-"
            }
        }

        val match = topRegex.matchEntire(params.lowercase()) ?: return ""
        val position = match.groupValues[1].toIntOrNull()?.takeIf { it >= 1 } ?: return ""
        val isName = match.groupValues[2] == "name"
        val entry = service.top(position).getOrNull(position - 1) ?: return if (isName) "-" else "0"
        return if (isName) entry.faction else entry.wins.toString()
    }
}
