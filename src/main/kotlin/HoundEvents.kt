import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect

import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerChangedWorldEvent
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

        if ((event.action == Action.RIGHT_CLICK_BLOCK && event.clickedBlock?.state !is BlockInventoryHolder) && event.action != Action.LEFT_CLICK_BLOCK) {
            return
        }

        hound.clearStaticHighlightsForPlayer(event.player)
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
                        if (hound.highlightItemTypeForPlayer(material, player) == null) {
                            hound.clearStaticHighlightsForPlayer(player)
                        }
                    })
                }
                while (true) {
                    delay(1000)
                    if (player.uniqueId !in hound.liveSearchSet) {
                        cancel()
                    }
                }
            }

            if (hound.highlightItemTypeForPlayer(
                    player.inventory.itemInMainHand.type,
                    player
                ) == null
            ) {
                hound.clearStaticHighlightsForPlayer(player)
            }
        }

        GlobalScope.launch {
            hound.liveSearchEventFlows[player.uniqueId]?.emit(player.inventory.itemInMainHand.type)
        }
    }

    @EventHandler
    fun onPlayerDisconnect(event: PlayerQuitEvent) {
        hound.clearLiveSearchForPlayer(event.player)
        hound.clearStaticHighlightsForPlayer(event.player)
        hound.clearGuideForPlayer(event.player)
    }

    @EventHandler
    fun onPlayerChangedWorld(event: PlayerChangedWorldEvent) {
        hound.clearLiveSearchForPlayer(event.player)
        hound.clearStaticHighlightsForPlayer(event.player)
        hound.clearGuideForPlayer(event.player)
    }
}
