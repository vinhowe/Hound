package cmds

import Hound
import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandCompletion
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Default
import co.aikar.commands.annotation.Description
import co.aikar.commands.annotation.Syntax
import org.bukkit.Material
import org.bukkit.entity.Player

@CommandAlias("bhound|bf")
data class BlockSearchCommand(private val hound: Hound) : BaseCommand() {
	
	@Default
	@Syntax("<block>")
	@CommandCompletion("@blocks")
	@CommandPermission("hound.search.block")
	@Description("Highlight nearby block types.")
	fun search(player: Player, material: Material) {
		
		val radius = hound.searchRadius
		
		val blocks = hound.highlightBlockTypeForPlayer(material, player)
		
		
		if (blocks.isNotEmpty()) {
			player.sendMessage("§6Found ${blocks.size} ${if (blocks.size == 1) "block" else "blocks"} of type '${material.name.toLowerCase()}'")
		} else {
			player.sendMessage("§4Couldn't find any blocks of type '${material.name.toLowerCase()}' within $radius ${if (radius == 1) "block" else "blocks"} of you.")
		}
		
	}
	
}