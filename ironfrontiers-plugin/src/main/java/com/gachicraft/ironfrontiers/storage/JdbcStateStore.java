package com.gachicraft.ironfrontiers.storage;

import com.gachicraft.ironfrontiers.api.SectorState;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** MySQL implementation of StateStore. All JDBC work is delegated to the DB executor. */
public final class JdbcStateStore implements StateStore {
    private final HikariDataSource dataSource;
    private final Executor executor;
    private final String stateTable;
    private final String overrideTable;

    public JdbcStateStore(HikariDataSource dataSource, Executor executor, String prefix) {
        this.dataSource = dataSource;
        this.executor = executor;
        if (!prefix.matches("[A-Za-z0-9_]{1,24}")) {
            throw new IllegalArgumentException("Invalid table prefix");
        }
        stateTable = prefix + "sector_state";
        overrideTable = prefix + "sector_override";
    }

    @Override
    public CompletableFuture<List<SectorPersistentState>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT sector_id,state,state_left_s,occupation_left_s,destruction_saved_s,"
                    + "resume_window_left_s,spawn_pause_left_s,version,leader_epoch FROM " + stateTable;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                List<SectorPersistentState> states = new ArrayList<>();
                while (result.next()) {
                    states.add(new SectorPersistentState(result.getString("sector_id"),
                            SectorState.valueOf(result.getString("state")),
                            nullableLong(result, "state_left_s"),
                            result.getLong("occupation_left_s"),
                            nullableLong(result, "destruction_saved_s"),
                            result.getLong("resume_window_left_s"),
                            result.getLong("spawn_pause_left_s"),
                            result.getLong("version"), result.getLong("leader_epoch")));
                }
                return List.copyOf(states);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not load sector states", exception);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> save(SectorPersistentState state) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO " + stateTable + " (sector_id,state,state_left_s,occupation_left_s,"
                    + "destruction_saved_s,resume_window_left_s,spawn_pause_left_s,version,leader_epoch) "
                    + "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state=VALUES(state),"
                    + "state_left_s=VALUES(state_left_s),occupation_left_s=VALUES(occupation_left_s),"
                    + "destruction_saved_s=VALUES(destruction_saved_s),resume_window_left_s=VALUES(resume_window_left_s),"
                    + "spawn_pause_left_s=VALUES(spawn_pause_left_s),version=VALUES(version),leader_epoch=VALUES(leader_epoch)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, state.sectorId());
                statement.setString(2, state.state().name());
                setNullableLong(statement, 3, state.stateLeftSeconds());
                statement.setLong(4, state.occupationLeftSeconds());
                setNullableLong(statement, 5, state.destructionSavedSeconds());
                statement.setLong(6, state.resumeWindowLeftSeconds());
                statement.setLong(7, state.spawnPauseLeftSeconds());
                statement.setLong(8, state.version());
                statement.setLong(9, state.leaderEpoch());
                statement.executeUpdate();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not persist sector state " + state.sectorId(), exception);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<SectorOverride>> loadOverrides() {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT sector_id,kpm_capture_tenths,kpm_held_tenths FROM " + overrideTable;
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                List<SectorOverride> overrides = new ArrayList<>();
                while (result.next()) {
                    Integer capture = nullableInt(result, "kpm_capture_tenths");
                    Integer held = nullableInt(result, "kpm_held_tenths");
                    if (capture != null || held != null) {
                        overrides.add(new SectorOverride(result.getString("sector_id"), capture, held));
                    }
                }
                return List.copyOf(overrides);
            } catch (Exception exception) {
                throw new IllegalStateException("Could not load sector KPM overrides", exception);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> saveOverride(SectorOverride override) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO " + overrideTable
                    + " (sector_id,kpm_capture_tenths,kpm_held_tenths) VALUES (?,?,?) "
                    + "ON DUPLICATE KEY UPDATE kpm_capture_tenths=VALUES(kpm_capture_tenths),"
                    + "kpm_held_tenths=VALUES(kpm_held_tenths)";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, override.sectorId());
                setNullableInt(statement, 2, override.captureTenths());
                setNullableInt(statement, 3, override.heldTenths());
                statement.executeUpdate();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not persist sector override " + override.sectorId(), exception);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteSector(String sectorId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    try (PreparedStatement stateDelete = connection.prepareStatement(
                            "DELETE FROM " + stateTable + " WHERE sector_id=?");
                         PreparedStatement overrideDelete = connection.prepareStatement(
                            "DELETE FROM " + overrideTable + " WHERE sector_id=?")) {
                        stateDelete.setString(1, sectorId);
                        stateDelete.executeUpdate();
                        overrideDelete.setString(1, sectorId);
                        overrideDelete.executeUpdate();
                    }
                    connection.commit();
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(previousAutoCommit);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Could not delete sector data " + sectorId, exception);
            }
        }, executor);
    }

    private static Long nullableLong(ResultSet result, String column) throws Exception {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet result, String column) throws Exception {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws Exception {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setLong(index, value);
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws Exception {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }

    @Override public void close() { /* DataSource lifecycle is owned by DatabaseHandle. */ }
}
