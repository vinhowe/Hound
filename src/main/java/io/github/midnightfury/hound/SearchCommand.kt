package io.github.midnightfury.hound

import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.Location.*;

class SearchCommand : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as Player
        val list: MutableList<String>
        val Location
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

    }
}