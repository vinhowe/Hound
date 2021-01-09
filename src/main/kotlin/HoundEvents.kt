import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.BlockInventoryHolder

class HoundEvents(private val hound: Hound) : Listener {
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

    @FlowPreview
    @EventHandler
    fun onPlayerChangeMainSlotEvent(event: PlayerItemHeldEvent) {
        val player = event.player
        if (!hound.liveSearchSet.contains(player.uniqueId)) {
            return
        }

        if (!hound.liveSearchEventFlows.containsKey(player.uniqueId)) {
            val liveSearchFlow = MutableSharedFlow<Material>()
            hound.liveSearchEventFlows[player.uniqueId] = liveSearchFlow
            GlobalScope.launch {
                liveSearchFlow.collect { material ->
                    hound.server.scheduler.runTask(hound, Runnable {
                        if (!hound.highlightItemTypeForPlayer(
                                material,
                                player
                            )
                        ) {
                            hound.clearContainerHighlightsForPlayer(player)
                        }
                    })
                }
                while(true) {
                    delay(1000)
                    if (player.uniqueId !in hound.liveSearchSet) {
                        cancel()
                    }
                }
            }

            if (!hound.highlightItemTypeForPlayer(
                    player.inventory.itemInMainHand.type,
                    player
                )
            ) {
                hound.clearContainerHighlightsForPlayer(player)
            }
        }

        GlobalScope.launch {
            hound.liveSearchEventFlows[player.uniqueId]?.emit(player.inventory.itemInMainHand.type)
        }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        val player = event.player
        if (!hound.liveSearchSet.contains(player.uniqueId)) {
            return
        }

        hound.liveSearchSet.remove(player.uniqueId)
        hound.liveSearchEventFlows.remove(player.uniqueId)
    }
}