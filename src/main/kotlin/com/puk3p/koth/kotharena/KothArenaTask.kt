package com.puk3p.koth.kotharena

import org.bukkit.scheduler.BukkitRunnable

/**
 * Drives a KOTH Arena event once per second (join countdown, "anyone left?" check, post-capture countdown).
 * Started by [KothArenaManager.openJoin], cancelled when the event ends.
 */
class KothArenaTask(private val manager: KothArenaManager) : BukkitRunnable() {
    override fun run() {
        manager.tick()
    }
}
