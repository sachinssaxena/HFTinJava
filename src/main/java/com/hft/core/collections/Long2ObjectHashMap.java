package com.hft.core.collections;

import java.util.Arrays;

/**
 * Open-addressing primitive long-to-object map.
 * Zero boxing of primitive long keys; zero allocation on steady-state get/put.
 */
public class Long2ObjectHashMap<V> {

    private static final long EMPTY_KEY = 0L;

    private final float loadFactor;
    private int capacity;
    private int mask;
    private int threshold;
    private int size;

    private long[] keys;
    private Object[] values;
    private boolean hasZeroKey;
    private V zeroKeyValue;

    public Long2ObjectHashMap() {
        this(1024, 0.65f);
    }

    public Long2ObjectHashMap(int expectedSize, float loadFactor) {
        this.loadFactor = loadFactor;
        this.capacity = tableSizeFor((int) (expectedSize / loadFactor));
        this.mask = capacity - 1;
        this.threshold = (int) (capacity * loadFactor);

        this.keys = new long[capacity];
        this.values = new Object[capacity];
    }

    private static int tableSizeFor(int cap) {
        int n = -1 >>> Integer.numberOfLeadingZeros(cap - 1);
        return (n < 0) ? 1 : (n >= 1 << 30) ? 1 << 30 : n + 1;
    }

    public void clear() {
        Arrays.fill(keys, EMPTY_KEY);
        Arrays.fill(values, null);
        hasZeroKey = false;
        zeroKeyValue = null;
        size = 0;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        if (key == EMPTY_KEY) {
            return hasZeroKey ? zeroKeyValue : null;
        }

        int idx = hash(key) & mask;
        while (true) {
            long k = keys[idx];
            if (k == EMPTY_KEY) {
                return null;
            }
            if (k == key) {
                return (V) values[idx];
            }
            idx = (idx + 1) & mask;
        }
    }

    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        if (key == EMPTY_KEY) {
            V old = zeroKeyValue;
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
                return null;
            }
            if (k == key) {
                V old = (V) values[idx];
                values[idx] = value;
                return old;
            }
            idx = (idx + 1) & mask;
        }
    }

    @SuppressWarnings("unchecked")
    public V remove(long key) {
        if (key == EMPTY_KEY) {
            if (!hasZeroKey) return null;
            hasZeroKey = false;
            V old = zeroKeyValue;
            zeroKeyValue = null;
            size--;
            return old;
        }

        int idx = hash(key) & mask;
        while (true) {
            long k = keys[idx];
            if (k == EMPTY_KEY) {
                return null;
            }
            if (k == key) {
                V old = (V) values[idx];
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
                values[pos] = null;
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
        Object[] oldValues = values;

        keys = new long[newCap];
        values = new Object[newCap];
        mask = newCap - 1;
        threshold = (int) (newCap * loadFactor);
        capacity = newCap;

        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            if (k != EMPTY_KEY) {
                Object val = oldValues[i];
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
