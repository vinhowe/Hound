package io.github.midnightfury.hound

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.Location.*;
import org.bukkit.block.Block
import java.util.ArrayList




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

    fun getChestsInRadius(start: Block, radius: Int): List<Chest> {
        val startLocation = start.location
        val chests = mutableListOf<Chest>()

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val blockLocation =
                        Location(start.world, startLocation.x + x, startLocation.y + y, startLocation.z + z)

                    if (blockLocation.block.type != Material.CHEST) {
                        continue
                    }

                    chests.add(blockLocation.block as Chest)
                }
            }
        }

        return chests
    }
}