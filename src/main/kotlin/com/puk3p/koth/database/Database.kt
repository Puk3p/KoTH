package com.puk3p.koth.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.configuration.ConfigurationSection
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger

/**
 * MySQL / MariaDB connection pool (config.yml -> database). The driver and HikariCP are bundled
 * and relocated, so this works the same whatever the server or other plugins ship.
 *
 * Tables are named [tablePrefix] + name, and each feature's schema is versioned in
 * `<prefix>schema_version` (see [migrate]), so later versions can change tables safely.
 */
class Database(
    settings: Settings,
    private val logger: Logger,
) : AutoCloseable {
    data class Settings(
        val host: String,
        val port: Int,
        val name: String,
        val username: String,
        val password: String,
        val tablePrefix: String,
        val poolSize: Int,
        val connectionTimeoutMillis: Long,
        val properties: Map<String, String>,
    ) {
        override fun toString(): String = "$username@$host:$port/$name" // never the password
    }

    val tablePrefix: String = settings.tablePrefix
    private val dataSource: HikariDataSource

    init {
        require(PREFIX_PATTERN.matches(tablePrefix)) { "database.table-prefix may only use letters, digits and _ (got '$tablePrefix')" }
        val config = HikariConfig()
        config.poolName = "KoTH-MySQL"
        config.driverClassName = DRIVER
        config.jdbcUrl = "jdbc:mysql://${settings.host}:${settings.port}/${settings.name}"
        config.username = settings.username
        config.password = settings.password
        config.maximumPoolSize = settings.poolSize.coerceIn(1, 20)
        config.minimumIdle = 1
        config.connectionTimeout = settings.connectionTimeoutMillis.coerceAtLeast(MIN_TIMEOUT_MILLIS)
        // Recycle connections well before MySQL's wait_timeout and ping idle ones, so a quiet
        // night never leaves the pool holding connections the server already dropped.
        config.maxLifetime = MAX_LIFETIME_MILLIS
        config.keepaliveTime = KEEPALIVE_MILLIS
        DEFAULT_PROPERTIES.forEach { (key, value) -> config.addDataSourceProperty(key, value) }
        settings.properties.forEach { (key, value) -> config.addDataSourceProperty(key, value) }
        // Fails here (PoolInitializationException) if MySQL can't be reached or the login is wrong
        dataSource = HikariDataSource(config)
        logger.info("Connected to MySQL ($settings).")
    }

    fun connection(): Connection = dataSource.connection

    fun table(name: String): String = tablePrefix + name

    /**
     * Brings [component]'s tables up to date: step i (0-based) moves the schema from version i to
     * i + 1. Steps already applied (recorded in `schema_version`) are skipped, so this runs on every
     * start. Write steps with IF NOT EXISTS where possible: MySQL can't roll back table changes.
     */
    @Throws(SQLException::class)
    fun migrate(
        component: String,
        steps: List<List<String>>,
    ) {
        val versions = table("schema_version")
        connection().use { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS $versions (" +
                        "component VARCHAR(64) NOT NULL PRIMARY KEY, " +
                        "version INT NOT NULL, " +
                        "updated_at BIGINT NOT NULL" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                )
            }
            val current =
                c.prepareStatement("SELECT version FROM $versions WHERE component = ?").use { ps ->
                    ps.setString(1, component)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            if (current > steps.size) {
                throw SQLException(
                    "$component tables are at version $current, newer than this plugin knows (${steps.size}). " +
                        "Update the plugin instead of running an older one on this database.",
                )
            }
            for (version in current until steps.size) {
                c.createStatement().use { st -> steps[version].forEach { st.executeUpdate(it) } }
                c.prepareStatement(
                    "INSERT INTO $versions (component, version, updated_at) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE version = VALUES(version), updated_at = VALUES(updated_at)",
                ).use { ps ->
                    ps.setString(1, component)
                    ps.setInt(2, version + 1)
                    ps.setLong(3, System.currentTimeMillis())
                    ps.executeUpdate()
                }
                logger.info("Updated the $component tables to version ${version + 1}.")
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    companion object {
        // Relocated with the bundled driver at build time (pom.xml, maven-shade-plugin)
        private const val DRIVER = "com.mysql.cj.jdbc.Driver"
        private val PREFIX_PATTERN = Regex("^[A-Za-z0-9_]{0,32}$")
        private const val MIN_TIMEOUT_MILLIS = 1000L
        private const val MAX_LIFETIME_MILLIS = 30 * 60 * 1000L
        private const val KEEPALIVE_MILLIS = 5 * 60 * 1000L

        private val DEFAULT_PROPERTIES =
            mapOf(
                "characterEncoding" to "UTF-8",
                "useUnicode" to "true",
                "tcpKeepAlive" to "true",
                "rewriteBatchedStatements" to "true",
                "cachePrepStmts" to "true",
                "prepStmtCacheSize" to "50",
                "useServerPrepStmts" to "true",
            )

        /** Reads config.yml -> database. */
        fun settingsFrom(section: ConfigurationSection?): Settings {
            val properties =
                section?.getConfigurationSection("properties")?.let { props ->
                    props.getKeys(false).associateWith { props.getString(it).orEmpty() }
                } ?: emptyMap()
            return Settings(
                host = section?.getString("host", "localhost") ?: "localhost",
                port = section?.getInt("port", 3306) ?: 3306,
                name = section?.getString("name", "koth") ?: "koth",
                username = section?.getString("username", "root") ?: "root",
                password = section?.getString("password", "") ?: "",
                tablePrefix = section?.getString("table-prefix", "koth_") ?: "koth_",
                poolSize = section?.getInt("pool-size", 3) ?: 3,
                connectionTimeoutMillis = section?.getLong("connection-timeout", 5000) ?: 5000,
                properties = properties,
            )
        }
    }
}
