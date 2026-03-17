package com.nanoexchange.bench;

import com.nanoexchange.engine.ExecutionReport;
import com.nanoexchange.engine.Order;
import com.nanoexchange.network.protocol.MessageType;
import com.nanoexchange.network.protocol.WireCodec;

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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

/**
 * Wire-protocol encode and decode throughput. The two hot frames in the system are:
 * <ul>
 *   <li>NEW_ORDER: the inbound frame from every client order</li>
 *   <li>exec report (ACK / FILL / PARTIAL): the outbound frame from every matching event,
 *       potentially more than one per inbound order</li>
 * </ul>
 *
 * <p>Buffers are pre-allocated direct ByteBuffers matching the production configuration.
 * The benchmark measures codec cost only — no socket I/O.
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(3)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
public class SerializationBenchmark {

    private WireCodec codec;
    private Order order;
    private ExecutionReport report;
    private ByteBuffer encodeBuf;
    private ByteBuffer decodeBuf;
    private final WireCodec.DecodedSink sink = new WireCodec.DecodedSink() {
        @Override
        public void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                               long price, long quantity, long displaySize, long timestampNanos) {
            // consume (no-op)
        }
        @Override public void onCancel(long orderId, long ts) { }
        @Override public void onModify(long orderId, long newPrice, long newQty, long ts) { }
        @Override public void onHeartbeat() { }
    };

    @Setup(Level.Trial)
    public void setup() {
        codec = new WireCodec();
        order = new Order();
        order.reset(1L, 7L, Order.SIDE_BUY, Order.TYPE_LIMIT, 100_00000000L, 100L, 123_456L);
        report = new ExecutionReport();
        report.reportType = ExecutionReport.TYPE_PARTIAL_FILL;
        report.orderId = 1L;
        report.clientId = 7L;
        report.side = Order.SIDE_BUY;
        report.price = 100_00000000L;
        report.executedQuantity = 50L;
        report.executedPrice = 100_00000000L;
        report.remainingQuantity = 50L;
        report.counterpartyOrderId = 42L;
        report.timestampNanos = 1_234_567_890L;

        encodeBuf = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
        // Pre-encode one NEW_ORDER for the decode benchmark.
        decodeBuf = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN);
        codec.encodeNewOrder(order, decodeBuf);
        decodeBuf.flip();
    }

    @Benchmark
    public void encodeNewOrder(Blackhole bh) {
        encodeBuf.clear();
        codec.encodeNewOrder(order, encodeBuf);
        bh.consume(encodeBuf.position());
    }

    @Benchmark
    public void encodeExecutionReport(Blackhole bh) {
        encodeBuf.clear();
        codec.encodeExecutionReport(report, encodeBuf);
        bh.consume(encodeBuf.position());
    }

    @Benchmark
    public void decodeNewOrder(Blackhole bh) {
        decodeBuf.position(0);
        int r = codec.tryDecode(decodeBuf, sink);
        bh.consume(r);
    }
}
