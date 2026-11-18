package com.puk3p.koth.pvppoints

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.event.KothCaptureEvent
import com.puk3p.koth.hooks.FactionRelation
import com.puk3p.koth.util.formatDuration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID
import kotlin.math.floor

/**
 * Where pvp-points come from (config.yml -> pvp-points):
 * - a kill gives the killer `kill-reward`, unless anti-farm blocks it (same IP, same faction,
 *   allies, or the same victim again within `same-victim-cooldown`);
 * - dying costs `death-penalty` (a fixed amount plus a percent of the balance), only for deaths
 *   that count, so a teammate's kill or an alt's farm costs nothing;
 * - a KoTH capture gives the capturer `types.<type>.rewards.pvp-points`.
 * Settings are read on every use, so /koth reload applies them.
 */
class PvpPointsListener(
    private val plugin: KoTHPlugin,
    private val store: PvpPointsStore,
) : Listener {
    private val config get() = plugin.configManager.config

    // When each killer last got points for each victim (main thread only)
    private val lastCountedKill = HashMap<Pair<UUID, UUID>, Long>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        store.track(player.uniqueId, player.name, config.getInt("$ROOT.starting-points", 0))
    }

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

        if (reward > 0) {
            val balance = store.add(killer.uniqueId, killer.name, reward, PointsReason.KILL, victim.name)
            killer.sendMessage(
                pvpMessage(
                    plugin,
                    "kill-reward",
                    "%points%" to reward.toString(),
                    "%victim%" to victim.name,
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
        val balance = store.add(player.uniqueId, player.name, amount, PointsReason.CAPTURE, event.arena.name)
        player.sendMessage(
            pvpMessage(
                plugin,
                "capture-reward",
                "%points%" to amount.toString(),
                "%arena%" to event.arena.name,
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
        when (plugin.factionsHook?.relationBetween(killer, victim)) {
            FactionRelation.SAME_FACTION ->
                if (config.getBoolean("$antiFarm.ignore-same-faction", true)) {
                    return pvpMessage(plugin, "farm-same-faction", "%victim%" to victim.name)
                }
            FactionRelation.ALLY ->
                if (config.getBoolean("$antiFarm.ignore-allies", true)) {
                    return pvpMessage(plugin, "farm-ally", "%victim%" to victim.name)
                }
            FactionRelation.NONE, null -> Unit
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
        val balance = store.points(victim.uniqueId)
        if (balance <= 0) return
        val fixed = config.getInt("$ROOT.death-penalty.amount", 5).coerceAtLeast(0)
        val percent = config.getDouble("$ROOT.death-penalty.percent", 0.0).coerceIn(0.0, 100.0)
        val lost = minOf(balance.toLong(), fixed + floor(balance * percent / 100).toLong()).toInt()
        if (lost <= 0) return

        val newBalance = store.add(victim.uniqueId, victim.name, -lost, PointsReason.DEATH, killer?.name)
        victim.sendMessage(
            pvpMessage(
                plugin,
                if (killer != null) "death-penalty" else "death-penalty-no-killer",
                "%points%" to lost.toString(),
                "%killer%" to (killer?.name ?: ""),
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
