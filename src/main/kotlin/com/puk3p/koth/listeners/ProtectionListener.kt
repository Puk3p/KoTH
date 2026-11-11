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
import org.bukkit.event.player.PlayerTeleportEvent

/**
 * KOTH Arena rules (config.yml -> koth-arena):
 * - `arena-protection` inside the capture zone while the event uses it. Flags mean "allowed":
 *   `block-placing: false` forbids placing blocks; a missing flag allows it. Normal KoTHs aren't protected.
 * - `allowed-commands`: the only commands players in the event can use.
 * - Invincibility while joining (and after the capture, if configured).
 *
 * Players with [BYPASS_PERMISSION] ignore protection and the command lock.
 */
class ProtectionListener(private val plugin: KoTHPlugin) : Listener {

    // ------------------------------------------------------------------ //
    //  Arena protection: player actions
    // ------------------------------------------------------------------ //

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val flags = if (event.block.type == Material.TNT) arrayOf("block-placing", "tnt") else arrayOf("block-placing")
        if (deniesPlayer(event.player, event.block.location, *flags)) {
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
        val flags = when (event.bucket) {
            Material.LAVA_BUCKET -> arrayOf("buckets", "lava")
            Material.WATER_BUCKET -> arrayOf("buckets", "water")
            else -> arrayOf("buckets")
        }
        if (deniesPlayer(event.player, target, *flags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (deniesPlayer(event.player, event.blockClicked.location, "buckets")) {
            event.isCancelled = true
        }
    }

    /** Pearling into, out of or within the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPearl(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return
        val to = event.to ?: return
        // Short-circuits, so the player is told at most once
        if (deniesPlayer(event.player, to, "pearls") || deniesPlayer(event.player, event.from, "pearls")) {
            event.isCancelled = true
        }
    }

    // ------------------------------------------------------------------ //
    //  Arena protection: world changes (no player to exempt)
    // ------------------------------------------------------------------ //

    /** Lava/water flowing into or inside the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val flag = when (event.block.type) {
            Material.LAVA, Material.STATIONARY_LAVA -> "lava"
            Material.WATER, Material.STATIONARY_WATER -> "water"
            else -> return
        }
        if (denies(event.toBlock.location, flag)) {
            event.isCancelled = true
        }
    }

    /** TNT (including cannons) and TNT minecarts don't break blocks in the zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        if (event.entity !is TNTPrimed && event.entity !is ExplosiveMinecart) return
        val arena = protectedArena() ?: return
        if (isAllowed(arena, "tnt")) return
        event.blockList().removeIf { arena.region.contains(it.location) }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        val region = pistonProtectedRegion() ?: return // cheap exit: this fires for every piston on the server
        val moved = event.blocks + event.block.getRelative(event.direction) // pushed blocks + piston head
        if (pistonTouchesZone(region, event.block, moved, event.direction)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        val region = pistonProtectedRegion() ?: return
        // Retract directions aren't reported consistently across 1.8 builds, so check both sides.
        if (pistonTouchesZone(region, event.block, event.blocks, event.direction, event.direction.oppositeFace)) {
            event.isCancelled = true
        }
    }

    /** The zone where pistons are forbidden right now, if any. */
    private fun pistonProtectedRegion(): Region? {
        val arena = protectedArena() ?: return null
        return if (isAllowed(arena, "pistons")) null else arena.region
    }

    /** True when the piston, a moved block, or where a moved block ends up is in [region]. */
    private fun pistonTouchesZone(region: Region, piston: Block, moved: List<Block>, vararg directions: BlockFace): Boolean {
        return region.contains(piston.location) || moved.any { block ->
            region.contains(block.location) || directions.any { region.contains(block.getRelative(it).location) }
        }
    }

    // ------------------------------------------------------------------ //
    //  KOTH Arena: command lock and invincibility
    // ------------------------------------------------------------------ //

    /** Players in the KOTH Arena may only use `koth-arena.allowed-commands` (and /koth). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (!plugin.kothArena.isParticipant(player.uniqueId)) return
        if (player.hasPermission(BYPASS_PERMISSION)) return

        val allowed = plugin.configManager.config.getStringList("koth-arena.allowed-commands")
        if (!isAllowedCommand(event.message, allowed)) {
            event.isCancelled = true
            player.sendMessage(plugin.kothArena.prefixed("command-blocked"))
        }
    }

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

    /** The arena whose zone is protected right now: the running one, or a KOTH Arena event's. */
    private fun protectedArena(): Arena? = plugin.arenaManager.busyArena()

    /** Protection only applies to KOTH Arena events (`koth-arena.arena-protection`). */
    private fun isAllowed(arena: Arena, flag: String): Boolean {
        if (!plugin.kothArena.isEventArena(arena)) return true
        return plugin.configManager.config.getBoolean("koth-arena.arena-protection.$flag", true)
    }

    /** True if [location] is in the protected zone and any of [flags] is forbidden. */
    private fun denies(location: Location, vararg flags: String): Boolean {
        val arena = protectedArena() ?: return false
        if (!arena.region.contains(location)) return false
        return flags.any { !isAllowed(arena, it) }
    }

    /** [denies] for a player action: honours the bypass permission and tells the player. */
    private fun deniesPlayer(player: Player, location: Location, vararg flags: String): Boolean {
        if (!denies(location, *flags) || player.hasPermission(BYPASS_PERMISSION)) return false
        player.sendMessage(plugin.configManager.messages.get("protection.denied"))
        return true
    }

    companion object {
        const val BYPASS_PERMISSION = "koth.bypass"

        // Always usable in the arena, so /koth leave works whatever allowed-commands says
        private val ALWAYS_ALLOWED = listOf("koth", "koths")

        /**
         * Whether a typed command is on the allowed list: "msg" allows "/msg Bob hi", "f who" allows
         * "/f who x" but not "/f home"; "/essentials:msg" counts as "msg". Case-insensitive, whole words.
         */
        fun isAllowedCommand(message: String, allowed: List<String>): Boolean {
            val typed = normalize(message)
            return (allowed + ALWAYS_ALLOWED).map { normalize(it) }
                .any { it.isNotEmpty() && (typed == it || typed.startsWith("$it ")) }
        }

        private fun normalize(command: String): String {
            val words = command.trim().removePrefix("/").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return ""
            return (listOf(words[0].substringAfter(':')) + words.drop(1)).joinToString(" ")
        }
    }
}
