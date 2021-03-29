package cmds

import Hound
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import data.LiveSearchState
import org.bukkit.entity.Player

@CommandAlias("lchound|lcf")
class LiveChestSearchCommand(private val hound: Hound) : BaseCommand() {


    @Default
    @Syntax("<on/off>")
    @CommandCompletion("@states")
    @CommandPermission("hound.search.container.live")
    @Description("Live search mode. While active, highlights item held in main hand in nearby containers.")
    fun search(player: Player, state: LiveSearchState) {

        if (state.currentState == state.desiredState) {
            return player.sendMessage("§4Live search mode is already ${if (state.currentState) "active" else "inactive"}.")
        }

        if (state.desiredState) {

            hound.liveSearchSet += player.uniqueId
            hound.highlightItemTypeForPlayer(player.inventory.itemInMainHand.type, player)

            player.sendMessage("§6Activated live search mode.")
        } else {

            hound.liveSearchSet -= player.uniqueId
            hound.clearStaticHighlightsForPlayer(player)

            player.sendMessage("§6Deactivated live search mode.")
        }

    }

}