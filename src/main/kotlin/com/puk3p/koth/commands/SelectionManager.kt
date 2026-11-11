package com.puk3p.koth.commands

import com.puk3p.koth.KoTHPlugin
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.math.abs

/**
 * Each admin's pending zone selection, set by /koth pos1 and /koth pos2 or with the wand, and used
 * by /koth create.
 */
class SelectionManager(private val plugin: KoTHPlugin) {

    class Selection {
        var pos1: Location? = null
        var pos2: Location? = null

        // When a corner was last set; SelectionVisualizer shows the selection for a while after it
        var changedAt: Long = 0
    }

    private val selections = HashMap<UUID, Selection>()

    fun get(uuid: UUID): Selection? = selections[uuid]

    /** Every pending selection, by player. Main thread only. */
    fun all(): Map<UUID, Selection> = selections

    fun clear(uuid: UUID) {
        selections.remove(uuid)
    }

    /** Stores a corner and tells the player, with the selection's size once both corners are set. */
    fun setCorner(player: Player, first: Boolean, location: Location) {
        val selection = selections.getOrPut(player.uniqueId) { Selection() }
        if (first) selection.pos1 = location else selection.pos2 = location
        selection.changedAt = System.currentTimeMillis()

        val messages = plugin.configManager.messages
        player.sendMessage(
            messages.get(
                if (first) "admin.pos1-set" else "admin.pos2-set",
                "%x%" to location.blockX.toString(),
                "%y%" to location.blockY.toString(),
                "%z%" to location.blockZ.toString()
            )
        )

        val pos1 = selection.pos1 ?: return
        val pos2 = selection.pos2 ?: return
        if (pos1.world != pos2.world) return
        val sizeX = abs(pos1.blockX - pos2.blockX) + 1
        val sizeY = abs(pos1.blockY - pos2.blockY) + 1
        val sizeZ = abs(pos1.blockZ - pos2.blockZ) + 1
        player.sendMessage(
            messages.get(
                "admin.selection-size",
                "%x%" to sizeX.toString(),
                "%y%" to sizeY.toString(),
                "%z%" to sizeZ.toString(),
                "%blocks%" to (sizeX.toLong() * sizeY * sizeZ).toString()
            )
        )
    }

    // ------------------------------------------------------------------ //
    //  Wand
    // ------------------------------------------------------------------ //

    /** A golden axe named and described from messages.yml (`wand`), recognised by a hidden marker line. */
    fun createWand(): ItemStack {
        val messages = plugin.configManager.messages
        val item = ItemStack(WAND_MATERIAL)
        val meta = item.itemMeta
        meta.displayName = messages.raw("wand.name")
        meta.lore = messages.getList("wand.lore") + WAND_MARKER
        item.itemMeta = meta
        return item
    }

    /** Recognised by its marker, so renaming the wand in messages.yml doesn't break wands already given out. */
    fun isWand(item: ItemStack?): Boolean {
        if (item == null || item.type != WAND_MATERIAL || !item.hasItemMeta()) return false
        return item.itemMeta.lore?.contains(WAND_MARKER) == true
    }

    companion object {
        private val WAND_MATERIAL = Material.GOLD_AXE

        // Black text on the tooltip's dark background: practically invisible
        private val WAND_MARKER = "${ChatColor.BLACK}koth-wand"
    }
}
