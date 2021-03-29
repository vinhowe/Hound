package cmds

import Hound
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.entity.Player

@CommandAlias("torchguide|tg")
class TorchGuideCommand(private val hound: Hound) : BaseCommand() {

    @Default
    @Syntax("<spacing>")
    @CommandCompletion("@range:1-5")
    @CommandPermission("hound.torchguide")
    @Description("Highlight a grid guide for torches")
    fun guide(player: Player, @Optional spacing: Int?) {

        if (spacing == null) {
            hound.clearStaticHighlightsForPlayer(player)
        } else {
            hound.createTorchHighlightsForPlayer(player, spacing)
        }

    }

}