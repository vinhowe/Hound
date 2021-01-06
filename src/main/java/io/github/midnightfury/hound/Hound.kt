package io.github.midnightfury.hound;

import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.ProtocolManager
import org.bukkit.plugin.java.JavaPlugin;

class Hound: JavaPlugin() {
    lateinit var protocolManager: ProtocolManager

    override fun onEnable() {
        // Plugin startup logic
        protocolManager = ProtocolLibrary.getProtocolManager();
        getCommand("buttonglow")?.setExecutor(SearchCommand(this))

    }

    override fun onDisable() {
        // Plugin shutdown logic
    }
}
