package com.gachicraft.ironfrontiers.storage.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** One sequential, versioned database schema migration. */
public interface DatabaseMigration {
    int version();
    void apply(Connection connection, String tablePrefix) throws SQLException;
}
