package com.nanoexchange.bench;

import com.nanoexchange.engine.RingBuffer;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Single-producer/single-consumer throughput for the custom {@link RingBuffer}, alongside a
 * baseline against {@link ArrayBlockingQueue} of the same capacity. The SPSC ring should be
 * an order of magnitude faster on the producer side and allocation-free — the primary point
 * of the comparison.
 *
 * <p>Both variants share the same test rig: producer publishes a constant long-valued payload
 * (Long-boxed for ABQ, a pooled wrapper for the ring), consumer drains and hands to a
 * blackhole. A spin is used on empty to avoid blocking-semantics overhead on ABQ's side that
 * would muddy the comparison.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
public class RingBufferBenchmark {

    public static final int CAPACITY = 1024;

    public static final class Msg {
        public long value;
    }

    @State(Scope.Group)
    public static class RingState {
        final RingBuffer<Msg> ring = new RingBuffer<>(CAPACITY);
        // Pre-allocated payloads so the producer allocates zero.
        final Msg[] pool = new Msg[CAPACITY];
        int idx;

        @Setup
        public void setup() {
            for (int i = 0; i < pool.length; i++) pool[i] = new Msg();
        }
    }

    @State(Scope.Group)
    public static class ABQState {
        final ArrayBlockingQueue<Long> q = new ArrayBlockingQueue<>(CAPACITY);
    }

    // --- custom ring ---------------------------------------------------------------------------

    @Benchmark
    @Group("spsc_ring")
    @GroupThreads(1)
    public void ringProduce(RingState s) {
        Msg m = s.pool[s.idx];
        s.idx = (s.idx + 1) & (CAPACITY - 1);
        m.value++;
        // spin until there is room
        while (!s.ring.tryPublish(m)) Thread.onSpinWait();
    }

    @Benchmark
    @Group("spsc_ring")
    @GroupThreads(1)
    public void ringConsume(RingState s, Blackhole bh) {
        Msg m;
        while ((m = s.ring.poll()) == null) Thread.onSpinWait();
        bh.consume(m.value);
    }

    // --- ArrayBlockingQueue baseline -----------------------------------------------------------

    @Benchmark
    @Group("abq")
    @GroupThreads(1)
    public void abqProduce(ABQState s) throws InterruptedException {
        s.q.put(1L);
    }

    @Benchmark
    @Group("abq")
    @GroupThreads(1)
    public void abqConsume(ABQState s, Blackhole bh) throws InterruptedException {
        bh.consume(s.q.take());
    }
}
