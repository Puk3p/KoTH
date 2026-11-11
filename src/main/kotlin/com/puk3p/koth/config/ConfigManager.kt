package com.puk3p.koth.config

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.util.ConfigLocations
import org.bukkit.Location
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
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

        // 2. Load Messages (missing keys fall back to the jar's messages.yml, so older files keep working)
        messagesConfig = loadYamlFile("messages.yml", withJarDefaults = true)
        messages = Messages(messagesConfig)

        // 3. Load Arenas (Data storage)
        arenasConfig = loadYamlFile("arenas.yml")
    }

    /**
     * Saves changes made to arenas.yml in memory back to the disk.
     * Used when an admin does /koth create, /koth delete or /koth setspawn.
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
     * Writes `<parent>.<child>` (e.g. koth-arena.arena-location) into config.yml and reloads it.
     * Edits only those lines, so the file's comments survive (Bukkit's save would strip them all).
     * @throws IOException if the file can't be read or written
     * @throws InvalidConfigurationException if the result wouldn't parse (nothing is written then)
     */
    fun saveLocation(parent: String, child: String, location: Location) {
        val file = File(plugin.dataFolder, "config.yml")
        val text = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        val updated = ConfigLocations.setBlock(text, parent, child, ConfigLocations.toYamlLines(location))

        val check = YamlConfiguration()
        check.loadFromString(updated)
        if (check.getString("$parent.$child.world") != location.world.name) {
            throw InvalidConfigurationException("$parent.$child did not read back after editing config.yml")
        }

        Files.write(file.toPath(), updated.toByteArray(Charsets.UTF_8))
        config = loadYamlFile("config.yml")
    }

    /**
     * Reloads the YAML files from disk. Rebuilding the arenas from arenas.yml is up to the caller
     * (/koth reload), which first makes sure no KoTH is running: a rebuild would drop its progress.
     */
    fun reload() {
        loadAll()
        plugin.logger.info("Configurations reloaded successfully.")
    }

    /**
     * Helper method to load a YAML file, creating it from defaults if missing.
     * Always read as UTF-8: Bukkit 1.8 would use the system charset, which breaks
     * diacritics in messages on Windows servers.
     *
     * @param withJarDefaults also register the bundled copy as defaults, so keys missing from the
     * file on disk resolve to the bundled value. Not used for config.yml: defaults would resurrect
     * KoTH types an admin deliberately removed.
     */
    private fun loadYamlFile(fileName: String, withJarDefaults: Boolean = false): FileConfiguration {
        val file = File(plugin.dataFolder, fileName)
        if (!file.exists()) {
            plugin.logger.info("Generating default $fileName...")
            plugin.saveResource(fileName, false)
        }

        val config = YamlConfiguration()
        try {
            InputStreamReader(FileInputStream(file), Charsets.UTF_8).use { config.load(it) }
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "Could not read $fileName", e)
        } catch (e: InvalidConfigurationException) {
            val backup = backUpBrokenFile(file)
            plugin.logger.log(
                Level.SEVERE,
                "$fileName is not valid YAML, so it was NOT loaded (the plugin sees it as empty). " +
                    "Fix the file and run /koth reload. A copy was saved as ${backup ?: "(backup failed)"}, " +
                    "in case a command overwrites $fileName in the meantime.",
                e
            )
        }

        if (withJarDefaults) {
            plugin.getResource(fileName)?.let { stream ->
                InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                    config.setDefaults(YamlConfiguration.loadConfiguration(reader))
                }
            }
        }
        return config
    }

    /**
     * Copies an unparseable file next to itself as `<name>.broken-<last modified>`, so the admin's
     * content survives even if a command later saves over the original (e.g. /koth create writes
     * arenas.yml from memory, which is empty after a failed load). One copy per version of the file.
     * @return the backup's file name, or null if it couldn't be written
     */
    private fun backUpBrokenFile(file: File): String? {
        val backup = File(file.parentFile, "${file.name}.broken-${file.lastModified()}")
        return try {
            if (!backup.exists()) {
                Files.copy(file.toPath(), backup.toPath())
            }
            backup.name
        } catch (e: IOException) {
            null
        }
    }
}
