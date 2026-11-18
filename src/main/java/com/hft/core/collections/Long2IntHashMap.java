package com.hft.core.collections;

import java.util.Arrays;

/**
 * Open-addressing primitive long-to-int map with zero heap allocation during steady-state.
 * Uses linear probing and power-of-two sizing for ultra-fast bitwise masking.
 */
public class Long2IntHashMap {

    public static final int DEFAULT_MISSING_VALUE = -1;
    private static final long EMPTY_KEY = 0L;

    private final int missingValue;
    private final float loadFactor;
    private int capacity;
    private int mask;
    private int threshold;
    private int size;

    private long[] keys;
    private int[] values;
    private boolean hasZeroKey;
    private int zeroKeyValue;

    public Long2IntHashMap() {
        this(1024, 0.65f, DEFAULT_MISSING_VALUE);
    }

    public Long2IntHashMap(int expectedSize, float loadFactor, int missingValue) {
        this.missingValue = missingValue;
        this.loadFactor = loadFactor;
        this.capacity = tableSizeFor((int) (expectedSize / loadFactor));
        this.mask = capacity - 1;
        this.threshold = (int) (capacity * loadFactor);

        this.keys = new long[capacity];
        this.values = new int[capacity];
        clear();
    }

    private static int tableSizeFor(int cap) {
        int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
        return (n < 0) ? 1 : (n >= 1 << 30) ? 1 << 30 : n + 1;
    }

    public void clear() {
        Arrays.fill(keys, EMPTY_KEY);
        Arrays.fill(values, missingValue);
        hasZeroKey = false;
        zeroKeyValue = missingValue;
        size = 0;
    }

    public int get(long key) {
        if (key == EMPTY_KEY) {
            return hasZeroKey ? zeroKeyValue : missingValue;
        }

        int idx = hash(key) & mask;
        while (true) {
            long k = keys[idx];
            if (k == EMPTY_KEY) {
                return missingValue;
            }
            if (k == key) {
                return values[idx];
            }
            idx = (idx + 1) & mask;
        }
    }

    public int put(long key, int value) {
        if (key == EMPTY_KEY) {
            int old = hasZeroKey ? zeroKeyValue : missingValue;
            if (!hasZeroKey) size++;
            hasZeroKey = true;
            zeroKeyValue = value;
            return old;
        }

        int idx = hash(key) & mask;
        while (true) {
            long k = keys[idx];
            if (k == EMPTY_KEY) {
                keys[idx] = key;
                values[idx] = value;
                size++;
                if (size > threshold) {
                    rehash();
                }
                return missingValue;
            }
            if (k == key) {
                int old = values[idx];
                values[idx] = value;
                return old;
            }
            idx = (idx + 1) & mask;
        }
    }

    public int remove(long key) {
        if (key == EMPTY_KEY) {
            if (!hasZeroKey) return missingValue;
            hasZeroKey = false;
            int old = zeroKeyValue;
            zeroKeyValue = missingValue;
            size--;
            return old;
        }

        int idx = hash(key) & mask;
        while (true) {
            long k = keys[idx];
            if (k == EMPTY_KEY) {
                return missingValue;
            }
            if (k == key) {
                int old = values[idx];
                shiftKeys(idx);
                size--;
                return old;
            }
            idx = (idx + 1) & mask;
        }
    }

    private void shiftKeys(int pos) {
        int r = pos;
        while (true) {
            r = (r + 1) & mask;
            long k = keys[r];
            if (k == EMPTY_KEY) {
                keys[pos] = EMPTY_KEY;
                values[pos] = missingValue;
                return;
            }
            int slot = hash(k) & mask;
            if ((pos <= r) ? (slot <= pos || slot > r) : (slot <= pos && slot > r)) {
                keys[pos] = k;
                values[pos] = values[r];
                pos = r;
            }
        }
    }

    private void rehash() {
        int newCap = capacity << 1;
        long[] oldKeys = keys;
        int[] oldValues = values;

        keys = new long[newCap];
        values = new int[newCap];
        Arrays.fill(values, missingValue);
        mask = newCap - 1;
        threshold = (int) (newCap * loadFactor);
        capacity = newCap;

        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != EMPTY_KEY) {
                int val = oldValues[i];
                int idx = hash(k) & mask;
                while (keys[idx] != EMPTY_KEY) {
                    idx = (idx + 1) & mask;
                }
                keys[idx] = k;
                values[idx] = val;
            }
        }
    }

    private static int hash(long key) {
        key ^= (key >>> 33);
        key *= 0xff51afd7ed558ccdL;
        key ^= (key >>> 33);
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= (key >>> 33);
        return (int) key;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
