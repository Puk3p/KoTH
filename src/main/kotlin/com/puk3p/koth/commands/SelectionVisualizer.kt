package com.puk3p.koth.commands

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Bukkit
import org.bukkit.Effect
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.max
import kotlin.math.min

/**
 * Draws each admin's zone selection with particles, visible only to that admin:
 * - one corner set: a vertical column at it (pos1 green, pos2 red);
 * - both corners set: the outline of the zone. The vertical edges at the selected corners are
 *   green (pos1) and red (pos2), every other edge yellow.
 * Shown while the admin holds the wand, and for a few seconds after a corner is set (so /koth pos1
 * and /koth pos2 are visible too). A selection disappears with /koth create.
 */
class SelectionVisualizer(private val plugin: KoTHPlugin) : BukkitRunnable() {
    private class Color(val r: Float, val g: Float, val b: Float)

    private var failed = false

    override fun run() {
        if (failed) return
        val now = System.currentTimeMillis()
        for ((uuid, selection) in plugin.selections.all()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val holdingWand = plugin.selections.isWand(player.itemInHand)
            if (!holdingWand && now - selection.changedAt > SHOW_AFTER_CHANGE_MS) continue
            try {
                draw(player, selection)
            } catch (e: Exception) {
                // A server build without the Spigot particle API: stop trying, selecting still works
                failed = true
                plugin.logger.warning("Selection particles are unavailable on this server build: ${e.javaClass.simpleName}")
                return
            } catch (e: LinkageError) {
                failed = true
                plugin.logger.warning("Selection particles are unavailable on this server build: ${e.javaClass.simpleName}")
                return
            }
        }
    }

    private fun draw(
        player: Player,
        selection: SelectionManager.Selection,
    ) {
        val pos1 = selection.pos1
        val pos2 = selection.pos2
        val world = player.world

        if (pos1 != null && pos2 != null && pos1.world == pos2.world) {
            if (pos1.world == world) drawBox(player, world, pos1, pos2)
            return
        }
        pos1?.takeIf { it.world == world }?.let { column(player, world, it, GREEN) }
        pos2?.takeIf { it.world == world }?.let { column(player, world, it, RED) }
    }

    /** A single corner: a column rising from the selected block. */
    private fun column(
        player: Player,
        world: World,
        corner: Location,
        color: Color,
    ) {
        val x = corner.blockX + 0.5
        val z = corner.blockZ + 0.5
        val bottom = corner.blockY.toDouble()
        line(player, world, x, bottom, z, x, bottom + COLUMN_HEIGHT, z, STEP, color)
    }

    /** The selection's outline, on the outer faces of its blocks. */
    private fun drawBox(
        player: Player,
        world: World,
        pos1: Location,
        pos2: Location,
    ) {
        val minX = min(pos1.blockX, pos2.blockX).toDouble()
        val minY = min(pos1.blockY, pos2.blockY).toDouble()
        val minZ = min(pos1.blockZ, pos2.blockZ).toDouble()
        val maxX = max(pos1.blockX, pos2.blockX) + 1.0
        val maxY = max(pos1.blockY, pos2.blockY) + 1.0
        val maxZ = max(pos1.blockZ, pos2.blockZ) + 1.0

        // Bigger zones get sparser dots, so one outline never costs more than MAX_POINTS particles
        val edgeLength = 4 * ((maxX - minX) + (maxY - minY) + (maxZ - minZ))
        val step = max(STEP, edgeLength / MAX_POINTS)

        // The box corner that each selected block sits at
        fun cornerX(pos: Location) = if (pos.blockX.toDouble() == minX) minX else maxX

        fun cornerZ(pos: Location) = if (pos.blockZ.toDouble() == minZ) minZ else maxZ
        val corner1 = cornerX(pos1) to cornerZ(pos1)
        val corner2 = cornerX(pos2) to cornerZ(pos2)

        // Vertical edges: bottom to top
        for (x in listOf(minX, maxX)) {
            for (z in listOf(minZ, maxZ)) {
                val color =
                    when (x to z) {
                        corner1 -> GREEN
                        corner2 -> RED
                        else -> YELLOW
                    }
                line(player, world, x, minY, z, x, maxY, z, step, color)
            }
        }
        // Bottom and top rectangles
        for (y in listOf(minY, maxY)) {
            line(player, world, minX, y, minZ, maxX, y, minZ, step, YELLOW)
            line(player, world, minX, y, maxZ, maxX, y, maxZ, step, YELLOW)
            line(player, world, minX, y, minZ, minX, y, maxZ, step, YELLOW)
            line(player, world, maxX, y, minZ, maxX, y, maxZ, step, YELLOW)
        }
    }

    private fun line(
        player: Player,
        world: World,
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
        step: Double,
        color: Color,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        val length = Math.sqrt(dx * dx + dy * dy + dz * dz)
        val points = max(1, (length / step).toInt())
        val location = Location(world, x1, y1, z1)
        for (i in 0..points) {
            val t = i.toDouble() / points
            location.x = x1 + dx * t
            location.y = y1 + dy * t
            location.z = z1 + dz * t
            dust(player, location, color)
        }
    }

    /**
     * Colored redstone dust for one player: with a particle count of 0 the client reads the offsets
     * as the color (a red of exactly 0 would be turned into 1, hence the tiny values).
     */
    private fun dust(
        player: Player,
        location: Location,
        color: Color,
    ) {
        player.spigot().playEffect(location, Effect.COLOURED_DUST, 0, 0, color.r, color.g, color.b, 1f, 0, VIEW_RADIUS)
    }

    companion object {
        const val PERIOD_TICKS = 10L

        private const val SHOW_AFTER_CHANGE_MS = 10_000L
        private const val COLUMN_HEIGHT = 12.0
        private const val STEP = 0.5
        private const val MAX_POINTS = 240.0
        private const val VIEW_RADIUS = 96

        private val GREEN = Color(0.001f, 1f, 0.001f)
        private val RED = Color(1f, 0.001f, 0.001f)
        private val YELLOW = Color(1f, 1f, 0.001f)
    }
}
