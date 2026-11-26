package com.gachicraft.ironfrontiers.storage;

import com.gachicraft.ironfrontiers.storage.migration.DatabaseMigration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

/** Applies consecutive schema migrations and records the resulting schema version. */
public final class SchemaMigrator {
    private final List<DatabaseMigration> migrations;

    public SchemaMigrator(List<DatabaseMigration> migrations) {
        this.migrations = migrations.stream().sorted(Comparator.comparingInt(DatabaseMigration::version)).toList();
    }

    public int migrate(DataSource dataSource, String prefix) throws Exception {
        if (!prefix.matches("[A-Za-z0-9_]{1,24}")) throw new IllegalArgumentException("Invalid table prefix");
        String schemaTable = prefix + "schema";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + schemaTable + " (version INT NOT NULL)");
            int currentVersion;
            try (ResultSet result = statement.executeQuery("SELECT version FROM " + schemaTable + " LIMIT 1")) {
                if (result.next()) currentVersion = result.getInt(1);
                else {
                    statement.executeUpdate("INSERT INTO " + schemaTable + " (version) VALUES (0)");
                    currentVersion = 0;
                }
            }

            for (DatabaseMigration migration : migrations) {
                if (migration.version() <= currentVersion) continue;
                if (migration.version() != currentVersion + 1) {
                    throw new IllegalStateException("Missing database migration " + (currentVersion + 1));
                }
                migration.apply(connection, prefix);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + schemaTable + " SET version = ?")) {
                    update.setInt(1, migration.version());
                    update.executeUpdate();
                }
                currentVersion = migration.version();
            }
            return currentVersion;
        }
    }
}
