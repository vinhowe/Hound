import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class LiveSearchCommand(private val hound: Hound): TabExecutor {
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size == 1) {
            return mutableListOf("on", "off")
        }
        return emptyList()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cYou must be an in-game player to use this command.")
            return true
        }
        if (!sender.hasPermission("hound.livesearch")) {
            sender.sendMessage("§cYou do not have access to that command.")
            return true
        }
        if (args.size > 1) {
            return false
        }
        if (args.isNotEmpty() && args[0].toLowerCase() !in listOf("on", "off")) {
            return false
        }

        val liveSearchMode = hound.liveSearchSet.contains(sender.uniqueId)

        if (liveSearchMode) {
            if (args.isNotEmpty() && args[0].toLowerCase() == "on") {
                sender.sendMessage("§cLive search mode is already active.")
                return true
            }

            hound.liveSearchSet.remove(sender.uniqueId)
            hound.clearContainerHighlightsForPlayer(sender)
            sender.sendMessage("§6Deactivated live search mode.")

            return true
        }

        if (args.isNotEmpty() && args[0].toLowerCase() == "off") {
            sender.sendMessage("§cLive search mode is already inactive.")
            return true
        }

        hound.highlightItemTypeForPlayer(sender.inventory.itemInMainHand.type, sender)
        hound.liveSearchSet.add(sender.uniqueId)
        sender.sendMessage("§6Activated live search mode.")
        return true
    }
}
