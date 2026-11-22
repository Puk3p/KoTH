package com.puk3p.koth.hooks

import com.massivecraft.factions.FPlayer
import com.massivecraft.factions.FPlayers
import com.massivecraft.factions.Faction
import com.massivecraft.factions.Factions
import com.puk3p.koth.util.stripColorCodes
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/** See [FactionsHook.relationBetween]. Its own class, so it loads without Factions installed. */
enum class FactionRelation { SAME_FACTION, ALLY, NONE }

/** A player-made faction (not Wilderness, SafeZone or WarZone): its [id] never changes, its [tag] can. */
data class FactionRef(
    val id: String,
    val tag: String,
)

/** A player Factions knows, with their exact [name] and their faction (null when they have none). */
data class FactionMember(
    val name: String,
    val faction: FactionRef?,
)

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

    /**
     * How two players' factions relate. Factionless players are never related, even to each other
     * (some Factions forks call two Wilderness players MEMBERs).
     */
    fun relationBetween(
        a: Player,
        b: Player,
    ): FactionRelation {
        return try {
            val fPlayers = FPlayers.getInstance()
            val fa = fPlayers.getByPlayer(a)
            val fb = fPlayers.getByPlayer(b)
            when {
                !fa.hasFaction() || !fb.hasFaction() -> FactionRelation.NONE
                fa.faction.isWilderness || fb.faction.isWilderness -> FactionRelation.NONE
                fa.faction.id == fb.faction.id -> FactionRelation.SAME_FACTION
                fa.getRelationTo(fb).isAlly -> FactionRelation.ALLY
                else -> FactionRelation.NONE
            }
        } catch (e: Exception) {
            FactionRelation.NONE
        } catch (e: LinkageError) {
            FactionRelation.NONE
        }
    }

    /** The player's faction, or null when they have none. */
    fun factionOf(player: Player): FactionRef? = guarded { refOf(FPlayers.getInstance().getByPlayer(player)) }

    /** Like [factionOf], for any player Factions knows, online or not. Never creates Factions data. */
    fun factionOf(uuid: UUID): FactionRef? =
        guarded {
            val fPlayers = FPlayers.getInstance()
            val fPlayer = Bukkit.getPlayer(uuid)?.let { fPlayers.getByPlayer(it) } ?: fPlayers.getById(uuid.toString())
            fPlayer?.let { refOf(it) }
        }

    /**
     * A player by name, online first, then in Factions' own player data (no Mojang lookups).
     * Null when Factions has never seen a player with that name.
     */
    fun findMember(name: String): FactionMember? =
        guarded {
            val fPlayers = FPlayers.getInstance()
            val online = Bukkit.getPlayerExact(name)
            if (online != null) {
                FactionMember(online.name, refOf(fPlayers.getByPlayer(online)))
            } else {
                findByName(name)?.let { FactionMember(it.name ?: name, refOf(it)) }
            }
        }

    /** A faction by tag, ignoring case and color codes (/f who puts the relation color in front of it). */
    fun factionByTag(tag: String): FactionRef? {
        val plain = stripColorCodes(tag)
        if (plain.isEmpty()) return null
        return guarded { refOf(Factions.getInstance().getByTag(plain)) }
    }

    /** A faction by id, or null if it no longer exists. */
    fun factionById(id: String): FactionRef? = guarded { refOf(Factions.getInstance().getFactionById(id)) }

    private fun findByName(name: String): FPlayer? {
        return FPlayers.getInstance().allFPlayers.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun tagOf(fPlayer: FPlayer): String? {
        if (!fPlayer.hasFaction()) return null
        val faction = fPlayer.faction
        if (faction.isWilderness) return null
        return faction.tag?.trim()?.takeIf { it.isNotEmpty() && !it.equals("wilderness", ignoreCase = true) }
    }

    private fun refOf(fPlayer: FPlayer): FactionRef? = if (fPlayer.hasFaction()) refOf(fPlayer.faction) else null

    private fun refOf(faction: Faction?): FactionRef? {
        if (faction == null || !faction.isNormal) return null
        val tag = faction.tag?.trim().orEmpty()
        if (tag.isEmpty()) return null
        return FactionRef(faction.id, tag)
    }

    /** Runs a Factions call; null if the installed Factions fork doesn't match the API we compiled against. */
    private inline fun <T> guarded(block: () -> T?): T? {
        return try {
            block()
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    companion object {
        const val NO_FACTION = "No Faction"
    }
}
