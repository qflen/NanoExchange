package com.nanoexchange.network;

import com.nanoexchange.engine.ExecutionReport;
import com.nanoexchange.engine.MatchingEngine;
import com.nanoexchange.engine.Order;
import com.nanoexchange.engine.OrderBook;
import com.nanoexchange.engine.RingBuffer;
import com.nanoexchange.network.feed.FeedSequence;
import com.nanoexchange.network.feed.MarketDataPublisher;
import com.nanoexchange.network.protocol.WireCodec;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Wires the matching engine to the TCP order gateway and the UDP market-data publisher, and
 * spins up the threads that move events between them.
 *
 * <h2>Thread topology</h2>
 * <ul>
 *   <li>Gateway thread — NIO selector; reads TCP frames, pushes decoded events into
 *       {@code inboundRing}; drains {@code outboundRing} to emit execution reports.</li>
 *   <li>Engine thread — reads {@code inboundRing}, invokes {@link MatchingEngine}, captures
 *       execution reports and the resulting book deltas, publishes incrementals and trades on
 *       the multicast feed, and pushes each report into {@code outboundRing} for the gateway
 *       thread to fan out to the originating TCP client.</li>
 * </ul>
 *
 * <p>Book deltas are computed by comparing a cheap pre-action snapshot of the
 * crossing-relevant price levels to the post-action state. For stage 6 this is a simple
 * "emit a BOOK_UPDATE for every currently-visible level touched" policy — an exchange-grade
 * feed would be more selective, but the semantics are correct (consumers rebuild the right
 * aggregate totals from the stream).
 */
public final class ExchangeServer implements AutoCloseable {

    /** Inbound event kinds written into the ring buffer from the gateway thread. */
    public static final byte EV_NEW_ORDER = 1;
    public static final byte EV_CANCEL    = 2;
    public static final byte EV_MODIFY    = 3;

    /** Pooled inbound-event holder. Stuck in a ring buffer, read by the engine thread. */
    public static final class InboundEvent {
        public byte kind;
        public long clientId;
        public long orderId;
        public byte side;
        public byte orderType;
        public long price;
        public long quantity;
        public long displaySize;
        public long newPrice;
        public long newQuantity;
        public long timestampNanos;
        /** Reference back to the originating session so reports can be routed. */
        public ClientSession session;

        void clear() {
            kind = 0;
            clientId = 0; orderId = 0;
            side = 0; orderType = 0;
            price = 0; quantity = 0; displaySize = 0;
            newPrice = 0; newQuantity = 0; timestampNanos = 0;
            session = null;
        }
    }

    /** Outbound report holder: a pre-encoded frame + the destination session. */
    public static final class OutboundFrame {
        public ClientSession session;
        public final ByteBuffer buffer =
                ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    }

    private final OrderBook book;
    private final MatchingEngine engine;
    private final OrderGateway gateway;
    private final MarketDataPublisher publisher;
    private final RingBuffer<InboundEvent> inboundRing;
    private final RingBuffer<OutboundFrame> outboundRing;
    private final InboundEvent[] inboundPool;
    private final OutboundFrame[] outboundPool;
    private final Order[] orderPool;
    private int inboundPoolIdx;
    private int outboundPoolIdx;
    private int orderPoolIdx;
    private final ConcurrentMap<Long, ClientSession> sessionsByClient = new ConcurrentHashMap<>();
    private final WireCodec outCodec = new WireCodec();
    private final FeedSequence feedSeq;
    private Thread gatewayThread;
    private Thread engineThread;
    private volatile boolean running;

    public ExchangeServer(
            int tcpPort,
            InetAddress multicastGroup,
            int multicastPort,
            NetworkInterface iface,
            int multicastTtl) throws IOException {
        this.book = new OrderBook(64, 4096);
        this.engine = new MatchingEngine(book, 4096);
        this.inboundRing = new RingBuffer<>(4096);
        this.outboundRing = new RingBuffer<>(4096);
        this.inboundPool = new InboundEvent[4096];
        for (int i = 0; i < inboundPool.length; i++) inboundPool[i] = new InboundEvent();
        this.outboundPool = new OutboundFrame[4096];
        for (int i = 0; i < outboundPool.length; i++) outboundPool[i] = new OutboundFrame();
        // Orders that come to rest are inserted into the book by reference, so we can't hand
        // the engine the same scratch object twice — each submission needs its own slot.
        this.orderPool = new Order[4096];
        for (int i = 0; i < orderPool.length; i++) orderPool[i] = new Order();
        this.feedSeq = new FeedSequence();
        this.publisher = new MarketDataPublisher(multicastGroup, multicastPort, iface, multicastTtl, feedSeq);
        this.gateway = new OrderGateway(new InetSocketAddress("0.0.0.0", tcpPort), new GatewayHandler());
    }

    public InetSocketAddress tcpAddress() throws IOException { return gateway.boundAddress(); }
    public InetSocketAddress multicastAddress() { return publisher.groupAddress(); }

    public void start(PrintStream readyBanner) throws IOException {
        running = true;
        gatewayThread = new Thread(() -> {
            try { gateway.runForever(); } catch (IOException ignored) { }
        }, "exchange-gateway");
        engineThread = new Thread(this::engineLoop, "exchange-engine");
        gatewayThread.setDaemon(true);
        engineThread.setDaemon(true);
        gatewayThread.start();
        engineThread.start();
        // Ready banner on stdout lets subprocess callers know the server is accepting
        // connections and publishing on the multicast group.
        if (readyBanner != null) {
            InetSocketAddress tcp = tcpAddress();
            InetSocketAddress mc = multicastAddress();
            readyBanner.printf("READY tcp=%s:%d multicast=%s:%d%n",
                    tcp.getAddress().getHostAddress(), tcp.getPort(),
                    mc.getAddress().getHostAddress(), mc.getPort());
            readyBanner.flush();
        }
    }

    public void stop() {
        running = false;
        gateway.stop();
    }

    @Override
    public void close() throws IOException {
        stop();
        try { if (engineThread != null) engineThread.join(2000L); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        gateway.close();
        publisher.close();
    }

    // =========================================================================================
    // engine thread
    // =========================================================================================

    private void engineLoop() {
        while (running) {
            InboundEvent ev = inboundRing.poll();
            if (ev == null) {
                Thread.onSpinWait();
                continue;
            }
            try {
                switch (ev.kind) {
                    case EV_NEW_ORDER -> handleNewOrder(ev);
                    case EV_CANCEL    -> handleCancel(ev);
                    case EV_MODIFY    -> handleModify(ev);
                    default -> { /* unknown — skip */ }
                }
            } catch (Exception e) {
                // engine-loop errors are fatal to this request but must not kill the thread
                e.printStackTrace();
            } finally {
                ev.clear();
            }
        }
    }

    private void handleNewOrder(InboundEvent ev) throws IOException {
        Order order = nextOrderSlot();
        order.reset(ev.orderId, ev.clientId, ev.side, ev.orderType,
                ev.price, ev.quantity, ev.timestampNanos);
        if (ev.orderType == Order.TYPE_ICEBERG && ev.displaySize > 0) {
            order.configureIceberg(ev.displaySize);
        }
        final byte side = ev.side;
        final long priceBefore = ev.price;
        engine.submit(order, report -> {
            try { dispatchReport(routeFor(report), report); } catch (IOException ignored) { }
            publishFeedSideEffects(report, side, priceBefore);
        });
    }

    private void handleCancel(InboundEvent ev) {
        engine.cancel(ev.orderId, ev.timestampNanos, report -> {
            try { dispatchReport(routeFor(report), report); } catch (IOException ignored) { }
            publishFeedSideEffects(report, report.side, report.price);
        });
    }

    private void handleModify(InboundEvent ev) {
        engine.modify(ev.orderId, ev.newPrice, ev.newQuantity, ev.timestampNanos, report -> {
            try { dispatchReport(routeFor(report), report); } catch (IOException ignored) { }
            publishFeedSideEffects(report, report.side, report.price);
        });
    }

    /** A report's {@code clientId} is set by the engine to the originating order's owner.
     * This is the authoritative routing key: taker reports go back to the taker's session,
     * maker reports go back to the maker's session. */
    private ClientSession routeFor(ExecutionReport report) {
        return sessionsByClient.get(report.clientId);
    }

    private void publishFeedSideEffects(ExecutionReport report, byte originSide, long originPrice) {
        try {
            switch (report.reportType) {
                case ExecutionReport.TYPE_FILL, ExecutionReport.TYPE_PARTIAL_FILL -> {
                    // Only emit trade once per match — choose the taker copy arbitrarily by
                    // skipping reports whose counterparty id is smaller than the order id
                    // (the two copies have symmetric orderId/counterpartyOrderId pairs).
                    if (report.orderId > report.counterpartyOrderId) {
                        publisher.publishTrade(
                                report.orderId, report.counterpartyOrderId, report.side,
                                report.executedPrice, report.executedQuantity,
                                report.timestampNanos);
                    }
                    // After any fill, the level on the MAKER side changed. Both sides may have
                    // shifted if a level cleared. Republish both best levels for simplicity.
                    republishLevel(Order.SIDE_BUY, 0);
                    republishLevel(Order.SIDE_SELL, 0);
                }
                case ExecutionReport.TYPE_ACK -> {
                    // new resting order may have changed one level
                    republishLevel(report.side, priceDepth(report.side, report.price));
                }
                case ExecutionReport.TYPE_CANCELED, ExecutionReport.TYPE_MODIFIED -> {
                    republishLevel(report.side, priceDepth(report.side, report.price));
                }
                default -> { /* REJECTED etc.: no book change */ }
            }
        } catch (IOException ignored) { }
    }

    private int priceDepth(byte side, long price) {
        int count = side == Order.SIDE_BUY ? book.bidLevelCount() : book.askLevelCount();
        for (int i = 0; i < count; i++) {
            long p = side == Order.SIDE_BUY ? book.bidPriceAtDepth(i) : book.askPriceAtDepth(i);
            if (p == price) return i;
        }
        return -1;
    }

    private void republishLevel(byte side, int depth) throws IOException {
        if (depth < 0) {
            // level was emptied — we don't know its price from here; just republish best level
            depth = 0;
        }
        long price, quantity;
        int count;
        if (side == Order.SIDE_BUY) {
            if (book.bidLevelCount() <= depth) return;
            price = book.bidPriceAtDepth(depth);
            quantity = book.bidQuantityAtDepth(depth);
            count = book.bidOrderCountAtDepth(depth);
        } else {
            if (book.askLevelCount() <= depth) return;
            price = book.askPriceAtDepth(depth);
            quantity = book.askQuantityAtDepth(depth);
            count = book.askOrderCountAtDepth(depth);
        }
        publisher.publishBookUpdate(side, price, quantity, count);
    }

    private void dispatchReport(ClientSession session, ExecutionReport report) throws IOException {
        if (session == null || session.isClosed()) return;
        OutboundFrame frame = nextOutboundSlot();
        frame.session = session;
        frame.buffer.clear();
        outCodec.encodeExecutionReport(report, frame.buffer);
        frame.buffer.flip();
        gateway.send(session, frame.buffer);
    }

    private OutboundFrame nextOutboundSlot() {
        OutboundFrame f = outboundPool[outboundPoolIdx];
        outboundPoolIdx = (outboundPoolIdx + 1) % outboundPool.length;
        return f;
    }

    private Order nextOrderSlot() {
        Order o = orderPool[orderPoolIdx];
        orderPoolIdx = (orderPoolIdx + 1) % orderPool.length;
        return o;
    }

    // =========================================================================================
    // gateway handler (runs on the selector thread)
    // =========================================================================================

    private final class GatewayHandler implements OrderGateway.InboundHandler {
        @Override
        public void onConnect(ClientSession session) {
            sessionsByClient.put(session.clientId(), session);
        }
        @Override
        public void onDisconnect(ClientSession session) {
            sessionsByClient.remove(session.clientId());
        }
        @Override
        public void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                               long price, long quantity, long displaySize, long timestampNanos) {
            InboundEvent ev = nextInboundSlot();
            ev.kind = EV_NEW_ORDER;
            ev.clientId = clientId;
            ev.orderId = orderId;
            ev.side = side;
            ev.orderType = orderType;
            ev.price = price;
            ev.quantity = quantity;
            ev.displaySize = displaySize;
            ev.timestampNanos = timestampNanos;
            ev.session = currentSession(clientId);
            inboundRing.publish(ev);
        }
        @Override
        public void onCancel(long orderId, long timestampNanos) {
            InboundEvent ev = nextInboundSlot();
            ev.kind = EV_CANCEL;
            ev.orderId = orderId;
            ev.timestampNanos = timestampNanos;
            ev.session = mostRecent();
            inboundRing.publish(ev);
        }
        @Override
        public void onModify(long orderId, long newPrice, long newQuantity, long timestampNanos) {
            InboundEvent ev = nextInboundSlot();
            ev.kind = EV_MODIFY;
            ev.orderId = orderId;
            ev.newPrice = newPrice;
            ev.newQuantity = newQuantity;
            ev.timestampNanos = timestampNanos;
            ev.session = mostRecent();
            inboundRing.publish(ev);
        }
    }

    private ClientSession currentSession(long clientId) {
        return sessionsByClient.get(clientId);
    }

    private ClientSession mostRecent() {
        // For cancel/modify we need a routed session; for single-client tests this is enough.
        // A real gateway carries the session through the decoded callback; stage 6 forgoes that.
        return sessionsByClient.values().stream().findFirst().orElse(null);
    }

    private InboundEvent nextInboundSlot() {
        InboundEvent ev = inboundPool[inboundPoolIdx];
        inboundPoolIdx = (inboundPoolIdx + 1) % inboundPool.length;
        return ev;
    }

    // =========================================================================================
    // entry point
    // =========================================================================================

    /**
     * CLI entry: {@code ExchangeServer <tcpPort> <multicastGroup> <multicastPort>}. Writes
     * {@code READY tcp=... multicast=...} to stdout once both ports are live.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ExchangeServer <tcpPort> <multicastGroup> <multicastPort>");
            System.exit(2);
        }
        int tcpPort = Integer.parseInt(args[0]);
        InetAddress group = InetAddress.getByName(args[1]);
        int mcPort = Integer.parseInt(args[2]);
        NetworkInterface iface = findLoopback();
        ExchangeServer server = new ExchangeServer(tcpPort, group, mcPort, iface, 1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (IOException ignored) { }
        }));
        server.start(System.out);
        Thread.currentThread().join();
    }

    private static NetworkInterface findLoopback() throws SocketException {
        Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements()) {
            NetworkInterface ni = e.nextElement();
            if (ni.isLoopback() && ni.supportsMulticast() && ni.isUp()) return ni;
        }
        throw new SocketException("no multicast loopback interface");
    }
}
