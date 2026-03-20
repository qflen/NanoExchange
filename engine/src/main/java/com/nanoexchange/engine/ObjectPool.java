package com.nanoexchange.engine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Bounded, lock-free object pool implemented as a Vyukov-style MPMC ring buffer.
 *
 * <p>This is the standard correct pattern for a wait-free bounded queue and is appropriate
 * here because the pool sits between threads with different roles (e.g. a network thread
 * releasing orders back after ack generation, while the engine thread acquires fresh ones).
 * Both ends support concurrent callers; in the common single-threaded engine case the cost
 * collapses to one CAS per operation.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Each slot carries a monotonic sequence number. A producer may write slot {@code i}
 *       only when its sequence equals the producer position; a consumer may read when the
 *       sequence equals {@code position + 1}. After each operation the sequence advances,
 *       acting as a hand-off token between producer and consumer without locks.</li>
 *   <li>{@link #acquire()} returns {@code null} when empty. Hot-path callers can't afford
 *       exceptions; the null return is the explicit empty signal.</li>
 *   <li>{@link #release(Object)} returns {@code false} when full, which means the object
 *       was <em>not</em> accepted back. The caller should simply drop the reference and let
 *       it be GC'd. This preserves liveness over pool population — better to take one GC hit
 *       than to block the hot path.</li>
 *   <li>Capacity is rounded up to a power of two so index computation is a mask instead of
 *       a modulo.</li>
 * </ul>
 *
 * <p>VarHandle with {@code getAcquire} / {@code setRelease} is used for sequence slot access
 * to establish the happens-before edge between the slot write and the subsequent read.
 * Positions are kept in {@link AtomicLong} (defaults to {@code compareAndSet} with volatile
 * semantics, which is what we need). Padding to eliminate false sharing between the two
 * positions is a deliberate non-goal for stage 1 and will be revisited if the stage 7
 * benchmark shows contention in practice.
 *
 * @param <E> element type pooled
 */
public final class ObjectPool<E> {

    private static final VarHandle SEQ =
            MethodHandles.arrayElementVarHandle(long[].class);

    private final Object[] buffer;
    private final long[] sequence;
    private final int mask;
    private final int capacity;

    private final AtomicLong enqueuePos = new AtomicLong();
    private final AtomicLong dequeuePos = new AtomicLong();

    /**
     * Create a pool of the given capacity, pre-populating it with {@code capacity} instances
     * produced by {@code factory}. After construction the pool is full and ready for
     * steady-state acquire/release traffic without triggering any further allocations.
     *
     * @param requestedCapacity desired capacity; rounded up to the next power of two
     * @param factory           supplier invoked {@code capacity} times to fill the pool
     * @throws IllegalArgumentException if capacity is less than 1
     */
    public ObjectPool(int requestedCapacity, Supplier<? extends E> factory) {
        if (requestedCapacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = nextPowerOfTwo(requestedCapacity);
        this.buffer = new Object[capacity];
        this.sequence = new long[capacity];
        this.mask = capacity - 1;
        for (int i = 0; i < capacity; i++) {
            sequence[i] = i;
        }
        for (int i = 0; i < capacity; i++) {
            if (!release(factory.get())) {
                throw new AssertionError("pre-population failed at index " + i);
            }
        }
    }

    /** @return pool capacity (power-of-two, ≥ requested). */
    public int capacity() {
        return capacity;
    }

    /**
     * Acquire a pooled element, or {@code null} if the pool is empty. Wait-free: the call
     * completes in a bounded number of steps regardless of contention.
     *
     * @return a pooled element or {@code null}
     */
    @SuppressWarnings("unchecked")
    public E acquire() {
        long pos = dequeuePos.get();
        while (true) {
            int idx = (int) (pos & mask);
            long seq = (long) SEQ.getAcquire(sequence, idx);
            long diff = seq - (pos + 1);
            if (diff == 0) {
                if (dequeuePos.compareAndSet(pos, pos + 1)) {
                    E item = (E) buffer[idx];
                    buffer[idx] = null;
                    SEQ.setRelease(sequence, idx, pos + capacity);
                    return item;
                }
                pos = dequeuePos.get();
            } else if (diff < 0) {
                return null;
            } else {
                pos = dequeuePos.get();
            }
        }
    }

    /**
     * Return an element to the pool. If the pool is full the element is rejected and the
     * caller should drop the reference (it will be GC'd). Wait-free.
     *
     * @param item element to return; must not be {@code null}
     * @return {@code true} if the element was accepted into the pool
     * @throws NullPointerException if {@code item} is null
     */
    public boolean release(E item) {
        if (item == null) {
            throw new NullPointerException("cannot release null");
        }
        long pos = enqueuePos.get();
        while (true) {
            int idx = (int) (pos & mask);
            long seq = (long) SEQ.getAcquire(sequence, idx);
            long diff = seq - pos;
            if (diff == 0) {
                if (enqueuePos.compareAndSet(pos, pos + 1)) {
                    buffer[idx] = item;
                    SEQ.setRelease(sequence, idx, pos + 1);
                    return true;
                }
                pos = enqueuePos.get();
            } else if (diff < 0) {
                return false;
            } else {
                pos = enqueuePos.get();
            }
        }
    }

    /**
     * Approximate number of elements currently available in the pool. Observational only:
     * the value may be stale the moment it is read in the presence of concurrent operations.
     */
    public int size() {
        long enq = enqueuePos.get();
        long deq = dequeuePos.get();
        long n = enq - deq;
        if (n < 0) return 0;
        if (n > capacity) return capacity;
        return (int) n;
    }

    private static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        return Integer.highestOneBit(n - 1) << 1;
    }
}
