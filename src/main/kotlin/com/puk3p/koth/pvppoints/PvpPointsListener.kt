package com.puk3p.koth.pvppoints

import com.massivecraft.factions.event.FactionDisbandEvent
import com.massivecraft.factions.event.FactionRenameEvent
import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.event.KothCaptureEvent
import com.puk3p.koth.hooks.FactionRef
import com.puk3p.koth.hooks.FactionRelation
import com.puk3p.koth.hooks.FactionsHook
import com.puk3p.koth.util.formatDuration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID
import kotlin.math.floor

/**
 * Where faction points come from (config.yml -> pvp-points); every amount goes to or comes from a
 * faction, through the member who earned or lost it:
 * - a kill gives the killer's faction `kill-reward`, unless anti-farm blocks it (same IP, same
 *   faction, allies, or the same victim again within `same-victim-cooldown`);
 * - dying costs the victim's faction `death-penalty` (a fixed amount plus a percent of the
 *   faction's points), only for deaths that count, so a teammate's kill or an alt's farm costs nothing;
 * - a KoTH capture gives the capturer's faction `types.<type>.rewards.pvp-points`.
 * Players without a faction neither earn nor lose anything. Settings are read on every use, so
 * /koth reload applies them.
 */
class PvpPointsListener(
    private val plugin: KoTHPlugin,
    private val store: PvpPointsStore,
    private val factions: FactionsHook,
) : Listener {
    private val config get() = plugin.configManager.config

    // When each killer last got points for each victim (main thread only)
    private val lastCountedKill = HashMap<Pair<UUID, UUID>, Long>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        if (victim.world.name in config.getStringList("$ROOT.disabled-worlds")) return

        val killer = victim.killer?.takeIf { it.uniqueId != victim.uniqueId }
        if (killer == null) {
            if (!config.getBoolean("$ROOT.death-penalty.only-player-kills", true)) penalize(victim, null)
            return
        }

        val reward = config.getInt("$ROOT.kill-reward", 10)
        val blocked = farmReason(killer, victim)
        if (blocked != null) {
            if (reward > 0) killer.sendMessage(blocked)
            return
        }
        lastCountedKill[killer.uniqueId to victim.uniqueId] = System.currentTimeMillis()
        pruneCooldowns()

        val killerFaction = factions.factionOf(killer)
        if (reward > 0 && killerFaction != null) {
            val balance = store.add(killerFaction, reward, PointsReason.KILL, killer.name, victim.name)
            killer.sendMessage(
                pvpMessage(
                    plugin,
                    "kill-reward",
                    "%points%" to reward.toString(),
                    "%victim%" to victim.name,
                    "%faction%" to killerFaction.tag,
                    "%balance%" to balance.toString(),
                ),
            )
        }
        penalize(victim, killer)
    }

    // MONITOR + ignoreCancelled: only captures that really happen (a cancelled capture is retried later)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCapture(event: KothCaptureEvent) {
        val amount = config.getInt("types.${event.arena.type}.rewards.pvp-points", 0)
        if (amount <= 0) return
        val player = event.capturer
        val faction = factions.factionOf(player) ?: return
        val balance = store.add(faction, amount, PointsReason.CAPTURE, player.name, event.arena.name)
        player.sendMessage(
            pvpMessage(
                plugin,
                "capture-reward",
                "%points%" to amount.toString(),
                "%arena%" to event.arena.name,
                "%faction%" to faction.tag,
                "%balance%" to balance.toString(),
            ),
        )
    }

    /** The message to show the killer if this kill gives no points, or null if it counts. */
    private fun farmReason(
        killer: Player,
        victim: Player,
    ): String? {
        val antiFarm = "$ROOT.anti-farm"
        if (config.getBoolean("$antiFarm.ignore-same-ip", true) && sameIp(killer, victim)) {
            return pvpMessage(plugin, "farm-same-ip", "%victim%" to victim.name)
        }
        when (factions.relationBetween(killer, victim)) {
            FactionRelation.SAME_FACTION ->
                if (config.getBoolean("$antiFarm.ignore-same-faction", true)) {
                    return pvpMessage(plugin, "farm-same-faction", "%victim%" to victim.name)
                }
            FactionRelation.ALLY ->
                if (config.getBoolean("$antiFarm.ignore-allies", true)) {
                    return pvpMessage(plugin, "farm-ally", "%victim%" to victim.name)
                }
            FactionRelation.NONE -> Unit
        }
        val cooldownSeconds = config.getLong("$antiFarm.same-victim-cooldown", 600)
        val last = lastCountedKill[killer.uniqueId to victim.uniqueId]
        if (cooldownSeconds > 0 && last != null) {
            val left = cooldownSeconds - (System.currentTimeMillis() - last) / 1000
            if (left > 0) {
                return pvpMessage(plugin, "farm-cooldown", "%victim%" to victim.name, "%time%" to formatDuration(left))
            }
        }
        return null
    }

    private fun penalize(
        victim: Player,
        killer: Player?,
    ) {
        val faction = factions.factionOf(victim) ?: return
        val balance = store.points(faction.id)
        if (balance <= 0) return
        val fixed = config.getInt("$ROOT.death-penalty.amount", 5).coerceAtLeast(0)
        val percent = config.getDouble("$ROOT.death-penalty.percent", 0.0).coerceIn(0.0, 100.0)
        val lost = minOf(balance.toLong(), fixed + floor(balance * percent / 100).toLong()).toInt()
        if (lost <= 0) return

        val newBalance = store.add(faction, -lost, PointsReason.DEATH, victim.name, killer?.name)
        victim.sendMessage(
            pvpMessage(
                plugin,
                if (killer != null) "death-penalty" else "death-penalty-no-killer",
                "%points%" to lost.toString(),
                "%killer%" to (killer?.name ?: ""),
                "%faction%" to faction.tag,
                "%balance%" to newBalance.toString(),
            ),
        )
    }

    /** Behind BungeeCord without ip_forward every player has the proxy's IP; the config says to turn this off then. */
    private fun sameIp(
        a: Player,
        b: Player,
    ): Boolean {
        val ipA = a.address?.address?.hostAddress ?: return false
        val ipB = b.address?.address?.hostAddress ?: return false
        return ipA == ipB
    }

    private fun pruneCooldowns() {
        if (lastCountedKill.size < PRUNE_AT) return
        val cooldownMillis = config.getLong("$ROOT.anti-farm.same-victim-cooldown", 600) * 1000
        val now = System.currentTimeMillis()
        lastCountedKill.values.removeIf { now - it >= cooldownMillis }
    }

    private companion object {
        const val ROOT = PvpPointsModule.ROOT
        const val PRUNE_AT = 500
    }
}

/**
 * Keeps the stored tag in step when a faction renames itself (points are kept by Factions id, so
 * they follow the faction either way; the tag is what the top and placeholders show).
 * In its own class because not every Factions fork has this event.
 */
class PvpPointsRenameListener(
    private val plugin: KoTHPlugin,
    private val store: PvpPointsStore,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRename(event: FactionRenameEvent) {
        val faction = event.faction ?: return
        // The event fires before Factions applies the new tag, and the rename can still fail after it
        plugin.server.scheduler.runTask(
            plugin,
            Runnable {
                val tag = faction.tag?.trim().orEmpty()
                if (tag.isNotEmpty()) store.rename(faction.id, tag)
            },
        )
    }
}

/** A disbanded faction loses its points (config: pvp-points.delete-on-faction-disband). */
class PvpPointsDisbandListener(
    private val plugin: KoTHPlugin,
    private val store: PvpPointsStore,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDisband(event: FactionDisbandEvent) {
        if (!plugin.configManager.config.getBoolean("${PvpPointsModule.ROOT}.delete-on-faction-disband", true)) return
        val id = event.faction?.id ?: return
        val entry = store.entry(id)?.takeIf { it.points > 0 } ?: return
        // Who disbanded it, when there is someone (not for inactivity); older forks lack getPlayer()
        val by =
            try {
                event.player?.name
            } catch (e: LinkageError) {
                null
            }
        store.set(FactionRef(id, entry.tag), 0, PointsReason.DISBAND, by)
        plugin.logger.info("[PvpPoints] Faction '${entry.tag}' disbanded; removed its ${entry.points} point(s).")
    }
}
