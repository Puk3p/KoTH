package com.puk3p.koth.hooks

import com.massivecraft.factions.FPlayer
import com.massivecraft.factions.FPlayers
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Hook for the legacy Factions API (com.massivecraft.factions) used on 1.8.8 servers
 * by FactionsUUID 1.6.9.5-U0.x and SaberFactions.
 *
 * Only instantiate this when the Factions plugin is installed: the class references
 * below cannot be resolved otherwise.
 */
class FactionsHook {
    fun getFaction(player: Player): String {
        return try {
            val fPlayer = FPlayers.getInstance().getByPlayer(player)
            if (fPlayer.hasFaction()) {
                fPlayer.faction.tag
            } else {
                NO_FACTION
            }
        } catch (e: Exception) {
            NO_FACTION // Fallback in case of API mismatch
        } catch (e: LinkageError) {
            NO_FACTION // Installed Factions fork lacks a method/class we compiled against
        }
    }

    fun getFactionId(player: Player): String {
        return try {
            val fPlayer = FPlayers.getInstance().getByPlayer(player)
            if (fPlayer.hasFaction()) fPlayer.faction.id else ""
        } catch (e: Exception) {
            ""
        } catch (e: LinkageError) {
            ""
        }
    }

    /**
     * Faction tag of a player, online or offline, or null if they have no faction or Factions
     * doesn't know them. Never creates Factions data for unknown players.
     */
    fun getFactionTag(uuid: UUID): String? {
        return try {
            val fPlayers = FPlayers.getInstance()
            val fPlayer =
                Bukkit.getPlayer(uuid)?.let { fPlayers.getByPlayer(it) }
                    ?: fPlayers.getById(uuid.toString())
                    ?: return null
            tagOf(fPlayer)
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    /**
     * Like [getFactionTag], by player name. Looks in Factions' own player data (no Mojang lookups,
     * which Bukkit.getOfflinePlayer(name) can do on 1.8 and which would block the main thread).
     */
    fun getFactionTagByName(name: String): String? {
        Bukkit.getPlayerExact(name)?.let { return getFactionTag(it.uniqueId) }
        return try {
            findByName(name)?.let { tagOf(it) }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    /** Whether Factions has a record of a player with this name (online or not). */
    fun knowsPlayer(name: String): Boolean {
        if (Bukkit.getPlayerExact(name) != null) return true
        return try {
            findByName(name) != null
        } catch (e: Exception) {
            false
        } catch (e: LinkageError) {
            false
        }
    }

    private fun findByName(name: String): FPlayer? {
        return FPlayers.getInstance().allFPlayers.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun tagOf(fPlayer: FPlayer): String? {
        if (!fPlayer.hasFaction()) return null
        val faction = fPlayer.faction
        if (faction.isWilderness) return null
        return faction.tag?.trim()?.takeIf { it.isNotEmpty() && !it.equals("wilderness", ignoreCase = true) }
    }

    companion object {
        const val NO_FACTION = "No Faction"
    }
}
