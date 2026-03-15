package com.nanoexchange.network.feed;

import com.nanoexchange.engine.OrderBook;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;

/**
 * Publishes market-data events onto a UDP multicast group. Consumers join the group and
 * receive all traffic without a per-client connection, making this cheap to fan out to many
 * subscribers. The feed carries:
 *
 * <ul>
 *   <li><b>BOOK_UPDATE</b> — aggregate quantity at a (side, price) level changed.</li>
 *   <li><b>TRADE</b> — a match happened.</li>
 *   <li><b>SNAPSHOT</b> — periodic full-book dump so consumers can recover from packet loss.</li>
 *   <li><b>HEARTBEAT</b> — liveness tick when no other messages were sent.</li>
 * </ul>
 *
 * <p>All messages share a monotonic {@link FeedSequence} — consumers detect gaps by sequence
 * skips and re-synchronize on the next snapshot, whose {@code startingSequence} field gives
 * the sequence of the last update included in the snapshot.
 *
 * <h2>macOS note</h2>
 * macOS does not reliably route multicast on the default interface when
 * {@code IP_MULTICAST_IF} is left unset. Callers must pass a concrete {@link NetworkInterface}
 * (typically the loopback {@code lo0} for local testing). Linux tolerates {@code null} but we
 * require the caller to be explicit either way.
 *
 * <h2>MTU</h2>
 * Datagrams are sized to at most {@link FeedCodec#MAX_DATAGRAM_SIZE} bytes (1472) to fit in a
 * single Ethernet frame without IP fragmentation. Large snapshots chunk across multiple
 * datagrams via the SNAPSHOT part/totalParts fields.
 *
 * <h2>TTL</h2>
 * Default TTL is 1 (link-local only). Production deployments set it explicitly to cross
 * subnets; in this codebase, tests and local dev stay on the loopback where TTL doesn't
 * matter.
 */
public final class MarketDataPublisher implements AutoCloseable {

    private final DatagramChannel channel;
    private final InetSocketAddress groupAddr;
    private final FeedSequence seq;
    private final FeedCodec codec;
    private final BookSnapshotBuilder snapshotBuilder;
    private final ByteBuffer scratch;

    public MarketDataPublisher(InetAddress group, int port, NetworkInterface iface, int ttl,
                               FeedSequence seq) throws IOException {
        this.seq = seq;
        this.codec = new FeedCodec();
        this.snapshotBuilder = new BookSnapshotBuilder(codec);
        this.groupAddr = new InetSocketAddress(group, port);
        this.channel = DatagramChannel.open(
                group instanceof java.net.Inet6Address
                        ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        this.channel.setOption(StandardSocketOptions.IP_MULTICAST_IF, iface);
        this.channel.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
        // Allow loopback to see our own packets so the same JVM can host publisher and a
        // test-only subscriber; production usually disables this.
        this.channel.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
        this.scratch = ByteBuffer.allocate(FeedCodec.MAX_DATAGRAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    }

    public InetSocketAddress groupAddress() { return groupAddr; }

    /** Publish a book-level aggregate change. {@code quantity == 0} removes the level. */
    public void publishBookUpdate(byte side, long price, long quantity, int orderCount)
            throws IOException {
        scratch.clear();
        codec.encodeBookUpdate(seq.next(), side, price, quantity, orderCount, scratch);
        scratch.flip();
        sendOneDatagram(scratch);
    }

    public void publishTrade(long takerOrderId, long makerOrderId, byte takerSide,
                             long price, long quantity, long timestampNanos) throws IOException {
        scratch.clear();
        codec.encodeTrade(seq.next(), takerOrderId, makerOrderId, takerSide,
                price, quantity, timestampNanos, scratch);
        scratch.flip();
        sendOneDatagram(scratch);
    }

    public void publishHeartbeat() throws IOException {
        scratch.clear();
        codec.encodeHeartbeat(seq.next(), scratch);
        scratch.flip();
        sendOneDatagram(scratch);
    }

    /** Emit one or more SNAPSHOT datagrams covering the entire visible state of {@code book}. */
    public void publishSnapshot(OrderBook book) throws IOException {
        // Use a separate scratch buffer so we don't clobber the one owned by helpers — builder
        // clears and writes per-part, each flipped buffer is then sent.
        ByteBuffer snapshotScratch = ByteBuffer
                .allocate(FeedCodec.MAX_DATAGRAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        snapshotBuilder.snapshot(seq, book, snapshotScratch, this::sendOneDatagramUnchecked);
    }

    private void sendOneDatagram(ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            int n = channel.send(data, groupAddr);
            if (n == 0) {
                // UDP normally sends in one go; 0 means the kernel buffer is full. Retry after
                // a tiny pause keeps us inside the hot path without spinning pointlessly.
                Thread.onSpinWait();
            }
        }
    }

    private void sendOneDatagramUnchecked(ByteBuffer data) {
        try {
            sendOneDatagram(data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
