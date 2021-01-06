package io.github.midnightfury.hound

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.block.Chest
import org.bukkit.entity.EntityType
import java.util.*

class FindChestsCommand(private val hound: Hound) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("§cYou must be an in-game player to use this command.")
            return true
        }
        if(!sender.hasPermission("hound.search")) {
            sender.sendMessage("§cYou do not have access to that command.")
            return true
        }
        if (args.size > 1) {
            return false
        }
        val material : Material
        if(args.size == 1) {
            if (enumValues<Material>().none { it.name == args[0].toUpperCase() }) {
                sender.sendMessage("§c'${args[0].toLowerCase()}' is not a valid item type.")
                return true
            }
            material = Material.valueOf(args[0].toUpperCase())
        } else {
            material = sender.inventory.itemInMainHand.type
            if(material.isAir) {
                sender.sendMessage("§cCouldn't find anything in selected slot.")
                return true
            }
        }

        val radius = 15;
        val chests = getChestsInRadius(sender.location, radius)
        val filteredChests = filterChestsByMaterial(chests, material)
        if (filteredChests.isEmpty()) {
            sender.sendMessage("§cCouldn't find '${material.toString().toLowerCase()}' in a chest within $radius blocks of you.")
            return true
        }
        hound.clearChestHighlightsForPlayer(sender)
        for (chest in filteredChests) {
            val loc = chest.location
            highlightBlock(sender, loc)
        }
        return true
    }


    fun filterChestsByMaterial(chests: List<Chest>, material: Material): List<Chest> {
        return chests.filter { it.blockInventory.contains(material) }
    }

    fun getChestsInRadius(start: Location, radius: Int): List<Chest> {
        val chests = mutableListOf<Chest>()

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val blockLocation =
                        Location(start.world, start.x + x, start.y + y, start.z + z)

                    if (blockLocation.block.type != Material.CHEST) {
                        continue
                    }

                    chests.add(blockLocation.block.state as Chest)
                }
            }
        }

        return chests
    }

    fun highlightBlock(player: Player, location: Location) {
        val spawnStoneButton = hound.protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY)
        val uuid = UUID.randomUUID()
        val rand = (Math.random() * Integer.MAX_VALUE).toInt()
        val playerUuid = player.uniqueId
        if(!hound.playerHighlightMap.containsKey(playerUuid)) {
            hound.playerHighlightMap[playerUuid] = mutableListOf()
        }
        hound.playerHighlightMap[playerUuid]?.add(rand)
        spawnStoneButton.integers
            .write(0, rand) // EID
            .write(1, 0) // speed x
            .write(2, 0) // speed y
            .write(3, 0) // speed z
            .write(4, 0) // pitch
            .write(5, 0) // yaw
             // types:
             // spruce trapdoor 4182
             // stone button 3914
            .write(6, 0) // data (block type, 77 is a stone button)
        spawnStoneButton.uuiDs.write(0, uuid)
        // location
        spawnStoneButton.doubles.write(0, location.x+0.5).write(1, location.y+0.21).write(2, location.z+0.5)
        spawnStoneButton.entityTypeModifier.write(0, EntityType.SHULKER_BULLET)
        val glowMeta = hound.protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA)
        glowMeta.integers.write(0, rand)
        val watcher = WrappedDataWatcher()
        val serializer = WrappedDataWatcher.Registry.get(Byte::class.javaObjectType)
        watcher.setObject(0, serializer, (0x40 or 0x20).toByte())
        val noGravity = WrappedDataWatcher.WrappedDataWatcherObject(
            5,
            WrappedDataWatcher.Registry.get(Boolean::class.javaObjectType)
        )
        watcher.setObject(noGravity, true);
        glowMeta.watchableCollectionModifier.write(0, watcher.watchableObjects)
        hound.protocolManager.sendServerPacket(player, spawnStoneButton)
        hound.protocolManager.sendServerPacket(player, glowMeta)
        Bukkit.getServer().scheduler.scheduleSyncDelayedTask(hound, {
            val destroyHighlighter = hound.protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY)
            destroyHighlighter.integerArrays.write(0, listOf(rand).toIntArray())
            hound.protocolManager.sendServerPacket(player, destroyHighlighter)
            hound.playerHighlightMap[playerUuid]?.remove(rand)
        }, 20 * 20L)
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
                suggestions.add(material.toString().toLowerCase())
            }
            return suggestions
        }
        return mutableListOf()

    }
}