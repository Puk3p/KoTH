package com.puk3p.koth.config

import com.puk3p.koth.KoTHPlugin
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.IOException
import java.util.logging.Level

class ConfigManager(private val plugin: KoTHPlugin) {

    lateinit var config: FileConfiguration
        private set
    lateinit var arenasConfig: FileConfiguration
        private set
    lateinit var messagesConfig: FileConfiguration
        private set

    lateinit var messages: Messages
        private set

    fun loadAll() {
        if (!plugin.dataFolder.exists()) {
            plugin.dataFolder.mkdirs()
        }

        // 1. Load Main Config (Settings, KoTH Types)
        config = loadYamlFile("config.yml")

        // 2. Load Messages
        messagesConfig = loadYamlFile("messages.yml")
        messages = Messages(messagesConfig)

        // 3. Load Arenas (Data storage)
        arenasConfig = loadYamlFile("arenas.yml")
    }

    /**
     * Saves changes made to arenas.yml in memory back to the disk.
     * Used when an admin does /koth create or /koth pos1
     */
    fun saveArenas() {
        val arenasFile = File(plugin.dataFolder, "arenas.yml")
        try {
            arenasConfig.save(arenasFile)
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Could not save arenas.yml!", e)
        }
    }

    /**
     * Reloads all configurations from disk.
     */
    fun reload() {
        loadAll()
        plugin.arenaManager.loadArenas()
        plugin.schedulerManager.loadSchedules()
        plugin.logger.info("Configurations reloaded successfully.")
    }

    /**
     * Helper method to load a YAML file, creating it from defaults if missing.
     */
    private fun loadYamlFile(fileName: String): FileConfiguration {
        val file = File(plugin.dataFolder, fileName)
        if (!file.exists()) {
            plugin.logger.info("Generating default $fileName...")
            plugin.saveResource(fileName, false)
        }
        return YamlConfiguration.loadConfiguration(file)
    }
}