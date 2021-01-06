package io.github.midnightfury.hound

import com.comphenix.protocol.PacketType
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent

class Events(private val hound: Hound) : Listener {
    @EventHandler
    fun onPlayerInteractEvent(event: PlayerInteractEvent) {
        if (!event.hasBlock()) {
            return
        }
        val player = event.player
        if (event.clickedBlock?.type != Material.CHEST) {
            return
        }
        hound.clearChestHighlightsForPlayer(player)
    }
}