package com.puk3p.koth.pvppoints

import com.puk3p.koth.KoTHPlugin
import com.puk3p.koth.database.Database
import com.puk3p.koth.hooks.FactionsHook
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.sql.SQLException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * PvP points (config.yml -> pvp-points): points that belong to factions. Members earn them for
 * their faction with kills and KoTH captures and lose them by dying; admins add them through a
 * player (/pvppoints add <player>, like the KoTH win rewards) and wipe them all with
 * /pvppoints reset. %pvppoints_...% placeholders, also per faction for /f who. Stored in a YAML
 * file or in MySQL (config.yml -> database), which also keeps a history of every change.
 * Needs Factions.
 */
class PvpPointsModule(private val plugin: KoTHPlugin) {
    /** Null when the module is off; see [disabledReason]. */
    var store: PvpPointsStore? = null
        private set

    var disabledReason: String? = null
        private set

    private var database: Database? = null
    private var mySqlStorage: MySqlPvpPointsStorage? = null
    private var saveTask: BukkitTask? = null
    private var lastHistoryCleanup = 0L

    // A lambda rather than a PvpPointsPlaceholders field, so PlaceholderAPI classes are only
    // loaded when PlaceholderAPI is installed.
    private var unregisterPlaceholders: (() -> Unit)? = null

    private val config get() = plugin.configManager.config

    fun enable() {
        if (!config.getBoolean("$ROOT.enabled", true)) {
            disabledReason = "disabled in config.yml ($ROOT.enabled)"
            return
        }
        val factions = plugin.factionsHook
        if (factions == null) {
            disabledReason = "Factions is not installed (points belong to factions)"
            plugin.logger.warning("[PvpPoints] Disabled: $disabledReason.")
            return
        }

        val store =
            try {
                openStore()
            } catch (e: SQLException) {
                fail("could not use MySQL", e)
                return
            } catch (e: IOException) {
                fail("could not read the saved points", e)
                return
            } catch (e: RuntimeException) {
                // PoolInitializationException (wrong host, login or database name), bad table-prefix, ...
                fail("could not connect to MySQL", e)
                return
            }
        this.store = store
        refreshTags(store, factions)
        registerListeners(store, factions)

        val interval = config.getLong("$ROOT.save-interval", 60).coerceAtLeast(MIN_SAVE_INTERVAL) * 20
        saveTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { periodic(store) }, interval, interval)

        if (plugin.placeholderAPIEnabled) {
            val expansion = PvpPointsPlaceholders(store, factions, plugin.description.version)
            expansion.register()
            unregisterPlaceholders = { expansion.unregister() }
        }
        val where = if (mySqlStorage != null) "MySQL" else config.getString("$ROOT.storage-file", DEFAULT_FILE)
        plugin.logger.info("[PvpPoints] Enabled: ${store.trackedCount()} faction(s), stored in $where.")
    }

    fun disable() {
        saveTask?.cancel()
        saveTask = null
        unregisterPlaceholders?.invoke()
        unregisterPlaceholders = null
        store?.close() // blocking: the server is stopping and the last changes must be written
        database?.close()
        database = null
    }

    /** A faction renamed while the server was off: the top shows its current tag. */
    private fun refreshTags(
        store: PvpPointsStore,
        factions: FactionsHook,
    ) {
        for (entry in store.entries()) {
            val current = factions.factionById(entry.factionId) ?: continue
            if (current.tag != entry.tag) store.rename(entry.factionId, current.tag)
        }
    }

    private fun registerListeners(
        store: PvpPointsStore,
        factions: FactionsHook,
    ) {
        val pluginManager = plugin.server.pluginManager
        pluginManager.registerEvents(PvpPointsListener(plugin, store, factions), plugin)
        try {
            pluginManager.registerEvents(PvpPointsRenameListener(plugin, store), plugin)
        } catch (e: LinkageError) {
            plugin.logger.warning(
                "[PvpPoints] This Factions version has no FactionRenameEvent; renamed factions show their new tag after a restart.",
            )
        }
        try {
            pluginManager.registerEvents(PvpPointsDisbandListener(plugin, store), plugin)
        } catch (e: LinkageError) {
            plugin.logger.warning("[PvpPoints] This Factions version has no FactionDisbandEvent; disbanded factions keep their points.")
        }
    }

    private fun openStore(): PvpPointsStore {
        val yamlFile = File(plugin.dataFolder, config.getString("$ROOT.storage-file", DEFAULT_FILE))
        val type = config.getString("$ROOT.storage", "yaml").lowercase()
        if (type != "mysql") {
            if (type != "yaml") plugin.logger.warning("[PvpPoints] Unknown $ROOT.storage '$type'; using yaml.")
            return PvpPointsStore(YamlPvpPointsStorage(yamlFile, plugin.logger))
        }

        val database = Database(Database.settingsFrom(config.getConfigurationSection("database")), plugin.logger)
        this.database = database
        val storage = MySqlPvpPointsStorage(database, plugin.logger, File(plugin.dataFolder, PENDING_FILE))
        mySqlStorage = storage
        val store = PvpPointsStore(storage)
        if (store.trackedCount() == 0 && yamlFile.isFile) importYaml(store, storage, yamlFile)
        return store
    }

    /** First start on MySQL with points in the YAML file: copy them over and rename the file. */
    private fun importYaml(
        store: PvpPointsStore,
        storage: MySqlPvpPointsStorage,
        yamlFile: File,
    ) {
        val entries = YamlPvpPointsStorage(yamlFile, plugin.logger).loadAll()
        if (entries.isEmpty()) return
        storage.importAll(entries, yamlFile.name)
        store.restore(entries)
        val done = File(yamlFile.parentFile, "${yamlFile.name}.imported")
        Files.move(yamlFile.toPath(), done.toPath())
        plugin.logger.info("[PvpPoints] Copied ${entries.size} faction(s) from ${yamlFile.name} to MySQL; the file is now ${done.name}.")
    }

    private fun periodic(store: PvpPointsStore) {
        store.flush(Executor { plugin.server.scheduler.runTaskAsynchronously(plugin, it) })

        val retentionDays = config.getLong("$ROOT.history-retention-days", 0)
        val storage = mySqlStorage ?: return
        val now = System.currentTimeMillis()
        if (retentionDays > 0 && now - lastHistoryCleanup >= TimeUnit.DAYS.toMillis(1)) {
            lastHistoryCleanup = now
            storage.deleteHistoryBefore(now - TimeUnit.DAYS.toMillis(retentionDays))
        }
    }

    private fun fail(
        reason: String,
        e: Exception,
    ) {
        disabledReason = "$reason (${e.message}); see the console"
        plugin.logger.log(Level.SEVERE, "[PvpPoints] Disabled: $reason. Nothing was changed; fix it and restart.", e)
        mySqlStorage?.close { emptyList() } // stops its background thread if it had started
        database?.close()
        database = null
        mySqlStorage = null
    }

    companion object {
        const val ROOT = "pvp-points"
        private const val MIN_SAVE_INTERVAL = 5L
        const val DEFAULT_FILE = "pvp-faction-points.yml"
        private const val PENDING_FILE = "pvp-faction-points-pending.yml"
    }
}

/** `pvp-points.<key>` from messages.yml with the PvP points prefix. */
internal fun pvpMessage(
    plugin: KoTHPlugin,
    key: String,
    vararg placeholders: Pair<String, String>,
): String {
    val messages = plugin.configManager.messages
    return messages.raw("pvp-points.prefix") + messages.raw("pvp-points.$key", *placeholders)
}
