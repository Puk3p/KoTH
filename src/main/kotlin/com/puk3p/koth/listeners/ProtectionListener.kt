package com.puk3p.koth.listeners

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.arena.Arena
import com.puk3p.koth.arena.Region
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * Rules inside KoTH zones and for KOTH Arena players:
 * - `types.<type>.protections`: protected actions inside every zone of that type, whether or not the
 *   KoTH is running. `true` = protected (block-breaking, block-placing, bucket-use, enderpearls,
 *   lava-flow, water-flow, tnt, pistons); `fall-damage: false` = no fall damage in the zone.
 * - `types.<type>.command-restrictions`: BLACKLIST/WHITELIST of commands for players standing in a
 *   running KoTH's zone.
 * - KOTH Arena players: only `koth-arena.allowed-commands`, invincibility while joining (and after
 *   the capture, if configured), no PvP with outsiders.
 *
 * Players with koth.bypass or koth.admin ignore protections and command restrictions.
 */
class ProtectionListener(private val plugin: KoTHPlugin) : Listener {

    // ------------------------------------------------------------------ //
    //  Zone protections: player actions
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val keys = if (event.block.type == Material.TNT) arrayOf("block-placing", "tnt") else arrayOf("block-placing")
        if (deniesPlayer(event.player, event.block.location, *keys)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (deniesPlayer(event.player, event.block.location, "block-breaking")) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        val target = event.blockClicked.getRelative(event.blockFace).location
        if (deniesPlayer(event.player, target, "bucket-use")) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (deniesPlayer(event.player, event.blockClicked.location, "bucket-use")) {
            event.isCancelled = true
        }
    }

    /** Throwing a pearl while standing in the zone: blocked before the pearl is used up. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onPearlThrow(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.item?.type != Material.ENDER_PEARL) return
        if (deniesPlayer(event.player, event.player.location, "enderpearls", messagePath = "protection.enderpearls")) {
            event.isCancelled = true
        }
    }

    /** A pearl thrown from outside that would land in the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPearlLand(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return
        val to = event.to ?: return
        if (deniesPlayer(event.player, to, "enderpearls", messagePath = "protection.enderpearls")) {
            event.isCancelled = true
        }
    }

    /** `fall-damage: false`: no fall damage inside the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        val arena = plugin.arenaManager.getArenaAt(player.location) ?: return
        if (!plugin.configManager.config.getBoolean("types.${arena.type}.protections.fall-damage", true)) {
            event.isCancelled = true
        }
    }

    // ------------------------------------------------------------------ //
    //  Zone protections: world changes (no player to exempt)
    // ------------------------------------------------------------------ //

    /** Lava/water flowing into or inside the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val key = when (event.block.type) {
            Material.LAVA, Material.STATIONARY_LAVA -> "lava-flow"
            Material.WATER, Material.STATIONARY_WATER -> "water-flow"
            else -> return
        }
        if (protects(event.toBlock.location, key)) {
            event.isCancelled = true
        }
    }

    /** TNT (including cannons) and TNT minecarts don't break blocks in the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        if (event.entity !is TNTPrimed && event.entity !is ExplosiveMinecart) return
        val regions = protectedRegions("tnt")
        if (regions.isEmpty()) return
        event.blockList().removeIf { block -> regions.any { it.contains(block.location) } }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val regions = protectedRegions("pistons") // cheap exit: this fires for every piston on the server
        if (regions.isEmpty()) return
        val moved = event.blocks + event.block.getRelative(event.direction) // pushed blocks + piston head
        if (regions.any { pistonTouches(it, event.block, moved, event.direction) }) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val regions = protectedRegions("pistons")
        if (regions.isEmpty()) return
        // Retract directions aren't reported consistently across 1.8 builds, so check both sides.
        val directions = arrayOf(event.direction, event.direction.oppositeFace)
        if (regions.any { pistonTouches(it, event.block, event.blocks, *directions) }) {
            event.isCancelled = true
        }
    }

    /** True when the piston, a moved block, or where a moved block ends up is in [region]. */
    private fun pistonTouches(region: Region, piston: Block, moved: List<Block>, vararg directions: BlockFace): Boolean {
        return region.contains(piston.location) || moved.any { block ->
            region.contains(block.location) || directions.any { region.contains(block.getRelative(it).location) }
        }
    }

    // ------------------------------------------------------------------ //
    //  Commands
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (hasBypass(player)) return
        val config = plugin.configManager.config

        // KOTH Arena players: only the allowed commands (and /koth), for the whole event
        if (plugin.kothArena.isParticipant(player.uniqueId)) {
            if (!isAllowedCommand(event.message, config.getStringList("koth-arena.allowed-commands"))) {
                event.isCancelled = true
                player.sendMessage(plugin.kothArena.prefixed("command-blocked"))
            }
            return
        }

        // Players standing in a running KoTH's zone: that type's command-restrictions
        val arena = plugin.arenaManager.getActiveArena() ?: return
        if (player.uniqueId !in arena.playersInside) return
        val path = "types.${arena.type}.command-restrictions"
        if (!config.getBoolean("$path.enabled", false)) return

        val listed = matchesAny(event.message, config.getStringList("$path.blocked-commands"))
        val whitelist = config.getString("$path.mode", "BLACKLIST").equals("WHITELIST", ignoreCase = true)
        val blocked = if (whitelist) !listed && !matchesAny(event.message, ALWAYS_ALLOWED) else listed
        if (blocked) {
            event.isCancelled = true
            player.sendMessage(plugin.configManager.messages.get("protection.command-blocked"))
        }
    }

    // ------------------------------------------------------------------ //
    //  KOTH Arena: invincibility and fair fights
    // ------------------------------------------------------------------ //

    /** Invincible players take no damage (except the void, so nobody falls forever). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        if (event.cause == EntityDamageEvent.DamageCause.VOID) return
        if (plugin.kothArena.isInvincible(victim.uniqueId)) {
            event.isCancelled = true
        }
    }

    /**
     * ...and can't hurt anyone, directly or with projectiles/potions.
     * Also keeps the event fair: while it runs, players in it and players outside it can't damage
     * each other (`block-outsider-pvp`), so eliminated players and bystanders can't interfere.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        } ?: return

        val kothArena = plugin.kothArena
        if (kothArena.isInvincible(attacker.uniqueId)) {
            event.isCancelled = true
            return
        }

        val victim = event.entity as? Player ?: return
        if (victim == attacker || !kothArena.isRunning || !kothArena.blockOutsiderPvp) return
        if (kothArena.isParticipant(attacker.uniqueId) != kothArena.isParticipant(victim.uniqueId)) {
            event.isCancelled = true
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private fun hasBypass(player: Player): Boolean {
        return player.hasPermission(BYPASS_PERMISSION) || player.hasPermission(ADMIN_PERMISSION)
    }

    private fun protects(arena: Arena, key: String): Boolean {
        return plugin.configManager.config.getBoolean("types.${arena.type}.protections.$key", false)
    }

    /** True if [location] is in a zone whose type protects any of [keys]. */
    private fun protects(location: Location, vararg keys: String): Boolean {
        val arena = plugin.arenaManager.getArenaAt(location) ?: return false
        return keys.any { protects(arena, it) }
    }

    /** Zones whose type protects [key]. */
    private fun protectedRegions(key: String): List<Region> {
        return plugin.arenaManager.getArenas().filter { protects(it, key) }.map { it.region }
    }

    /** [protects] for a player action: honours the bypass permissions and tells the player. */
    private fun deniesPlayer(
        player: Player,
        location: Location,
        vararg keys: String,
        messagePath: String = "protection.denied",
    ): Boolean {
        if (!protects(location, *keys) || hasBypass(player)) return false
        player.sendMessage(plugin.configManager.messages.get(messagePath))
        return true
    }

    companion object {
        const val BYPASS_PERMISSION = "koth.bypass"
        private const val ADMIN_PERMISSION = "koth.admin"

        // Always usable, so /koth leave works whatever a command list says
        private val ALWAYS_ALLOWED = listOf("koth", "koths")

        /**
         * Whether a typed command is on the allowed list: "msg" allows "/msg Bob hi", "f who" allows
         * "/f who x" but not "/f home"; "/essentials:msg" counts as "msg". Case-insensitive, whole words.
         */
        fun isAllowedCommand(message: String, allowed: List<String>): Boolean = matchesAny(message, allowed + ALWAYS_ALLOWED)

        /** Whether a typed command matches an entry of [list] (same rules as [isAllowedCommand]). */
        fun matchesAny(message: String, list: List<String>): Boolean {
            val typed = normalize(message)
            return list.map { normalize(it) }.any { it.isNotEmpty() && (typed == it || typed.startsWith("$it ")) }
        }

        private fun normalize(command: String): String {
            val words = command.trim().removePrefix("/").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return ""
            return (listOf(words[0].substringAfter(':')) + words.drop(1)).joinToString(" ")
        }
    }
}
