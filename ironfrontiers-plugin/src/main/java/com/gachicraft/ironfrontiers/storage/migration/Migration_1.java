package com.gachicraft.ironfrontiers.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Initial global sector-state table required by the stage-one bootstrap. */
public final class Migration_1 implements DatabaseMigration {
    @Override public int version() { return 1; }

    @Override
    public void apply(Connection connection, String tablePrefix) throws SQLException {
        String table = tablePrefix + "sector_state";
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "sector_id VARCHAR(64) NOT NULL PRIMARY KEY,"
                + "state ENUM('ENEMY','SAFE','HELD','DESTRUCTION') NOT NULL,"
                + "state_left_s INT NULL,"
                + "occupation_left_s INT NOT NULL DEFAULT 0,"
                + "destruction_saved_s INT NULL,"
                + "resume_window_left_s INT NOT NULL DEFAULT 0,"
                + "version BIGINT NOT NULL DEFAULT 0,"
                + "leader_epoch BIGINT NOT NULL DEFAULT 0,"
                + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                + ") CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
