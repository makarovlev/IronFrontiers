package com.gachicraft.ironfrontiers.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Opens MySQL and applies the initial schema migration exclusively on the DB executor. */
public final class DatabaseBootstrapper {
    private static final int SCHEMA_VERSION = 1;
    private final Plugin plugin;
    private final Executor databaseExecutor;

    public DatabaseBootstrapper(Plugin plugin, Executor databaseExecutor) {
        this.plugin = plugin;
        this.databaseExecutor = databaseExecutor;
    }

    public CompletableFuture<DatabaseHandle> initialize(YamlConfiguration config) {
        return CompletableFuture.supplyAsync(() -> {
            HikariDataSource pool = null;
            try {
                String host = config.getString("database.host", "127.0.0.1");
                int port = config.getInt("database.port", 3306);
                String name = config.getString("database.name", "ironfrontiers");
                String prefix = config.getString("database.table_prefix", "if_");
                if (!prefix.matches("[A-Za-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid database.table_prefix");
                boolean ssl = config.getBoolean("database.use_ssl", false);
                String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + name
                        + "?useSSL=" + ssl + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8";
                HikariConfig hikari = new HikariConfig();
                hikari.setJdbcUrl(jdbcUrl);
                hikari.setUsername(config.getString("database.user", "root"));
                hikari.setPassword(config.getString("database.password", ""));
                hikari.setMaximumPoolSize(config.getInt("database.pool_size", 6));
                hikari.setMinimumIdle(1);
                hikari.setConnectionTimeout(5000L);
                hikari.setValidationTimeout(3000L);
                hikari.setPoolName("IronFrontiers-MySQL");
                pool = new HikariDataSource(hikari);
                migrate(pool, prefix);
                JdbcStateStore store = new JdbcStateStore(pool, databaseExecutor, prefix);
                return new DatabaseHandle(pool, store);
            } catch (Exception exception) {
                if (pool != null) pool.close();
                throw new IllegalStateException("MySQL initialization failed", exception);
            }
        }, databaseExecutor);
    }

    private void migrate(HikariDataSource pool, String prefix) throws Exception {
        String schemaTable = prefix + "schema";
        String stateTable = prefix + "sector_state";
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + schemaTable
                    + " (version INT NOT NULL)");
            try (ResultSet result = statement.executeQuery("SELECT version FROM " + schemaTable + " LIMIT 1")) {
                if (!result.next()) statement.executeUpdate("INSERT INTO " + schemaTable + " (version) VALUES (0)");
            }
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + stateTable + " ("
                    + "sector_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "state VARCHAR(16) NOT NULL,"
                    + "state_left_s INT NULL,"
                    + "occupation_left_s INT NOT NULL DEFAULT 0,"
                    + "destruction_saved_s INT NULL,"
                    + "resume_window_left_s INT NOT NULL DEFAULT 0,"
                    + "version BIGINT NOT NULL DEFAULT 0,"
                    + "leader_epoch BIGINT NOT NULL DEFAULT 0,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                    + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            statement.executeUpdate("UPDATE " + schemaTable + " SET version = " + SCHEMA_VERSION);
        }
        plugin.getLogger().info("Database schema version " + SCHEMA_VERSION + " is ready");
    }
}
