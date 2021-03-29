package cmds

import ExactMatchingContainersSearchResult
import Hound
import PartialMatchingContainersSearchResult
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.*
import data.PartialMatchModel
import org.bukkit.entity.Player

@CommandAlias("chound|cf")
class ChestSearchCommand(private val hound: Hound) : BaseCommand() {

    @Default
    @Syntax("<item>")
    @CommandCompletion("@items")
    @CommandPermission("hound.search.container")
    @Description("Highlight item in nearby containers.")
    fun search(player: Player, model: PartialMatchModel) {
        val radius = hound.searchRadius
        val result = if (model.exact != null && model.fuzzy.isEmpty()) {
            hound.highlightItemTypeForPlayer(model.exact, player)
        } else {
            hound.highlightPartialMatchingItemTypesForPlayer(model.exact, model.fuzzy.toList(), player)
        }

        val response = when (result) {
            is ExactMatchingContainersSearchResult -> {
                exactMatchesResultMessage(model.input, result)
            }
            is PartialMatchingContainersSearchResult -> {
                partialMatchesResultMessage(model.input, result)
            }
            null -> {
                "§4Couldn't find items matching '${model.input}' in a container within $radius ${if (radius == 1) "block" else "blocks"} of you."
            }
        }

        player.sendMessage(response)
    }


    private fun String.plural(count: Int, suffix: String) = if (count == 1) this else "$this$suffix"


    private fun exactMatchesResultMessage(displayName: String,
                                          searchResult: ExactMatchingContainersSearchResult): String {
        val match = "match".plural(searchResult.itemCount, "es")
        val chest = "chest".plural(searchResult.matches.size, "s")

        return "§6Found ${searchResult.itemCount} $match for '$displayName' in ${searchResult.matches.size} $chest"
    }

    private fun partialMatchesResultMessage(displayName: String,
                                            searchResult: PartialMatchingContainersSearchResult): String {
        return buildString {
            append("§6Found ")

            val exactMatchCount = searchResult.exactMatchItemCount
            val fuzzyMatchCount = searchResult.partialMatchItemCount
            val totalChestCount = searchResult.exactMatches.size + searchResult.partialMatches.size

            val hasExact = exactMatchCount > 0
            val hasFuzzy = fuzzyMatchCount > 0

            if (hasExact) {
                append("$exactMatchCount exact ${"match".plural(exactMatchCount, "es")}")

                if (hasFuzzy) {
                    append(" and ")
                }
            }

            if (hasFuzzy) {
                append("$fuzzyMatchCount partial ${"match".plural(fuzzyMatchCount, "es")}")
            }

            append(" for '$displayName' in $totalChestCount ${"chest".plural(totalChestCount, "s")}")
        }
    }

}