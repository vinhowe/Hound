import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.*

class Hound : JavaPlugin() {
    val playerHighlightMap = mutableMapOf<UUID, MutableList<Int>>()
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
        // Plugin startup logic
        protocolManager = ProtocolLibrary.getProtocolManager()
        saveDefaultConfig()
        val findChestsCommand = FindContainersCommand(this)
        getCommand("hound")?.setExecutor(findChestsCommand)
        getCommand("hound")?.tabCompleter = findChestsCommand
        server.pluginManager.registerEvents(Events(this), this)
    }

    override fun onDisable() {
        // Plugin shutdown logic
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
}