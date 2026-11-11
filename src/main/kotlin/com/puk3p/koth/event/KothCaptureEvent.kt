package com.puk3p.koth.event

import com.puk3p.koth.arena.Arena
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a player's capture timer reaches zero, before rewards are given.
 * Cancelling it denies the win for now: the KoTH keeps running and, if the player is
 * still alone in the zone, the event fires again on the next tick (once per second).
 */
class KothCaptureEvent(val arena: Arena, val capturer: Player) : Event(), Cancellable {
    private var isCancelled = false

    override fun isCancelled(): Boolean {
        return isCancelled
    }

    override fun setCancelled(cancel: Boolean) {
        this.isCancelled = cancel
    }

    override fun getHandlers(): HandlerList {
        return handlerList
    }

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
