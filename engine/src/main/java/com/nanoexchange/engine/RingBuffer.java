package com.nanoexchange.engine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Single-producer / single-consumer bounded ring buffer, modeled on the LMAX Disruptor.
 * Hands off references between exactly one producer thread (typically the network gateway)
 * and exactly one consumer thread (the matching engine). Wait-free on the happy path.
 *
 * <h2>Memory layout</h2>
 * The producer and consumer sequences each live in their own "island" of long fields padded
 * with 7 longs on each side (≈ one cache line). Adjacent cache-line-sized islands eliminate
 * false sharing between the two sequences — without padding, a write from the producer would
 * invalidate the consumer's L1 copy of its own sequence and vice versa, producing a steady
 * stream of coherence traffic even though logically the two sides never touch the same data.
 *
 * <p>Each side also caches the opposite side's sequence locally and only refreshes the
 * authoritative value when the cached one would indicate a block. On a healthy steady state
 * where producer and consumer keep pace, cross-thread reads of the other side's sequence
 * happen rarely — once per "catch up" — rather than on every enqueue/dequeue.
 *
 * <h2>Memory ordering</h2>
 * Writes to a sequence use release semantics; reads use acquire. That establishes the
 * happens-before edge: anything the producer wrote to the slot before publishing the sequence
 * is visible to the consumer once the consumer observes that sequence. The slot array itself
 * is written with plain stores — no per-slot atomic overhead.
 *
 * @param <E> element reference type; typically a pooled engine event
 */
public final class RingBuffer<E> {

    private static final VarHandle PROD_SEQ;
    private static final VarHandle CONS_SEQ;
    static {
        try {
            PROD_SEQ = MethodHandles.lookup()
                    .findVarHandle(RingBuffer.class, "producerSeq", long.class);
            CONS_SEQ = MethodHandles.lookup()
                    .findVarHandle(RingBuffer.class, "consumerSeq", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int capacity;
    private final int mask;
    private final Object[] slots;

    // ---- producer island (written by producer, read by consumer) --------------------------
    @SuppressWarnings("unused") private long pp1, pp2, pp3, pp4, pp5, pp6, pp7;
    private long producerSeq = -1L;
    @SuppressWarnings("unused") private long pp8, pp9, pp10, pp11, pp12, pp13, pp14;

    // Producer-local cache of the consumerSeq (mutated only by the producer).
    private long cachedConsumerSeq = -1L;
    @SuppressWarnings("unused") private long pc1, pc2, pc3, pc4, pc5, pc6, pc7;

    // ---- consumer island (written by consumer, read by producer) --------------------------
    private long consumerSeq = -1L;
    @SuppressWarnings("unused") private long cc1, cc2, cc3, cc4, cc5, cc6, cc7;

    // Consumer-local cache of producerSeq (mutated only by the consumer).
    private long cachedProducerSeq = -1L;
    @SuppressWarnings("unused") private long ccp1, ccp2, ccp3, ccp4, ccp5, ccp6, ccp7;

    /**
     * Create a ring of at least {@code requestedCapacity} slots, rounded up to a power of two.
     */
    public RingBuffer(int requestedCapacity) {
        if (requestedCapacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2");
        }
        this.capacity = nextPowerOfTwo(requestedCapacity);
        this.mask = capacity - 1;
        this.slots = new Object[capacity];
    }

    public int capacity() {
        return capacity;
    }

    // =========================================================================================
    // producer-side API
    // =========================================================================================

    /**
     * Publish {@code item}, spinning until the consumer has drained enough space. Never
     * allocates. Must be called from the single producer thread.
     *
     * @param item element to publish; must not be {@code null}
     * @throws NullPointerException if item is null
     */
    public void publish(E item) {
        if (item == null) {
            throw new NullPointerException("null item");
        }
        final long next = producerSeq + 1L;
        final long wrapPoint = next - capacity;
        if (wrapPoint > cachedConsumerSeq) {
            long observed;
            while ((observed = (long) CONS_SEQ.getAcquire(this)) < wrapPoint) {
                Thread.onSpinWait();
            }
            cachedConsumerSeq = observed;
        }
        slots[(int) (next & mask)] = item;
        PROD_SEQ.setRelease(this, next);
        producerSeq = next;
    }

    /**
     * Try to publish without blocking. Returns {@code false} if the ring is full.
     */
    public boolean tryPublish(E item) {
        if (item == null) throw new NullPointerException("null item");
        final long next = producerSeq + 1L;
        final long wrapPoint = next - capacity;
        if (wrapPoint > cachedConsumerSeq) {
            cachedConsumerSeq = (long) CONS_SEQ.getAcquire(this);
            if (wrapPoint > cachedConsumerSeq) {
                return false;
            }
        }
        slots[(int) (next & mask)] = item;
        PROD_SEQ.setRelease(this, next);
        producerSeq = next;
        return true;
    }

    // =========================================================================================
    // consumer-side API
    // =========================================================================================

    /**
     * Consume the next item, or {@code null} if the ring is empty. Must be called from the
     * single consumer thread.
     */
    @SuppressWarnings("unchecked")
    public E poll() {
        final long want = consumerSeq + 1L;
        if (want > cachedProducerSeq) {
            cachedProducerSeq = (long) PROD_SEQ.getAcquire(this);
            if (want > cachedProducerSeq) {
                return null;
            }
        }
        int idx = (int) (want & mask);
        Object item = slots[idx];
        slots[idx] = null;
        CONS_SEQ.setRelease(this, want);
        consumerSeq = want;
        return (E) item;
    }

    /**
     * Consume the next item, blocking with {@link Thread#onSpinWait()} until one is available.
     */
    @SuppressWarnings("unchecked")
    public E take() {
        final long want = consumerSeq + 1L;
        if (want > cachedProducerSeq) {
            long observed;
            while ((observed = (long) PROD_SEQ.getAcquire(this)) < want) {
                Thread.onSpinWait();
            }
            cachedProducerSeq = observed;
        }
        int idx = (int) (want & mask);
        Object item = slots[idx];
        slots[idx] = null;
        CONS_SEQ.setRelease(this, want);
        consumerSeq = want;
        return (E) item;
    }

    /**
     * Observational size: approximate number of items currently pending. May be stale the
     * moment it is read; intended for tests and metrics.
     */
    public int size() {
        long p = (long) PROD_SEQ.getAcquire(this);
        long c = (long) CONS_SEQ.getAcquire(this);
        long diff = p - c;
        if (diff < 0) return 0;
        if (diff > capacity) return capacity;
        return (int) diff;
    }

    private static int nextPowerOfTwo(int n) {
        if (n <= 1) return 1;
        return Integer.highestOneBit(n - 1) << 1;
    }
}
