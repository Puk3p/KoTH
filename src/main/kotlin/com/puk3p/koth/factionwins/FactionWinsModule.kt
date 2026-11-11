package com.puk3p.koth.factionwins

import com.puk3p.koth.KoTHPlugin
import org.bukkit.plugin.ServicePriority
import ro.puk3p.fkoth.api.FKothApi
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.logging.Level

/**
 * Faction KoTH wins (config.yml -> faction-wins), formerly the separate FKothWins addon:
 * wins per faction on every capture, /fkoth, %fkoth_...% placeholders, a top hologram and the
 * FKothApi service other plugins (DinoFtop) read wins from.
 */
class FactionWinsModule(private val plugin: KoTHPlugin) {

    /** Null when the module is off; see [disabledReason]. */
    var service: FactionWinsService? = null
        private set

    var disabledReason: String? = null
        private set

    private var hologram: TopHologram? = null
    private var disbandHookEnabled = false
    private var renameHookEnabled = false

    // A lambda rather than a FKothPlaceholders field, so PlaceholderAPI classes are only
    // loaded when PlaceholderAPI is actually installed.
    private var unregisterPlaceholders: (() -> Unit)? = null

    private val config get() = plugin.configManager.config

    fun enable() {
        if (!config.getBoolean("faction-wins.enabled", true)) {
            disabledReason = "disabled in config.yml (faction-wins.enabled)"
            return
        }
        if (plugin.server.pluginManager.getPlugin(LEGACY_PLUGIN) != null) {
            // Both would provide ro.puk3p.fkoth.api.FKothApi and the /fkoth command
            disabledReason = "the old $LEGACY_PLUGIN plugin is still installed"
            plugin.logger.severe(
                "$LEGACY_PLUGIN is installed, but faction KoTH wins are now built into KoTH. " +
                    "Remove $LEGACY_PLUGIN.jar and restart; its wins are imported automatically. " +
                    "Faction wins stay disabled until then."
            )
            return
        }

        val storageFile = File(plugin.dataFolder, config.getString("faction-wins.storage-file", "faction-wins.yml"))
        importLegacyData(storageFile)

        val service = FactionWinsService(plugin, FactionWinsRepository(storageFile, plugin.logger))
        this.service = service

        val pluginManager = plugin.server.pluginManager
        pluginManager.registerEvents(CaptureWinListener(plugin, service), plugin)
        if (plugin.factionsEnabled) {
            disbandHookEnabled = try {
                pluginManager.registerEvents(FactionDisbandListener(plugin, service), plugin)
                true
            } catch (e: LinkageError) {
                plugin.logger.warning("[FKoth] This Factions version has no FactionDisbandEvent; disband cleanup disabled.")
                false
            }
            renameHookEnabled = try {
                pluginManager.registerEvents(FactionRenameListener(plugin, service), plugin)
                true
            } catch (e: LinkageError) {
                plugin.logger.warning("[FKoth] This Factions version has no FactionRenameEvent; a renamed faction's wins stay under its old name.")
                false
            }
        }

        plugin.server.servicesManager.register(FKothApi::class.java, FKothApiImpl(service), plugin, ServicePriority.Normal)

        if (plugin.placeholderAPIEnabled) {
            val expansion = FKothPlaceholders(service, plugin.description.version)
            expansion.register()
            unregisterPlaceholders = { expansion.unregister() }
        }

        startHologram(service)
        plugin.logger.info("[FKoth] Faction KoTH wins enabled (${service.trackedFactionCount()} factions tracked).")
    }

    /** After /koth reload or /fkoth reload: restarts the hologram with the new settings. */
    fun reload() {
        val service = service ?: return
        hologram?.stop()
        startHologram(service)
    }

    fun disable() {
        hologram?.stop()
        hologram = null
        unregisterPlaceholders?.invoke()
        unregisterPlaceholders = null
        service?.save()
    }

    fun debugStatus(): List<Pair<String, String>> {
        fun state(on: Boolean) = if (on) "ENABLED" else "DISABLED"
        return listOf(
            "Capture hook" to state(service != null),
            "Factions" to state(plugin.factionsHook != null),
            "Factions disband hook" to state(disbandHookEnabled),
            "Factions rename hook" to state(renameHookEnabled),
            "PlaceholderAPI" to state(unregisterPlaceholders != null),
            "Top hologram" to (hologram?.status() ?: "DISABLED"),
            "Tracked factions" to (service?.trackedFactionCount()?.toString() ?: "-"),
        )
    }

    private fun startHologram(service: FactionWinsService) {
        val hologram = TopHologram(plugin, service)
        hologram.start()
        this.hologram = hologram
    }

    /**
     * First start after FKothWins: copy its wins file (same format) if this plugin has none yet.
     * The old file is left in place.
     */
    private fun importLegacyData(target: File) {
        if (target.exists()) return
        val legacyFolder = File(plugin.dataFolder.parentFile, LEGACY_PLUGIN)
        val legacyFile = File(legacyFolder, "faction-wins.yml")
        if (!legacyFile.isFile) return
        try {
            target.parentFile?.mkdirs()
            Files.copy(legacyFile.toPath(), target.toPath())
            plugin.logger.info("[FKoth] Imported faction wins from plugins/$LEGACY_PLUGIN/faction-wins.yml.")
        } catch (e: IOException) {
            plugin.logger.log(Level.SEVERE, "[FKoth] Could not import plugins/$LEGACY_PLUGIN/faction-wins.yml", e)
        }
    }

    companion object {
        const val LEGACY_PLUGIN = "FKothWins"
    }
}
