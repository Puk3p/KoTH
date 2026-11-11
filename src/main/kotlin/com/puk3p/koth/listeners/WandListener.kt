package com.puk3p.koth.listeners

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.commands.KothCommand
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * The /koth wand: left click a block for pos1, right click a block for pos2. The clicked block is
 * selected exactly (unlike /koth pos1, which uses the block the player stands in) and is neither
 * broken nor used. Only for koth.admin; for anyone else the wand is just a golden axe.
 */
class WandListener(private val plugin: KoTHPlugin) : Listener {

    // Not ignoreCancelled: KoTH zones often sit in regions where another plugin cancels interaction,
    // and selecting must still work there.
    @EventHandler(priority = EventPriority.LOW)
    fun onClick(event: PlayerInteractEvent) {
        val first = when (event.action) {
            Action.LEFT_CLICK_BLOCK -> true
            Action.RIGHT_CLICK_BLOCK -> false
            else -> return
        }
        val player = event.player
        if (!plugin.selections.isWand(event.item) || !player.hasPermission(KothCommand.ADMIN_PERMISSION)) return
        val block = event.clickedBlock ?: return

        event.isCancelled = true // don't break or use the block
        plugin.selections.setCorner(player, first, block.location)
    }

    /** Creative mode breaks blocks instantly; cancelling the click isn't always enough. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val player = event.player
        if (plugin.selections.isWand(player.itemInHand) && player.hasPermission(KothCommand.ADMIN_PERMISSION)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.selections.clear(event.player.uniqueId)
    }
}
