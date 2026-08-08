package com.puk3p.koth

import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

class KoTHPlugin : JavaPlugin() {

    // We will initialize these managers shortly
    // lateinit var configManager: ConfigManager
    // lateinit var arenaManager: ArenaManager
    // lateinit var schedulerManager: SchedulerManager
    // lateinit var tournamentManager: TournamentManager

    var factionsEnabled = false
        private set

    var placeholderAPIEnabled = false
        private set

    override fun onEnable() {
        logger.info("Initializing KoTH Plugin v${description.version}...")

        checkDependencies()

        // 1. Load Configurations
        // configManager = ConfigManager(this)
        // configManager.loadAll()

        // 2. Initialize Managers
        // arenaManager = ArenaManager(this)
        // tournamentManager = TournamentManager(this)
        // schedulerManager = SchedulerManager(this)

        // 3. Register Listeners
        // server.pluginManager.registerEvents(PlayerListener(this), this)
        // server.pluginManager.registerEvents(ProtectionListener(this), this)

        // 4. Register Commands
        // getCommand("koth")?.setExecutor(KothCommand(this))

        // 5. Register PlaceholderAPI Hook
        if (placeholderAPIEnabled) {
            // PlaceholderAPIHook(this).register()
        }

        logger.info("KoTH Plugin has been successfully enabled.")
    }

    override fun onDisable() {
        logger.info("Disabling KoTH Plugin...")

        // Safely shut down active arenas and save data
        // arenaManager.shutdownAll()
        // schedulerManager.cancelAllTasks()

        logger.info("KoTH Plugin disabled.")
    }

    private fun checkDependencies() {
        if (server.pluginManager.getPlugin("Factions") != null) {
            factionsEnabled = true
            logger.info("Hooked into FactionsUUID successfully.")
        } else {
            logger.log(Level.WARNING, "FactionsUUID not found! Faction features will be disabled.")
        }

        if (server.pluginManager.getPlugin("PlaceholderAPI") != null) {
            placeholderAPIEnabled = true
            logger.info("Hooked into PlaceholderAPI successfully.")
        }
    }
}