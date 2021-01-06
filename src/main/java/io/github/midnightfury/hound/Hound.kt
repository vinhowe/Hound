package io.github.midnightfury.hound;

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*

class Hound : JavaPlugin() {
    val playerHighlightMap = mutableMapOf<UUID, MutableList<Int>>()
    lateinit var protocolManager: ProtocolManager

    override fun onEnable() {
        // Plugin startup logic
        protocolManager = ProtocolLibrary.getProtocolManager();
        val findChestsCommand = FindChestsCommand(this);
        getCommand("hound")?.setExecutor(findChestsCommand)
        getCommand("hound")?.tabCompleter = findChestsCommand
        server.pluginManager.registerEvents(Events(this), this)
    }

    override fun onDisable() {
        // Plugin shutdown logic
    }

    fun clearChestHighlightsForPlayer(player: Player) {
        if (!(playerHighlightMap.containsKey(player.uniqueId)) || playerHighlightMap[player.uniqueId]?.isEmpty() != false) {
            return
        }
        val destroyHighlighter = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY)
        destroyHighlighter.integerArrays.write(0, playerHighlightMap[player.uniqueId]?.toIntArray())
        protocolManager.sendServerPacket(player, destroyHighlighter)
        playerHighlightMap[player.uniqueId]?.clear()
    }
}