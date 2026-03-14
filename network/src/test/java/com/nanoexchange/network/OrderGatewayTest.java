package com.nanoexchange.network;

import com.nanoexchange.engine.ExecutionReport;
import com.nanoexchange.engine.Order;
import com.nanoexchange.network.protocol.WireCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class OrderGatewayTest {

    private OrderGateway gateway;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws IOException {
        // subclass set up per test so the handler can be customized
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gateway != null) {
            gateway.stop();
            gateway.close();
        }
        if (serverThread != null) {
            serverThread.join(2000L);
        }
    }

    private void start(OrderGateway.InboundHandler handler) throws IOException {
        gateway = new OrderGateway(new InetSocketAddress("127.0.0.1", 0), handler);
        serverThread = new Thread(() -> {
            try { gateway.runForever(); } catch (IOException ignored) { }
        }, "gateway-test");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private SocketChannel connect() throws IOException {
        InetSocketAddress bound = gateway.boundAddress();
        SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", bound.getPort()));
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        client.configureBlocking(true);
        return client;
    }

    @Test
    void connectAndDisconnectNotifiesHandler() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        start(new OrderGateway.InboundHandler() {
            @Override public void onConnect(ClientSession s) { connected.countDown(); }
            @Override public void onDisconnect(ClientSession s) { disconnected.countDown(); }
        });

        SocketChannel c = connect();
        try {
            assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            c.close();
        }
        assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void newOrderFramesAreParsedAndDelivered() throws Exception {
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        CountDownLatch threeDecoded = new CountDownLatch(3);
        start(new OrderGateway.InboundHandler() {
            @Override
            public void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                                   long price, long quantity, long displaySize, long timestampNanos) {
                received.add("c=" + clientId + " o=" + orderId + " p=" + price + " q=" + quantity);
                threeDecoded.countDown();
            }
        });

        try (SocketChannel c = connect()) {
            WireCodec codec = new WireCodec();
            ByteBuffer out = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
            for (long id = 1; id <= 3; id++) {
                Order o = new Order();
                o.reset(id, 99L, Order.SIDE_BUY, Order.TYPE_LIMIT, 100_00000000L + id, 10L, 0L);
                codec.encodeNewOrder(o, out);
            }
            out.flip();
            while (out.hasRemaining()) c.write(out);

            assertThat(threeDecoded.await(2, TimeUnit.SECONDS)).isTrue();
        }

        // session clientId is authoritative; wire clientId was 99 but the gateway substitutes
        // the session-assigned one (monotonic from 1)
        assertThat(received).hasSize(3);
        assertThat(received.peek()).doesNotContain("c=99");
    }

    @Test
    void thousandOrdersAllDelivered() throws Exception {
        final int N = 1000;
        AtomicInteger count = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        start(new OrderGateway.InboundHandler() {
            @Override
            public void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                                   long price, long quantity, long displaySize, long timestampNanos) {
                if (count.incrementAndGet() == N) done.countDown();
            }
        });

        try (SocketChannel c = connect()) {
            WireCodec codec = new WireCodec();
            ByteBuffer out = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < N; i++) {
                if (out.remaining() < 128) {
                    out.flip();
                    while (out.hasRemaining()) c.write(out);
                    out.clear();
                }
                Order o = new Order();
                o.reset(1000L + i, 7L, Order.SIDE_BUY, Order.TYPE_LIMIT, 100L, 1L, 0L);
                codec.encodeNewOrder(o, out);
            }
            out.flip();
            while (out.hasRemaining()) c.write(out);

            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count.get()).isEqualTo(N);
    }

    @Test
    void outboundReportIsDeliveredToClient() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        // remember the session so the test thread can drive outbound frames through it
        ClientSession[] sessionSlot = new ClientSession[1];
        start(new OrderGateway.InboundHandler() {
            @Override
            public void onConnect(ClientSession s) {
                sessionSlot[0] = s;
                connected.countDown();
            }
        });

        try (SocketChannel c = connect()) {
            assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();

            ExecutionReport r = new ExecutionReport();
            r.reportType = ExecutionReport.TYPE_ACK;
            r.orderId = 42L;
            r.clientId = sessionSlot[0].clientId();
            r.side = Order.SIDE_BUY;
            r.price = 100L;
            r.remainingQuantity = 10L;
            r.timestampNanos = 1L;

            ByteBuffer frame = OrderGateway.newEncodingBuffer(256);
            new WireCodec().encodeExecutionReport(r, frame);
            frame.flip();

            // send from the test thread — inspired by how the engine thread will hand off
            // completed reports. send() queues and flushes; flushWrite is non-blocking.
            gateway.send(sessionSlot[0], frame);
            gateway.wakeup();

            // client reads back the frame
            ByteBuffer in = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (in.position() < 4 && System.nanoTime() < deadline) {
                c.read(in);
            }
            int total = 4 + in.getInt(0);
            while (in.position() < total && System.nanoTime() < deadline) {
                c.read(in);
            }
            in.flip();

            CaptureSink cap = new CaptureSink();
            assertThat(new WireCodec().tryDecode(in, cap)).isEqualTo(WireCodec.DECODED);
            assertThat(cap.lastReportType).isEqualTo(ExecutionReport.TYPE_ACK);
            assertThat(cap.lastOrderId).isEqualTo(42L);
        }
    }

    @Test
    void malformedFrameClosesConnection() throws Exception {
        CountDownLatch errored = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        start(new OrderGateway.InboundHandler() {
            @Override public void onProtocolError(ClientSession s) { errored.countDown(); }
            @Override public void onDisconnect(ClientSession s) { disconnected.countDown(); }
        });

        try (SocketChannel c = connect()) {
            ByteBuffer garbage = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
            // valid-looking length, invalid CRC
            garbage.putInt(10);
            for (int i = 0; i < 10; i++) garbage.put((byte) i);
            garbage.flip();
            c.write(garbage);
            assertThat(errored.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(disconnected.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class CaptureSink implements WireCodec.DecodedSink {
        byte lastReportType;
        long lastOrderId;
        @Override
        public void onExecutionReport(byte reportType, long orderId, long clientId, byte side,
                                      long price, long executedQuantity, long executedPrice,
                                      long remainingQuantity, long counterpartyOrderId,
                                      byte rejectReason, long timestampNanos) {
            lastReportType = reportType;
            lastOrderId = orderId;
        }
    }
}
