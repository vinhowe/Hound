package io.github.midnightfury.hound

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.block.Chest


class SearchCommand : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as Player
        val list: MutableList<String>
        val location = player.location;
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): MutableList<String> {
        val suggestions = mutableListOf<String>()
        if (args.size == 1) {
            for (material in Material.values()) {
                suggestions.add(material.toString())
            }
            return suggestions
        }
        return mutableListOf()

    }
    fun getBlocks(start: Block, radius: Int): ArrayList<Block>? {
        val blocks = ArrayList<Block>()
        var x = start.location.x - radius
        while (x <= start.location.x + radius) {
            var y = start.location.y - radius
            while (y <= start.location.y + radius) {
                var z = start.location.z - radius
                while (z <= start.location.z + radius) {
                    val loc = Location(start.world, x, y, z)
                    blocks.add(loc.block)
                    z++
                }
                y++
            }
            x++
        }
        return blocks
    }
}