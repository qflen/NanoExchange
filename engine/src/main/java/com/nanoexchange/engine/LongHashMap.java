package com.nanoexchange.engine;

/**
 * Open-addressing hash map with primitive {@code long} keys and object values. Built because
 * {@link java.util.HashMap HashMap&lt;Long, V&gt;} boxes every key on every operation,
 * producing per-lookup garbage that is unacceptable on a matching-engine hot path.
 *
 * <p>Design choices:
 * <ul>
 *   <li><b>Linear probing with tombstones.</b> Simpler and more cache-friendly than chained
 *       hashing; single memory region for keys, no per-entry node allocation.</li>
 *   <li><b>Load factor 0.5.</b> Aggressive but deliberate — primary probe length grows
 *       super-linearly past 0.7 for linear probing, and the memory doubled for the lower
 *       load factor is a good trade for tail-latency stability. Documented in ADR-002.</li>
 *   <li><b>Power-of-two capacity.</b> Index is {@code hash & mask}, one AND instead of a
 *       modulo.</li>
 *   <li><b>Key {@code 0} is reserved</b> as the "empty slot" sentinel. Callers must not
 *       insert zero-valued keys; attempts are caught by an {@code assert} (production code
 *       should run with assertions disabled, but during development we catch misuse loudly).
 *       All order IDs are assigned from a monotonic counter starting at 1, so this is a
 *       non-issue in practice.</li>
 *   <li><b>Tombstones</b> are represented by a private singleton sentinel stored in the
 *       values array (not the keys array). This keeps the key comparison a straight
 *       {@code ==} without a side-channel lookup into a separate state table.</li>
 * </ul>
 *
 * <p>Not thread-safe. The matching engine is single-threaded; off-engine code should not
 * touch this map directly.
 *
 * @param <V> value type
 */
public final class LongHashMap<V> {

    /** Reserved key value; attempts to insert this key fail an assertion. */
    public static final long RESERVED_KEY = 0L;

    private static final Object TOMBSTONE = new Object();
    private static final int MIN_CAPACITY = 8;

    private long[] keys;
    private Object[] values;
    private int mask;
    private int size;
    private int occupiedSlots;
    private int resizeThreshold;

    /** Create a map with a default initial capacity. */
    public LongHashMap() {
        this(MIN_CAPACITY);
    }

    /**
     * Create a map sized for at least {@code expectedEntries} without triggering a resize.
     *
     * @param expectedEntries expected live-entry count; capacity is derived to keep load ≤ 0.5
     */
    public LongHashMap(int expectedEntries) {
        if (expectedEntries < 1) expectedEntries = 1;
        int minCap = Math.max(MIN_CAPACITY, expectedEntries * 2);
        int cap = nextPowerOfTwo(minCap);
        this.keys = new long[cap];
        this.values = new Object[cap];
        this.mask = cap - 1;
        this.resizeThreshold = cap >>> 1;
    }

    /** @return live entry count (excludes tombstones). */
    public int size() {
        return size;
    }

    /** @return {@code true} if no live entries. */
    public boolean isEmpty() {
        return size == 0;
    }

    /** @return backing array length; power of two, always ≥ 2 × {@link #size()}. */
    public int capacity() {
        return keys.length;
    }

    /**
     * Look up a value by key.
     *
     * @param key long key, must not equal {@link #RESERVED_KEY}
     * @return mapped value, or {@code null} if the key is absent
     */
    @SuppressWarnings("unchecked")
    public V get(long key) {
        assert key != RESERVED_KEY : "key 0 is reserved";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == RESERVED_KEY) return null;
            Object v = values[idx];
            if (v != TOMBSTONE && k == key) return (V) v;
            idx = (idx + 1) & mask;
        }
    }

    /** @return whether {@code key} is present. */
    public boolean containsKey(long key) {
        return get(key) != null;
    }

    /**
     * Insert or replace a mapping.
     *
     * @param key   long key, must not equal {@link #RESERVED_KEY}
     * @param value non-null value
     * @return previous value mapped to {@code key}, or {@code null} if none
     */
    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        assert key != RESERVED_KEY : "key 0 is reserved";
        if (value == null) throw new NullPointerException("value must not be null");
        if (occupiedSlots >= resizeThreshold) {
            resize(keys.length << 1);
        }
        int idx = index(key);
        int firstTombstone = -1;
        while (true) {
            long k = keys[idx];
            if (k == RESERVED_KEY) {
                if (firstTombstone >= 0) {
                    keys[firstTombstone] = key;
                    values[firstTombstone] = value;
                } else {
                    keys[idx] = key;
                    values[idx] = value;
                    occupiedSlots++;
                }
                size++;
                return null;
            }
            Object v = values[idx];
            if (v == TOMBSTONE) {
                if (firstTombstone < 0) firstTombstone = idx;
            } else if (k == key) {
                values[idx] = value;
                return (V) v;
            }
            idx = (idx + 1) & mask;
        }
    }

    /**
     * Remove a mapping by key.
     *
     * @param key long key, must not equal {@link #RESERVED_KEY}
     * @return removed value, or {@code null} if absent
     */
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        assert key != RESERVED_KEY : "key 0 is reserved";
        int idx = index(key);
        while (true) {
            long k = keys[idx];
            if (k == RESERVED_KEY) return null;
            Object v = values[idx];
            if (v != TOMBSTONE && k == key) {
                values[idx] = TOMBSTONE;
                size--;
                return (V) v;
            }
            idx = (idx + 1) & mask;
        }
    }

    /** Remove all entries. Backing arrays are retained at their current size. */
    public void clear() {
        java.util.Arrays.fill(keys, RESERVED_KEY);
        java.util.Arrays.fill(values, null);
        size = 0;
        occupiedSlots = 0;
    }

    private int index(long key) {
        return (int) (mix(key) & mask);
    }

    /** Thomas Wang's long hash — cheap and well-distributed across its output bits. */
    private static long mix(long key) {
        key = (~key) + (key << 18);
        key = key ^ (key >>> 31);
        key = key * 21;
        key = key ^ (key >>> 11);
        key = key + (key << 6);
        key = key ^ (key >>> 22);
        return key;
    }

    @SuppressWarnings("unchecked")
    private void resize(int newCapacity) {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        this.keys = new long[newCapacity];
        this.values = new Object[newCapacity];
        this.mask = newCapacity - 1;
        this.resizeThreshold = newCapacity >>> 1;
        this.size = 0;
        this.occupiedSlots = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            long k = oldKeys[i];
            Object v = oldValues[i];
            if (k != RESERVED_KEY && v != TOMBSTONE && v != null) {
                put(k, (V) v);
            }
        }
    }

    private static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        return Integer.highestOneBit(n - 1) << 1;
    }
}
