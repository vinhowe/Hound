import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import com.comphenix.protocol.wrappers.WrappedDataWatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.plugin.java.JavaPlugin
import java.util.*

class Hound : JavaPlugin() {
    private val playerHighlightMap = mutableMapOf<UUID, MutableList<Int>>()
    val liveSearchSet = mutableSetOf<UUID>()
    val liveSearchEventFlows = mutableMapOf<UUID, MutableSharedFlow<Material>>()
    lateinit var protocolManager: ProtocolManager
    val searchRadius: Int
        get() {
            return (config.get("search-radius") as Int?)!!
        }
    val highlightDuration: Number
        get() {
            return (config.get("highlight-duration") as Number?)!!
        }

    override fun onEnable() {
        protocolManager = ProtocolLibrary.getProtocolManager()
        saveDefaultConfig()
        val searchCommand = SearchCommand(this)
        val liveSearchCommand = LiveSearchCommand(this)
        getCommand("hound")?.setExecutor(searchCommand)
        getCommand("hound")?.tabCompleter = searchCommand
        getCommand("lhound")?.setExecutor(liveSearchCommand)
        getCommand("lhound")?.tabCompleter = liveSearchCommand
        server.pluginManager.registerEvents(HoundEvents(this), this)
    }

    fun clearContainerHighlightsForPlayer(player: Player) {
        if (!(playerHighlightMap.containsKey(player.uniqueId)) || playerHighlightMap[player.uniqueId]?.isEmpty() != false) {
            return
        }
        val destroyHighlighter = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY)
        destroyHighlighter.integerArrays.write(0, playerHighlightMap[player.uniqueId]?.toIntArray())
        protocolManager.sendServerPacket(player, destroyHighlighter)
        playerHighlightMap[player.uniqueId]?.clear()
    }

    fun highlightBlock(player: Player, location: Location, duration: Double?) {
        val playerUuid = player.uniqueId

        if (!playerHighlightMap.containsKey(playerUuid)) {
            playerHighlightMap[playerUuid] = mutableListOf()
        }

        val highlightEntityId = (Math.random() * Integer.MAX_VALUE).toInt()
        val highlightUuid = UUID.randomUUID()

        playerHighlightMap[playerUuid]?.add(highlightEntityId)

        val spawnHighlightPacket = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY)
        spawnHighlightPacket.integers
            .write(0, highlightEntityId) // EID
            .write(1, 0) // speed x
            .write(2, 0) // speed y
            .write(3, 0) // speed z
            .write(4, 0) // pitch
            .write(5, 0) // yaw
            .write(6, 0) // data
        spawnHighlightPacket.uuiDs.write(0, highlightUuid)
        // location
        spawnHighlightPacket.doubles.write(0, location.x + 0.5).write(1, location.y + 0.21).write(2, location.z + 0.5)
        spawnHighlightPacket.entityTypeModifier.write(0, EntityType.SHULKER_BULLET)

        val setHighlightGlowingPacket = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA)
        setHighlightGlowingPacket.integers.write(0, highlightEntityId)
        val watcher = WrappedDataWatcher()
        val serializer = WrappedDataWatcher.Registry.get(Byte::class.javaObjectType)
        watcher.setObject(0, serializer, (0x40 or 0x20).toByte())
        val noGravity = WrappedDataWatcher.WrappedDataWatcherObject(
            5,
            WrappedDataWatcher.Registry.get(Boolean::class.javaObjectType)
        )
        watcher.setObject(noGravity, true)
        setHighlightGlowingPacket.watchableCollectionModifier.write(0, watcher.watchableObjects)

        protocolManager.sendServerPacket(player, spawnHighlightPacket)
        protocolManager.sendServerPacket(player, setHighlightGlowingPacket)

        if (duration == null) {
            return
        }

        Bukkit.getServer().scheduler.scheduleSyncDelayedTask(this, {
            val destroyHighlighter = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY)
            destroyHighlighter.integerArrays.write(0, listOf(highlightEntityId).toIntArray())
            protocolManager.sendServerPacket(player, destroyHighlighter)
            playerHighlightMap[playerUuid]?.remove(highlightEntityId)
        }, (duration * 20).toLong())
    }

    private fun filterContainersByMaterial(
        containers: List<BlockInventoryHolder>,
        material: Material
    ): List<BlockInventoryHolder> {
        return containers.filter { it.inventory.contains(material) }
    }

    private fun getContainersInRadius(start: Location, radius: Int): List<BlockInventoryHolder> {
        val containers = mutableListOf<BlockInventoryHolder>()

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val blockLocation =
                        Location(start.world, start.x + x, start.y + y, start.z + z)
                    val blockState = blockLocation.block.state

                    if (blockState !is BlockInventoryHolder) {
                        continue
                    }

                    containers.add(blockState)
                }
            }
        }

        return containers
    }

    fun highlightItemTypeForPlayer(
        material: Material,
        player: Player,
        radius: Int = searchRadius,
        duration: Double? = highlightDuration.toDouble()
    ): Boolean {
        if (material == Material.AIR) {
            return false
        }

        val containers = getContainersInRadius(player.location, radius)
        val filteredContainers = filterContainersByMaterial(containers, material)

        if (filteredContainers.isEmpty()) {
            return false
        }

        clearContainerHighlightsForPlayer(player)
        for (container in filteredContainers) {
            val loc = container.block.location
            highlightBlock(player, loc, duration)
        }
        return true
    }
}