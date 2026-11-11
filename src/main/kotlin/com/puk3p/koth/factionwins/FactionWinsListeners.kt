package com.puk3p.koth.factionwins

import com.massivecraft.factions.event.FactionDisbandEvent
import com.massivecraft.factions.event.FactionRenameEvent
import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.event.KothCaptureEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** Every completed capture adds a win to the capturer's faction. */
class CaptureWinListener(
    private val plugin: KoTHPlugin,
    private val service: FactionWinsService,
) : Listener {
    // MONITOR + ignoreCancelled: only captures that really happen (a cancelled capture is retried later)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCapture(event: KothCaptureEvent) {
        val winner = event.capturer.name
        val (status, faction) = service.addWinForCapture(event.capturer)
        when (status) {
            AddByPlayerResult.SUCCESS -> plugin.logger.info("[FKoth] +1 win for faction '$faction' (winner: $winner)")
            AddByPlayerResult.NO_FACTION -> plugin.logger.info("[FKoth] Winner '$winner' has no faction; win not counted.")
            else -> Unit // not reachable for an online capturer
        }
    }
}

/**
 * A faction that changes its tag keeps its wins. Wins are stored by tag, so without this the
 * renamed faction would drop to 0 and whoever took the old tag next would inherit its wins.
 * In its own class (and registered separately) because not every Factions fork has this event.
 */
class FactionRenameListener(
    private val plugin: KoTHPlugin,
    private val service: FactionWinsService,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRename(event: FactionRenameEvent) {
        val faction = event.faction ?: return
        val oldTag = faction.tag?.trim().orEmpty() // still the old tag: the event fires before the change
        val newTag = event.factionTag?.trim().orEmpty()
        if (oldTag.isEmpty() || newTag.isEmpty() || oldTag.equals(newTag, ignoreCase = true)) return

        // The event fires before Factions applies the new tag, and the rename can still fail after it
        // (e.g. the payment). Move the wins on the next tick, only if the faction really has the new tag.
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                if (!newTag.equals(faction.tag?.trim(), ignoreCase = true)) return@Runnable
                val moved = service.renameFaction(oldTag, newTag) ?: return@Runnable
                plugin.logger.info("[FKoth] Faction '$oldTag' is now '$newTag'; moved its $moved KoTH win(s).")
            },
        )
    }
}

/**
 * Removes a faction's wins when it disbands (config: faction-wins.delete-wins-on-faction-disband).
 * References Factions classes, so it's only registered when Factions is installed.
 *
 * Unlike the FKothWins addon, this doesn't filter on the disband reason: the addon only acted when
 * the reason contained "disband", which none of SaberFactions' reasons (COMMAND, PLUGIN, INACTIVITY,
 * LEAVE) do, so wins were never actually removed.
 */
class FactionDisbandListener(
    private val plugin: KoTHPlugin,
    private val service: FactionWinsService,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDisband(event: FactionDisbandEvent) {
        if (!plugin.configManager.config.getBoolean("faction-wins.delete-wins-on-faction-disband", true)) return
        val tag = event.faction?.tag?.trim().orEmpty()
        if (tag.isEmpty()) return

        val wins = service.getWinsForFaction(tag)
        if (wins <= 0) return
        service.clearFaction(tag)
        plugin.logger.info("[FKoth] Faction '$tag' disbanded; removed its $wins KoTH win(s).")
    }
}
