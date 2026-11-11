package com.puk3p.koth

import com.puk3p.koth.arena.ArenaManager
import com.puk3p.koth.commands.KothCommand
import com.puk3p.koth.commands.SelectionManager
import com.puk3p.koth.commands.SelectionVisualizer
import com.puk3p.koth.config.ConfigManager
import com.puk3p.koth.factionwins.FactionWinsModule
import com.puk3p.koth.factionwins.FkothCommand
import com.puk3p.koth.hooks.FactionsHook
import com.puk3p.koth.hooks.PlaceholderAPIHook
import com.puk3p.koth.services.DiscordService
import com.puk3p.koth.listeners.PlayerListener
import com.puk3p.koth.listeners.ProtectionListener
import com.puk3p.koth.listeners.WandListener
import com.puk3p.koth.scheduler.KothScheduler
import com.puk3p.koth.services.RewardService
import com.puk3p.koth.tasks.CaptureTickTask
import com.puk3p.koth.kotharena.KothArenaManager
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

class KoTHPlugin : JavaPlugin() {

    lateinit var configManager: ConfigManager
        private set
    lateinit var arenaManager: ArenaManager
        private set
    lateinit var kothArena: KothArenaManager
        private set

    /** Pending zone selections (/koth pos1, /koth pos2, the wand). */
    lateinit var selections: SelectionManager
        private set
    lateinit var rewardService: RewardService
        private set
    lateinit var kothScheduler: KothScheduler
        private set
    lateinit var discordService: DiscordService
        private set

    /** Null when the Factions plugin is not installed. */
    var factionsHook: FactionsHook? = null
        private set

    /** Null when PlaceholderAPI is not installed. */
    private var placeholderHook: PlaceholderAPIHook? = null

    /** Faction KoTH wins (/fkoth), formerly the FKothWins addon. */
    lateinit var factionWins: FactionWinsModule
        private set

    var factionsEnabled = false
        private set

    var placeholderAPIEnabled = false
        private set

    override fun onEnable() {
        logger.info("Initializing KoTH Plugin v${description.version}...")

        checkDependencies()

        // 1. Load Configurations
        configManager = ConfigManager(this)
        configManager.loadAll()

        // 2. Initialize Managers
        arenaManager = ArenaManager(this)
        kothArena = KothArenaManager(this)
        rewardService = RewardService(this)
        discordService = DiscordService(this)
        if (factionsEnabled) {
            factionsHook = FactionsHook()
        }
        arenaManager.loadArenas()
        kothScheduler = KothScheduler(this)
        kothScheduler.reload()

        // 3. Register Listeners
        server.pluginManager.registerEvents(PlayerListener(this), this)
        server.pluginManager.registerEvents(ProtectionListener(this), this)
        selections = SelectionManager(this)
        server.pluginManager.registerEvents(WandListener(this), this)
        SelectionVisualizer(this).runTaskTimer(this, SelectionVisualizer.PERIOD_TICKS, SelectionVisualizer.PERIOD_TICKS)

        // 4. Register Commands
        val kothCommand = KothCommand(this)
        getCommand("koth")?.let {
            it.setExecutor(kothCommand)
            it.setTabCompleter(kothCommand)
        }

        // 5. Capture loop: once per second on the main thread (it fires Bukkit events and broadcasts)
        CaptureTickTask(this).runTaskTimer(this, 20L, 20L)

        // 6. Scheduled starts: checked every second, acts once per minute
        kothScheduler.runTaskTimer(this, 20L, 20L)

        // 7. Register PlaceholderAPI Hook
        if (placeholderAPIEnabled) {
            placeholderHook = PlaceholderAPIHook(this).also { it.register() }
        }

        // 8. Faction KoTH wins: /fkoth, %fkoth_...%, top hologram, FKothApi service (for DinoFtop)
        factionWins = FactionWinsModule(this)
        factionWins.enable()
        val fkothCommand = FkothCommand(this, factionWins)
        getCommand("fkoth")?.let {
            it.setExecutor(fkothCommand)
            it.setTabCompleter(fkothCommand)
        }

        logger.info("KoTH Plugin has been successfully enabled.")
    }

    override fun onDisable() {
        logger.info("Disabling KoTH Plugin...")

        // Bukkit cancels this plugin's scheduled tasks automatically on disable.
        // Arena edits are saved immediately, so only a running KoTH or KOTH Arena event needs handling.
        // Cancelling the event teleports its players out, so nobody is left in the arena after a restart.
        if (::kothArena.isInitialized && kothArena.isRunning) {
            kothArena.cancel()
        }
        if (::arenaManager.isInitialized) {
            arenaManager.getActiveArena()?.let { arenaManager.stopArena(it) }
        }
        // persist() keeps the expansion across /papi reload, so remove it explicitly
        placeholderHook?.unregister()
        placeholderHook = null
        if (::factionWins.isInitialized) {
            factionWins.disable()
        }
        server.servicesManager.unregisterAll(this)

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
