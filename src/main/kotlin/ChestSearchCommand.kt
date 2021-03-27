import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import kotlin.IllegalStateException

class ChestSearchCommand(private val hound: Hound) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§4You must be an in-game player to use this command.")
            return true
        }
        if (!sender.hasPermission("hound.search.container")) {
            sender.sendMessage("§4You do not have access to that command.")
            return true
        }
        if (args.size > 1) {
            return false
        }

        val materials: MutableList<Material> = mutableListOf()
        var exactMatchMaterial: Material? = null
        if (args.size == 1) {
            // Search for all items whose names contain the argument string
            Material.values().forEach {
                if (it.name.equals(args[0], true)) {
                    exactMatchMaterial = it
                } else if (it.name.contains(args[0], true)) {
                    materials += it
                }
            }
        } else {
            if (sender.inventory.itemInMainHand.type.isAir) {
                sender.sendMessage("§4Couldn't find anything in selected slot.")
                return true
            }
            exactMatchMaterial = sender.inventory.itemInMainHand.type
        }

        val radius = hound.searchRadius
        val itemDisplayName =
            if (args.size == 1) args[0].toLowerCase() else sender.inventory.itemInMainHand.type.toString().toLowerCase()

        val searchResult = if (args.size == 1) {
            hound.highlightPartialMatchingItemTypesForPlayer(exactMatchMaterial, materials, sender)
        } else {
            hound.highlightItemTypeForPlayer(exactMatchMaterial!!, sender)
        }

        if (searchResult == null) {
            sender.sendMessage(
                "§4Couldn't find items matching '$itemDisplayName' in a container within $radius ${if (radius == 1) "block" else "blocks"} of you."
            )
            return true
        }

        val resultMessage = when (searchResult) {
            is ExactMatchingContainersSearchResult -> exactMatchesResultMessage(searchResult, itemDisplayName)
            is PartialMatchingContainersSearchResult -> partialMatchesResultMessage(searchResult, itemDisplayName)
            else -> {
                sender.sendMessage(
                    "§4Something went wrong. This is a bug."
                )
                throw IllegalStateException()
            }
        }

        sender.sendMessage("§6$resultMessage")

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

    private fun partialMatchesResultMessage(
        searchResult: PartialMatchingContainersSearchResult,
        displayName: String
    ): String {
        var exactMatchesString = ""
        if (searchResult.exactMatchItemCount > 0) {
            val matchesPluralized = if (searchResult.exactMatchItemCount == 1) {
                "match"
            } else {
                "matches"
            }
            exactMatchesString += "${searchResult.exactMatchItemCount} exact $matchesPluralized"
        }

        var partialMatchesString = ""
        if (searchResult.partialMatchItemCount > 0) {
            if (exactMatchesString.isNotEmpty()) {
                partialMatchesString = " and "
            }
            val matchesPluralized = if (searchResult.partialMatchItemCount == 1) {
                "match"
            } else {
                "matches"
            }
            partialMatchesString +=
                "${searchResult.partialMatchItemCount} partial $matchesPluralized"
        }

        val totalChests = searchResult.exactMatches.size + searchResult.partialMatches.size
        val chestsPluralized = if (totalChests == 1) {
            "chest"
        } else {
            "chests"
        }

        return "Found $exactMatchesString$partialMatchesString for '$displayName' in $totalChests $chestsPluralized"
    }

    private fun exactMatchesResultMessage(
        searchResult: ExactMatchingContainersSearchResult,
        displayName: String
    ): String {
        val matchesPluralized = if (searchResult.itemCount == 1) {
            "match"
        } else {
            "matches"
        }
        val chestsPluralized = if (searchResult.matches.size == 1) {
            "chest"
        } else {
            "chests"
        }
        return "Found ${searchResult.itemCount} $matchesPluralized for '$displayName' in ${searchResult.matches.size} $chestsPluralized"
    }
}