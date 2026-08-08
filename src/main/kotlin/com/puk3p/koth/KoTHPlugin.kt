package com.puk3p.koth

import com.puk3p.koth.arena.ArenaManager
import com.puk3p.koth.config.ConfigManager
import com.puk3p.koth.hooks.FactionsHook
import com.puk3p.koth.listeners.PlayerListener
import com.puk3p.koth.services.DiscordService
import com.puk3p.koth.services.RewardService
import com.puk3p.koth.tasks.CaptureTickTask
import com.puk3p.koth.tournament.TournamentManager
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

class KoTHPlugin : JavaPlugin() {

    lateinit var configManager: ConfigManager
    lateinit var arenaManager: ArenaManager
    lateinit var tournamentManager: TournamentManager
    lateinit var rewardService: RewardService
    lateinit var discordService: DiscordService
    lateinit var schedulerManager: com.puk3p.koth.scheduler.KothScheduler

    var factionsHook: FactionsHook? = null

    var factionsEnabled = false
        private set

    var placeholderAPIEnabled = false
        private set

    var premiumVanishHook: com.puk3p.koth.hooks.PremiumVanishHook? = null
        private set

    override fun onEnable() {
        logger.info("Initializing KoTH Plugin v${description.version}...")

        checkDependencies()

        // 1. Load Configurations
        configManager = ConfigManager(this)
        configManager.loadAll()

        // 2. Initialize Managers & Services
        arenaManager = ArenaManager(this)
        arenaManager.loadArenas()

        tournamentManager = TournamentManager(this)
        rewardService = RewardService(this)
        discordService = DiscordService(this)

        // Add Scheduler
        schedulerManager = com.puk3p.koth.scheduler.KothScheduler(this)
        schedulerManager.loadSchedules()


        if (factionsEnabled) {
            factionsHook = FactionsHook()
        }

        // 3. Register Listeners
        server.pluginManager.registerEvents(PlayerListener(this), this)
        // Add the ProtectionListener for tournament mode restrictions
        server.pluginManager.registerEvents(com.puk3p.koth.listeners.ProtectionListener(this), this)

        // 4. Register Commands
        val kothCommand = com.puk3p.koth.commands.KothCommand(this)
        getCommand("koth")?.setExecutor(kothCommand)
        getCommand("koth")?.tabCompleter = kothCommand

        // 5. Register PlaceholderAPI Hook
        if (placeholderAPIEnabled) {
            com.puk3p.koth.hooks.PlaceholderAPIHook(this).register()
        }

        // 6. Start Core Tasks
        // This runs our capture logic every 1 second (20 ticks)
        CaptureTickTask(this).runTaskTimer(this, 20L, 20L)
        schedulerManager.startTask()
        logger.info("KoTH Plugin has been successfully enabled.")
    }

    override fun onDisable() {
        logger.info("Disabling KoTH Plugin...")

        // Ensure arenas are reset when plugin reloads
        if (::arenaManager.isInitialized) {
            arenaManager.clearArenas()
        }

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

        if (server.pluginManager.getPlugin("PremiumVanish") != null) {
            premiumVanishHook = com.puk3p.koth.hooks.PremiumVanishHook()
            logger.info("Hooked into PremiumVanish successfully.")
        }

    }
}