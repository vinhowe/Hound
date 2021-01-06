package io.github.midnightfury.hound

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import com.comphenix.protocol.wrappers.WrappedDataWatcher.WrappedDataWatcherObject
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import java.util.*
import kotlin.collections.ArrayList

class SearchCommand(val hound: Hound) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as Player
        val list: MutableList<String>
        val location = player.location;
        val blockData = Material.STONE_BUTTON.createBlockData("[face=ceiling]");
        val spawnStoneButton = hound.protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY)
        val uuid = UUID.randomUUID()
        val rand = (Math.random() * Integer.MAX_VALUE).toInt()
        spawnStoneButton.integers
            .write(0, rand) // EID
            .write(1, 0) // speed x
            .write(2, 0) // speed y
            .write(3, 0) // speed z
            .write(4, 0) // pitch
            .write(5, 0) // yaw
            .write(6, 3914) // data (block type, 77 is a stone button)
        spawnStoneButton.uuiDs.write(0, uuid)
        // location
        spawnStoneButton.doubles.write(0, location.x).write(1, location.y).write(2, location.z)
        spawnStoneButton.entityTypeModifier.write(0, EntityType.FALLING_BLOCK)
        val glowMeta = hound.protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA)
        glowMeta.integers.write(0,rand)
        val watcher = WrappedDataWatcher()
        val serializer = WrappedDataWatcher.Registry.get(Byte::class.javaObjectType)
        watcher.setObject(0, serializer, (0x40).toByte())
        val noGravity = WrappedDataWatcherObject(5, WrappedDataWatcher.Registry.get(Boolean::class.javaObjectType))
        watcher.setObject(noGravity, true);
        glowMeta.watchableCollectionModifier.write(0, watcher.watchableObjects)
        hound.protocolManager.sendServerPacket(player, spawnStoneButton)
        hound.protocolManager.sendServerPacket(player, glowMeta)
        Bukkit.getServer().scheduler.scheduleSyncDelayedTask(hound, {
            val destroyHighlighter = hound.protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY)
            destroyHighlighter.integerArrays.write(0, listOf(rand).toIntArray())
            hound.protocolManager.sendServerPacket(player, destroyHighlighter)
        }, 1 * 20L)
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