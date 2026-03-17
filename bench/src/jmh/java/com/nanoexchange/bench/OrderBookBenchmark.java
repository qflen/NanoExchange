package com.nanoexchange.bench;

import com.nanoexchange.engine.ExecutionReport;
import com.nanoexchange.engine.ExecutionSink;
import com.nanoexchange.engine.MatchingEngine;
import com.nanoexchange.engine.Order;
import com.nanoexchange.engine.OrderBook;

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

import java.util.concurrent.TimeUnit;

/**
 * Matching-engine throughput and latency benchmarks.
 *
 * <p>Two scenarios:
 * <ul>
 *   <li>{@link InsertState#restingInsert}: a new limit order at a non-crossing price.</li>
 *   <li>{@link SweepState#marketSweep5Levels}: a market order that sweeps five levels.</li>
 * </ul>
 *
 * <p>Each scenario has a dedicated {@link State} class so its {@code @Setup(Level.Invocation)}
 * can reset the book to a consistent shape before every single invocation; otherwise the book
 * would grow unboundedly and benchmarks would measure the wrong thing.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
public class OrderBookBenchmark {

    private static final class NullSink implements ExecutionSink {
        @Override
        public void onExecutionReport(ExecutionReport report) {
            // measure engine cost, not sink cost
        }
    }

    @State(Scope.Thread)
    public static class InsertState {
        OrderBook book;
        MatchingEngine engine;
        long nextOrderId;
        final NullSink sink = new NullSink();
        final Order[] orderPool = new Order[1 << 16];
        int orderPoolIdx;

        @Setup(Level.Trial)
        public void trial() {
            for (int i = 0; i < orderPool.length; i++) orderPool[i] = new Order();
        }

        @Setup(Level.Invocation)
        public void perInvocation() {
            book = new OrderBook(64, 4096);
            engine = new MatchingEngine(book, 4096);
            nextOrderId = 1;
            orderPoolIdx = 0;
        }

        Order nextOrder() {
            Order o = orderPool[orderPoolIdx];
            orderPoolIdx = (orderPoolIdx + 1) & (orderPool.length - 1);
            return o;
        }
    }

    @State(Scope.Thread)
    public static class SweepState {
        OrderBook book;
        MatchingEngine engine;
        long nextOrderId;
        final NullSink sink = new NullSink();
        final Order[] orderPool = new Order[1 << 16];
        int orderPoolIdx;

        @Setup(Level.Trial)
        public void trial() {
            for (int i = 0; i < orderPool.length; i++) orderPool[i] = new Order();
        }

        @Setup(Level.Invocation)
        public void perInvocation() {
            book = new OrderBook(64, 4096);
            engine = new MatchingEngine(book, 4096);
            nextOrderId = 1;
            orderPoolIdx = 0;
            // Populate 10 ask levels, 5 orders each. Keep the client IDs off taker's
            // id so self-trade prevention doesn't kick in during the sweep.
            for (int lvl = 0; lvl < 10; lvl++) {
                long price = 100_00000000L + (long) lvl * 1_00000000L;
                for (int i = 0; i < 5; i++) {
                    Order o = nextOrder();
                    o.reset(nextOrderId++, 1L + (i % 3), Order.SIDE_SELL,
                            Order.TYPE_LIMIT, price, 10L, 0L);
                    engine.submit(o, sink);
                }
            }
        }

        Order nextOrder() {
            Order o = orderPool[orderPoolIdx];
            orderPoolIdx = (orderPoolIdx + 1) & (orderPool.length - 1);
            return o;
        }
    }

    @Benchmark
    public void restingInsert(InsertState s, Blackhole bh) {
        Order o = s.nextOrder();
        o.reset(s.nextOrderId++, 1L, Order.SIDE_BUY, Order.TYPE_LIMIT,
                100_00000000L, 10L, 0L);
        s.engine.submit(o, s.sink);
        bh.consume(s.book.bidLevelCount());
    }

    @Benchmark
    public void marketSweep5Levels(SweepState s, Blackhole bh) {
        Order taker = s.nextOrder();
        taker.reset(s.nextOrderId++, 99L, Order.SIDE_BUY, Order.TYPE_MARKET,
                Long.MAX_VALUE, 50L /* sweeps 5 levels at depth 10 each */, 0L);
        s.engine.submit(taker, s.sink);
        bh.consume(s.book.askLevelCount());
    }
}
