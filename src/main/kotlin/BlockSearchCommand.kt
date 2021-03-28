import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class BlockSearchCommand(val hound: Hound) : TabExecutor {
    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        return if (args.size == 1) BLOCKS else emptyList()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§4You must be an in-game player to use this command.")
            return true
        }
        if (!sender.hasPermission("hound.search.block")) {
            sender.sendMessage("§4You do not have access to that command.")
            return true
        }
        if (args.size != 1) {
            return false
        }

        val material: Material
        try {
            material = Material.values().first { it.name.equals(args[0], true) }
        } catch (e: NoSuchElementException) {
            sender.sendMessage("§4'${args[0].toLowerCase()}' is not a valid item type.")
            return true
        }

        val radius = hound.searchRadius
        val highlightedBlocks = hound.highlightBlockTypeForPlayer(material, sender)

        if (highlightedBlocks.isEmpty()) {
            sender.sendMessage(
                "§4Couldn't find any blocks of type '${args[0].toLowerCase()}' within $radius ${if (radius == 1) "block" else "blocks"} of you."
            )
            return true
        }

        sender.sendMessage(
            "§6Found ${highlightedBlocks.size} ${
                if (highlightedBlocks.size == 1) {
                    "block"
                } else {
                    "blocks"
                }
            } of type '${args[0].toLowerCase()}'"
        )

        return true
    }
    
    
    private companion object {
        val BLOCKS = Material.values().filter(Material::isBlock).map(Material::name).map(String::toLowerCase)
    }
    
}