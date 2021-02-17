import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class SearchCommand(private val hound: Hound) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cYou must be an in-game player to use this command.")
            return true
        }
        if (!sender.hasPermission("hound.search")) {
            sender.sendMessage("§cYou do not have access to that command.")
            return true
        }
        if (args.size > 1) {
            return false
        }

        val materials: MutableList<Material> = mutableListOf()
        if (args.size == 1) {
            // Search for all items whose names contain the argument string
            materials += Material.values().filter { it.name.contains(args[0], true) }
        } else {
            if (sender.inventory.itemInMainHand.type.isAir) {
                sender.sendMessage("§cCouldn't find anything in selected slot.")
                return true
            }
            materials += sender.inventory.itemInMainHand.type
        }

        val radius = hound.searchRadius
        if (!hound.highlightItemTypesForPlayer(materials, sender)) {
            sender.sendMessage(
                "§cCouldn't find items matching '${
                    if (args.size == 1) args[0].toLowerCase() else sender.inventory.itemInMainHand.type.toString()
                        .toLowerCase()
                }' in a container within $radius ${if (radius == 1) "block" else "blocks"} of you."
            )
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        val suggestions = mutableListOf<String>()
        if (args.size == 1) {
            for (material in Material.values()) {
                suggestions.add(material.toString().toLowerCase())
            }
            return suggestions
        }
        return emptyList()
    }
}
