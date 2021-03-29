package cmds

import Hound
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import org.bukkit.entity.Player

@CommandAlias("target|x")
class TargetCommand(private val hound: Hound) : BaseCommand() {

    @Default
    @Syntax("<x> <z>")
    @CommandCompletion("@coords:x @coords:z")
    @CommandPermission("hound.target")
    @Description("Create a floating guide that will lead you to coordinates.")
    fun target(player: Player, @Flags("x") @Optional x: Double?, @Flags("z") @Optional z: Double?) {

        if (x == null || z == null) {
            hound.clearGuideForPlayer(player)
        } else {
            hound.createGuideForPlayer(player, x, z)
        }

    }

}