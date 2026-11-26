package com.gachicraft.ironfrontiers.storage;

import com.zaxxer.hikari.HikariDataSource;

/** Owns the pooled connection source and its state repository. */
public record DatabaseHandle(HikariDataSource dataSource, StateStore stateStore) implements AutoCloseable {
    @Override public void close() {
        stateStore.close();
        dataSource.close();
    }
}
