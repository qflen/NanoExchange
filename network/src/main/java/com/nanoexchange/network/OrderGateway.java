package com.nanoexchange.network;

import com.nanoexchange.network.protocol.WireCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-threaded NIO selector that accepts client TCP connections and pushes decoded order
 * events to an inbound handler. The gateway thread does no engine work — it only parses bytes
 * and fans out events. Engine / business logic lives on another thread and consumes from a
 * ring buffer fed by the handler.
 *
 * <h2>Nagle</h2>
 * Every accepted socket has {@code TCP_NODELAY = true}. Nagle's algorithm coalesces small
 * writes to improve throughput at the cost of up to ~200ms added latency waiting for the ACK
 * of the previous segment. For order entry, latency dominates throughput, so Nagle is turned
 * off.
 *
 * <h2>Threading</h2>
 * The selector thread is the single reader/writer for every registered socket. Reports that
 * come back from the engine (on a different thread) must be handed off into per-session
 * outbound queues (stage 4 exposes {@link ClientSession#queueOutbound} for this and expects
 * the caller to wake up the selector; future stages will layer a proper ring buffer on top).
 */
public final class OrderGateway implements AutoCloseable {

    private final Selector selector;
    private final ServerSocketChannel serverChannel;
    private final InboundHandler handler;
    private final AtomicLong nextClientId = new AtomicLong(1L);
    private final WireCodec codec = new WireCodec();
    private volatile boolean running;

    public OrderGateway(InetSocketAddress bindAddr, InboundHandler handler) throws IOException {
        this.handler = handler;
        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);
        this.serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        this.serverChannel.bind(bindAddr);
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    /** The actual bound address (useful when the caller passed port 0 to mean "ephemeral"). */
    public InetSocketAddress boundAddress() throws IOException {
        return (InetSocketAddress) serverChannel.getLocalAddress();
    }

    /**
     * Run one iteration of the selector loop with the given timeout in milliseconds. Exposed
     * for tests and for embedding into a caller-owned thread; {@link #runForever()} is the
     * convenience wrapper for production use.
     */
    public void pollOnce(long timeoutMillis) throws IOException {
        selector.select(timeoutMillis);
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();
            if (!key.isValid()) continue;
            try {
                if (key.isAcceptable()) {
                    accept();
                } else {
                    if (key.isReadable()) readFromKey(key);
                    if (key.isValid() && key.isWritable()) writeToKey(key);
                }
            } catch (IOException io) {
                closeKey(key);
            }
        }
    }

    /** Spin the selector loop until {@link #stop()} flips {@code running} to false. */
    public void runForever() throws IOException {
        running = true;
        while (running) {
            pollOnce(50L);
        }
    }

    public void stop() {
        running = false;
        selector.wakeup();
    }

    /**
     * Wake the selector from its current {@code select} so a piece of work posted by another
     * thread (e.g. an outbound report from the engine) can be acted on.
     */
    public void wakeup() {
        selector.wakeup();
    }

    @Override
    public void close() throws IOException {
        running = false;
        for (SelectionKey key : selector.keys()) {
            try { key.channel().close(); } catch (IOException ignored) { }
        }
        selector.close();
        serverChannel.close();
    }

    // =========================================================================================
    // private I/O
    // =========================================================================================

    private void accept() throws IOException {
        SocketChannel sc;
        while ((sc = serverChannel.accept()) != null) {
            sc.configureBlocking(false);
            sc.setOption(StandardSocketOptions.TCP_NODELAY, true);
            long clientId = nextClientId.getAndIncrement();
            ClientSession session = new ClientSession(sc, clientId);
            SelectionKey key = sc.register(selector, SelectionKey.OP_READ);
            key.attach(session);
            handler.onConnect(session);
        }
    }

    private void readFromKey(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        int n = session.readFromSocket();
        if (n < 0) {
            closeKey(key);
            return;
        }
        if (n == 0) return;

        session.prepareDecode();
        ByteBuffer buf = session.readBuffer();
        boolean bad = false;
        while (true) {
            int status = codec.tryDecode(buf, new CodecAdapter(session, handler));
            if (status == WireCodec.DECODED) continue;
            if (status == WireCodec.NEED_MORE_BYTES) break;
            bad = true;
            break;
        }
        session.compactAfterDecode();
        if (bad) {
            handler.onProtocolError(session);
            closeKey(key);
        }
    }

    private void writeToKey(SelectionKey key) throws IOException {
        ClientSession session = (ClientSession) key.attachment();
        boolean drained = session.flushWrite();
        if (drained) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void closeKey(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        if (session != null) {
            handler.onDisconnect(session);
            session.close();
        }
        key.cancel();
    }

    /**
     * Encode {@code frame} (already position-0..limit) as the outbound payload for the given
     * session. Registers OP_WRITE if the socket backpressures. Safe to call from the selector
     * thread. From another thread, stage the frame and call {@link #wakeup()}.
     */
    public void send(ClientSession session, ByteBuffer frame) throws IOException {
        // When the engine emits reports faster than the socket can drain
        // (100k-order simulator bursts), the per-session 1 MiB buffer
        // can fill. Flush inline and retry rather than throwing: the
        // engine loop owns this thread, so there is nothing else to do
        // while we wait. We give up and drop after a bounded spin so a
        // wedged client can't halt the engine forever.
        int attempts = 0;
        while (frame.remaining() > session.writeBuffer().remaining()) {
            boolean drained = session.flushWrite();
            if (drained) break;
            if (++attempts >= 1024) {
                SelectionKey key = session.channel().keyFor(selector);
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
                return;
            }
            Thread.onSpinWait();
        }
        session.queueOutbound(frame);
        boolean drained = session.flushWrite();
        if (!drained) {
            SelectionKey key = session.channel().keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    // =========================================================================================
    // handler interface + bridge
    // =========================================================================================

    /**
     * Callback the gateway invokes as bytes arrive from clients. Implementations are expected
     * to hand decoded events off to the engine via a lock-free ring buffer (stage 3) and
     * return quickly.
     */
    public interface InboundHandler extends WireCodec.DecodedSink {
        /** New TCP connection accepted and ready to receive frames. */
        default void onConnect(ClientSession session) { }
        /** Connection closed normally or by error. */
        default void onDisconnect(ClientSession session) { }
        /** Framing or CRC failure — the gateway will close the connection after this returns. */
        default void onProtocolError(ClientSession session) { }
    }

    /** Adapter: each decoded frame is forwarded to the user handler with the session bound in. */
    private record CodecAdapter(ClientSession session, InboundHandler handler)
            implements WireCodec.DecodedSink {
        @Override
        public void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                               long price, long quantity, long displaySize, long timestampNanos) {
            // wire-reported clientId is ignored; trust the session's authenticated id
            handler.onNewOrder(session.clientId(), orderId, side, orderType,
                    price, quantity, displaySize, timestampNanos);
        }
        @Override
        public void onCancel(long orderId, long timestampNanos) {
            handler.onCancel(orderId, timestampNanos);
        }
        @Override
        public void onModify(long orderId, long newPrice, long newQuantity, long timestampNanos) {
            handler.onModify(orderId, newPrice, newQuantity, timestampNanos);
        }
        @Override
        public void onHeartbeat() {
            handler.onHeartbeat();
        }
    }

    /** Utility: a scratch buffer for encoding frames. */
    public static ByteBuffer newEncodingBuffer(int capacity) {
        return ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }
}
