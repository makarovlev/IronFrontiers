package com.gachicraft.ironfrontiers.geometry;

import java.util.Arrays;

/** Minimal primitive-long open-addressed map used by the hot chunk lookup path. */
final class LongObjectMap<V> {
    @FunctionalInterface
    interface EntryConsumer<V> {
        void accept(long key, V value);
    }

    private long[] keys;
    private Object[] values;
    private boolean[] occupied;
    private int size;
    private int threshold;

    LongObjectMap() {
        this(16);
    }

    LongObjectMap(int expectedSize) {
        int capacity = 16;
        while (capacity < expectedSize * 2L) {
            capacity <<= 1;
        }
        allocate(capacity);
    }

    @SuppressWarnings("unchecked")
    V get(long key) {
        int slot = findSlot(key);
        return occupied[slot] ? (V) values[slot] : null;
    }

    void put(long key, V value) {
        if (size >= threshold) {
            resize();
        }
        int slot = findSlot(key);
        if (!occupied[slot]) {
            occupied[slot] = true;
            keys[slot] = key;
            size++;
        }
        values[slot] = value;
    }

    int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    void forEach(EntryConsumer<? super V> consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (occupied[i]) {
                consumer.accept(keys[i], (V) values[i]);
            }
        }
    }

    private int findSlot(long key) {
        int mask = keys.length - 1;
        int slot = (int) mix(key) & mask;
        while (occupied[slot] && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        boolean[] oldOccupied = occupied;
        allocate(oldKeys.length << 1);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldOccupied[i]) {
                put(oldKeys[i], (V) oldValues[i]);
            }
        }
    }

    private void allocate(int capacity) {
        keys = new long[capacity];
        values = new Object[capacity];
        occupied = new boolean[capacity];
        threshold = (int) (capacity * 0.65);
        size = 0;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
