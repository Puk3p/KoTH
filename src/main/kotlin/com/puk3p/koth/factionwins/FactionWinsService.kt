package com.puk3p.koth.factionwins

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class AddByPlayerResult { SUCCESS, PLAYER_NOT_FOUND, PLAYER_OFFLINE, NO_FACTION }

/** Faction KoTH wins: rules from config.yml -> faction-wins, faction lookups through FactionsHook. */
class FactionWinsService(
    private val plugin: KoTHPlugin,
    private val repository: FactionWinsRepository,
) {
    private val config get() = plugin.configManager.config

    private val ignoreNoFactionWinner: Boolean
        get() = config.getBoolean("faction-wins.ignore-no-faction-winner", true)

    private val allowOfflinePlayerLookup: Boolean
        get() = config.getBoolean("faction-wins.allow-offline-player-lookup", true)

    /** A capture: +1 win for the capturer's faction. */
    fun addWinForCapture(player: Player): Pair<AddByPlayerResult, String?> {
        return addToFactionOf(factionOf(player.uniqueId), 1)
    }

    /** /fkoth add <player> <amount>: adds wins to that player's faction. */
    fun addWinsForPlayer(
        playerName: String,
        amount: Int,
    ): Pair<AddByPlayerResult, String?> {
        val online = Bukkit.getPlayerExact(playerName)
        val known = online != null || plugin.factionsHook?.knowsPlayer(playerName) == true
        if (!known) return AddByPlayerResult.PLAYER_NOT_FOUND to null
        if (online == null && !allowOfflinePlayerLookup) return AddByPlayerResult.PLAYER_OFFLINE to null
        // Straight from Factions, not the placeholder cache: an admin command must act on current data
        return addToFactionOf(plugin.factionsHook?.getFactionTagByName(playerName), amount)
    }

    private fun addToFactionOf(
        faction: String?,
        amount: Int,
    ): Pair<AddByPlayerResult, String?> {
        if (faction != null) {
            repository.addWins(faction, amount)
            return AddByPlayerResult.SUCCESS to faction
        }
        if (ignoreNoFactionWinner) return AddByPlayerResult.NO_FACTION to null
        repository.addWins(WILDERNESS, amount)
        return AddByPlayerResult.SUCCESS to WILDERNESS
    }

    fun factionOf(uuid: java.util.UUID): String? = plugin.factionsHook?.getFactionTag(uuid)

    /**
     * Faction of a player by name, for placeholders and the API. Online players are looked up
     * directly. Offline ones need a scan of every player Factions knows, so that result is cached
     * briefly: a placeholder on a scoreboard would otherwise repeat the scan many times a second.
     */
    fun factionOfName(playerName: String): String? {
        val hook = plugin.factionsHook ?: return null
        Bukkit.getPlayerExact(playerName)?.let { return hook.getFactionTag(it.uniqueId) }

        val key = playerName.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        offlineNameCache[key]?.takeIf { it.expiresAt > now }?.let { return it.tag }

        if (offlineNameCache.size >= OFFLINE_NAME_CACHE_MAX) offlineNameCache.clear()
        val tag = hook.getFactionTagByName(playerName)
        offlineNameCache[key] = CachedTag(tag, now + OFFLINE_NAME_CACHE_MS)
        return tag
    }

    private class CachedTag(val tag: String?, val expiresAt: Long)

    private val offlineNameCache = ConcurrentHashMap<String, CachedTag>()

    fun isTracked(faction: String): Boolean = repository.isTracked(faction)

    /** A faction changed its tag; see [FactionWinsRepository.rename]. */
    fun renameFaction(
        oldName: String,
        newName: String,
    ): Int? = repository.rename(oldName, newName)

    fun getWinsForFaction(faction: String): Int = repository.getWins(faction)

    fun addWinsForFaction(
        faction: String,
        amount: Int,
    ): Int = repository.addWins(faction, amount)

    fun removeWinsForFaction(
        faction: String,
        amount: Int,
    ): Int = repository.removeWins(faction, amount)

    fun setWinsForFaction(
        faction: String,
        amount: Int,
    ): Int = repository.setWins(faction, amount)

    fun clearFaction(faction: String) = repository.removeFaction(faction)

    fun top(limit: Int): List<FactionWins> = repository.top(limit)

    fun getRankForFaction(faction: String): Int? {
        val needle = faction.lowercase(Locale.ROOT)
        val index = repository.top(Int.MAX_VALUE).indexOfFirst { it.faction.lowercase(Locale.ROOT) == needle }
        return if (index == -1) null else index + 1
    }

    fun trackedFactionCount(): Int = repository.trackedFactionCount()

    fun save() = repository.save()

    companion object {
        const val WILDERNESS = "Wilderness"

        private const val OFFLINE_NAME_CACHE_MS = 30_000L
        private const val OFFLINE_NAME_CACHE_MAX = 1000
    }
}
