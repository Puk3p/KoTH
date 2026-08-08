package com.puk3p.koth.listeners

import com.puk3p.koth.KoTHPlugin
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerInteractEvent

class ProtectionListener(private val plugin: KoTHPlugin) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val activeArena = plugin.arenaManager.getActiveArena() ?: return

        if (!activeArena.playersInside.contains(player.uniqueId)) return
        if (player.hasPermission("koth.admin")) return

        val typePath = "types.${activeArena.type}.command-restrictions"
        if (!plugin.configManager.config.getBoolean("$typePath.enabled", false)) return

        val mode = plugin.configManager.config.getString("$typePath.mode", "BLACKLIST")?.uppercase() ?: "BLACKLIST"
        val commandList = plugin.configManager.config.getStringList("$typePath.blocked-commands").map { it.lowercase().removePrefix("/") }

        val rawMsg = event.message.substring(1).trim().lowercase()
        val parts = rawMsg.split("\\s+".toRegex())
        val baseCmd = parts[0]
        val fullCmd = if (parts.size > 1) "$baseCmd ${parts[1]}" else baseCmd

        val isMatched = commandList.any { it == baseCmd || it == fullCmd }

        val shouldBlock = if (mode == "WHITELIST") !isMatched else isMatched

        if (shouldBlock) {
            player.sendMessage(plugin.configManager.messages.get("tournament.command-blocked"))
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEnderpearl(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val item = event.item ?: return
        if (item.type != Material.ENDER_PEARL) return

        val activeArena = plugin.arenaManager.getActiveArena() ?: return
        if (!activeArena.region.contains(player.location)) return

        val isPearlBlocked = !plugin.configManager.config.getBoolean("types.${activeArena.type}.protections.enderpearls", true)
        if (isPearlBlocked && !player.hasPermission("koth.admin")) {
            player.sendMessage("§cEnderpearls are disabled inside this capture zone!")
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return

        val activeArena = plugin.arenaManager.getArenaAt(player.location) ?: return
        val disableFallDamage = !plugin.configManager.config.getBoolean("types.${activeArena.type}.protections.fall-damage", true)

        if (disableFallDamage) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.player.hasPermission("koth.admin")) return
        if (isProtectionEnabled(event.player, event.block.location, "block-breaking")) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.player.hasPermission("koth.admin")) return
        if (isProtectionEnabled(event.player, event.block.location, "block-placing")) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (event.player.hasPermission("koth.admin")) return
        if (isProtectionEnabled(event.player, event.blockClicked.location, "bucket-use")) {
            event.isCancelled = true
        }
    }

    private fun isProtectionEnabled(player: Player, location: org.bukkit.Location, key: String): Boolean {
        val arena = plugin.arenaManager.getArenaAt(location) ?: return false
        return plugin.configManager.config.getBoolean("types.${arena.type}.protections.$key", false)
    }
}