package com.gachicraft.ironfrontiers.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Stage-two persistence extension. Adds the capture spawn-pause remainder and
 * the independent per-sector KPM threshold overrides.
 */
public final class Migration_2 implements DatabaseMigration {
    @Override public int version() { return 2; }

    @Override
    public void apply(Connection connection, String tablePrefix) throws SQLException {
        String stateTable = tablePrefix + "sector_state";
        String overrideTable = tablePrefix + "sector_override";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + stateTable
                    + " ADD COLUMN spawn_pause_left_s INT NOT NULL DEFAULT 0");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + overrideTable + " ("
                    + "sector_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "kpm_capture_tenths INT NULL,"
                    + "kpm_held_tenths INT NULL"
                    + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }
}
