package com.nanoexchange.bench;

import com.nanoexchange.engine.LongHashMap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Order-lookup hot path: the engine does a {@code get(orderId)} on every cancel and modify.
 * Real order IDs arrive approximately monotonically (gateway assigns them in sequence), so
 * the benchmark workload uses a monotonic stream of 10k pre-populated keys rather than
 * uniform random — uniform random is kind to both implementations and doesn't reflect reality.
 *
 * <p>Three operations are measured: {@code get}, {@code put}, {@code remove}. The expectation
 * (to be confirmed by numbers) is that {@link LongHashMap} beats {@link HashMap}{@code <Long, V>}
 * on all three due to avoiding the {@link Long} box allocation on every call.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
public class HashMapBenchmark {

    public static final int N = 10_000;

    private LongHashMap<Object> longMap;
    private HashMap<Long, Object> boxedMap;
    private long[] keys;
    private final Object value = new Object();
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        longMap = new LongHashMap<>(N * 2);
        boxedMap = new HashMap<>(N * 2);
        keys = new long[N];
        for (int i = 0; i < N; i++) {
            long id = 1_000_000L + i; // monotonic, large-ish to avoid small-long cache hit
            keys[i] = id;
            longMap.put(id, value);
            boxedMap.put(id, value);
        }
    }

    @Benchmark
    public void longMapGet(Blackhole bh) {
        long k = keys[cursor];
        cursor = (cursor + 1) % N;
        bh.consume(longMap.get(k));
    }

    @Benchmark
    public void boxedMapGet(Blackhole bh) {
        long k = keys[cursor];
        cursor = (cursor + 1) % N;
        bh.consume(boxedMap.get(k));
    }

    // Put and remove mutate state, which would grow or shrink the map under test. Keep the
    // map size stable by removing the key we just inserted (put/remove as a pair per invocation
    // would double the work, which is fine — the ratio between the two implementations is the
    // point of comparison).
    @Benchmark
    public void longMapPutRemove() {
        long k = Long.MIN_VALUE + cursor;
        cursor = (cursor + 1) % N;
        longMap.put(k, value);
        longMap.remove(k);
    }

    @Benchmark
    public void boxedMapPutRemove() {
        long k = Long.MIN_VALUE + cursor;
        cursor = (cursor + 1) % N;
        boxedMap.put(k, value);
        boxedMap.remove(k);
    }
}
