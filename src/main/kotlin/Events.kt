import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.BlockInventoryHolder

class Events(private val hound: Hound) : Listener {
    @EventHandler
    fun onPlayerInteractEvent(event: PlayerInteractEvent) {
        if (!event.hasBlock()) {
            return
        }
        val player = event.player
        if (event.clickedBlock?.state !is BlockInventoryHolder) {
            return
        }
        hound.clearContainerHighlightsForPlayer(player)
    }
}