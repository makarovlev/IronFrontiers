package com.gachicraft.ironfrontiers.storage;

import com.gachicraft.ironfrontiers.storage.migration.Migration_1;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Opens MySQL and applies versioned migrations exclusively on the DB executor. */
public final class DatabaseBootstrapper {
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

                int schemaVersion = new SchemaMigrator(List.of(new Migration_1())).migrate(pool, prefix);
                plugin.getLogger().info("Database schema version " + schemaVersion + " is ready");
                JdbcStateStore store = new JdbcStateStore(pool, databaseExecutor, prefix);
                return new DatabaseHandle(pool, store);
            } catch (Exception exception) {
                if (pool != null) pool.close();
                throw new IllegalStateException("MySQL initialization failed", exception);
            }
        }, databaseExecutor);
    }
}
