import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.lang.NumberFormatException

class TargetCommand(val hound: Hound) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§4You must be an in-game player to use this command.")
            return true
        }
        if (!sender.hasPermission("hound.target")) {
            sender.sendMessage("§4You do not have access to that command.")
            return true
        }

        if (args.isEmpty()) {
            hound.clearGuideForPlayer(sender)
            return true
        }

        if (args.size != 2) {
            return false
        }

        val x: Double
        val z: Double

        try {
            x = args[0].toDouble()
            z = args[1].toDouble()
        } catch (_: NumberFormatException) {
            return false
        }

        hound.createGuideForPlayer(sender, x, z)
        return true
    }
}