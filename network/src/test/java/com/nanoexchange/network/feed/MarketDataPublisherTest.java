package com.nanoexchange.network.feed;

import com.nanoexchange.engine.Order;
import com.nanoexchange.engine.OrderBook;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

final class MarketDataPublisherTest {

    /**
     * Find a loopback interface that supports multicast. Most platforms expose {@code lo} /
     * {@code lo0} with multicast enabled.
     */
    private static NetworkInterface loopback() throws IOException {
        var e = NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements()) {
            NetworkInterface ni = e.nextElement();
            if (ni.isLoopback() && ni.supportsMulticast() && ni.isUp()) {
                return ni;
            }
        }
        throw new IOException("no multicast-capable loopback interface");
    }

    private static DatagramChannel joinReceiver(InetAddress group, int port, NetworkInterface iface)
            throws IOException {
        DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
        ch.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        ch.bind(new InetSocketAddress(port));
        ch.configureBlocking(false);
        MembershipKey key = ch.join(group, iface);
        assertThat(key.isValid()).isTrue();
        return ch;
    }

    /**
     * Receive up to {@code count} datagrams within {@code timeoutMillis}, blocking at the
     * test-thread level with a simple poll-loop.
     */
    private static List<ByteBuffer> receive(DatagramChannel ch, int count, long timeoutMillis)
            throws IOException, InterruptedException {
        List<ByteBuffer> out = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (out.size() < count && System.nanoTime() < deadline) {
            ByteBuffer buf = ByteBuffer.allocate(FeedCodec.MAX_DATAGRAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            if (ch.receive(buf) != null) {
                buf.flip();
                out.add(buf);
            } else {
                Thread.sleep(2);
            }
        }
        return out;
    }

    @Test
    void publishedBookUpdateIsReceivedByMulticastSubscriber() throws Exception {
        NetworkInterface iface = loopback();
        InetAddress group = InetAddress.getByName("239.200.3.14"); // admin-scoped
        int port = 34_567;
        try (DatagramChannel receiver = joinReceiver(group, port, iface);
             MarketDataPublisher pub = new MarketDataPublisher(group, port, iface, 1, new FeedSequence())) {

            pub.publishBookUpdate(Order.SIDE_BUY, 100_50000000L, 250L, 3);
            pub.publishHeartbeat();

            List<ByteBuffer> datagrams = receive(receiver, 2, 2000L);
            assertThat(datagrams).hasSize(2);

            FeedCodec codec = new FeedCodec();
            List<String> log = new ArrayList<>();
            FeedCodec.FeedVisitor v = new FeedCodec.FeedVisitor() {
                @Override
                public void onBookUpdate(long seq, byte side, long price, long quantity, int orderCount) {
                    log.add("BU seq=" + seq + " p=" + price + " q=" + quantity);
                }
                @Override
                public void onHeartbeat(long seq) { log.add("HB seq=" + seq); }
            };
            for (ByteBuffer d : datagrams) codec.decode(d, v);
            assertThat(log).containsExactly("BU seq=1 p=10050000000 q=250", "HB seq=2");
        }
    }

    @Test
    void snapshotIsReceivedAsMultipleDatagrams() throws Exception {
        NetworkInterface iface = loopback();
        InetAddress group = InetAddress.getByName("239.200.3.15");
        int port = 34_568;

        OrderBook book = new OrderBook(256, 1024);
        long id = 1;
        for (int i = 0; i < 120; i++) {
            Order o = new Order();
            o.reset(id++, 1, Order.SIDE_BUY, Order.TYPE_LIMIT, 100L - i, 10L, 0L);
            book.addResting(o);
            Order s = new Order();
            s.reset(id++, 1, Order.SIDE_SELL, Order.TYPE_LIMIT, 200L + i, 10L, 0L);
            book.addResting(s);
        }

        try (DatagramChannel receiver = joinReceiver(group, port, iface);
             MarketDataPublisher pub = new MarketDataPublisher(group, port, iface, 1, new FeedSequence())) {

            pub.publishSnapshot(book);

            int expectedParts = (240 + BookSnapshotBuilder.levelsPerPart() - 1) / BookSnapshotBuilder.levelsPerPart();
            List<ByteBuffer> datagrams = receive(receiver, expectedParts, 2000L);
            assertThat(datagrams).hasSize(expectedParts);

            FeedCodec codec = new FeedCodec();
            int[] totalLevelsSeen = { 0 };
            short[] lastTotalParts = { 0 };
            int[] partsSeen = { 0 };
            FeedCodec.FeedVisitor v = new FeedCodec.FeedVisitor() {
                @Override
                public void onSnapshotBegin(long seq, long starting, short part, short total, int levels) {
                    partsSeen[0]++;
                    lastTotalParts[0] = total;
                }
                @Override
                public void onSnapshotLevel(byte side, long price, long quantity, int orderCount) {
                    totalLevelsSeen[0]++;
                }
            };
            for (ByteBuffer d : datagrams) codec.decode(d, v);
            assertThat(partsSeen[0]).isEqualTo(expectedParts);
            assertThat((int) lastTotalParts[0]).isEqualTo(expectedParts);
            assertThat(totalLevelsSeen[0]).isEqualTo(240);
        }
    }
}
