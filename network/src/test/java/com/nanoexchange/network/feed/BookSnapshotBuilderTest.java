package com.nanoexchange.network.feed;

import com.nanoexchange.engine.Order;
import com.nanoexchange.engine.OrderBook;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class BookSnapshotBuilderTest {

    private static Order makeOrder(long id, long client, byte side, long price, long qty) {
        Order o = new Order();
        o.reset(id, client, side, Order.TYPE_LIMIT, price, qty, 0L);
        return o;
    }

    @Test
    void snapshotEmptyBookEmitsOneEmptyPart() {
        FeedSequence seq = new FeedSequence();
        FeedCodec codec = new FeedCodec();
        BookSnapshotBuilder builder = new BookSnapshotBuilder(codec);
        OrderBook book = new OrderBook(16, 64);
        ByteBuffer scratch = ByteBuffer.allocate(FeedCodec.MAX_DATAGRAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        List<String> log = new ArrayList<>();
        builder.snapshot(seq, book, scratch, (buf) -> {
            FeedCodec.FeedVisitor v = new FeedCodec.FeedVisitor() {
                @Override
                public void onSnapshotBegin(long s, long starting, short part, short total, int levels) {
                    log.add("BEGIN part=" + part + "/" + total + " levels=" + levels);
                }
                @Override public void onSnapshotEnd(long s) { log.add("END"); }
            };
            codec.decode(buf, v);
        });
        assertThat(log).containsExactly("BEGIN part=1/1 levels=0", "END");
    }

    @Test
    void snapshotMirrorsBookState() {
        FeedSequence seq = new FeedSequence();
        FeedCodec codec = new FeedCodec();
        BookSnapshotBuilder builder = new BookSnapshotBuilder(codec);
        OrderBook book = new OrderBook(16, 64);

        book.addResting(makeOrder(1, 10, Order.SIDE_BUY, 99_00000000L, 100L));
        book.addResting(makeOrder(2, 10, Order.SIDE_BUY, 99_00000000L, 50L));
        book.addResting(makeOrder(3, 11, Order.SIDE_BUY, 98_00000000L, 75L));
        book.addResting(makeOrder(4, 12, Order.SIDE_SELL, 101_00000000L, 200L));

        ByteBuffer scratch = ByteBuffer.allocate(FeedCodec.MAX_DATAGRAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        List<String> levels = new ArrayList<>();
        builder.snapshot(seq, book, scratch, (buf) -> {
            FeedCodec.FeedVisitor v = new FeedCodec.FeedVisitor() {
                @Override
                public void onSnapshotLevel(byte side, long price, long qty, int count) {
                    levels.add("s=" + side + " p=" + price + " q=" + qty + " n=" + count);
                }
            };
            codec.decode(buf, v);
        });

        assertThat(levels).containsExactly(
                "s=0 p=9900000000 q=150 n=2",   // best bid aggregated
                "s=0 p=9800000000 q=75 n=1",
                "s=1 p=10100000000 q=200 n=1"
        );
    }

    @Test
    void snapshotChunksAcrossManyLevels() {
        // 200 levels per side = 400 total; a single datagram holds
        // BookSnapshotBuilder.levelsPerPart() levels, so we should get
        // ceil(400 / levelsPerPart) parts.
        FeedSequence seq = new FeedSequence();
        FeedCodec codec = new FeedCodec();
        BookSnapshotBuilder builder = new BookSnapshotBuilder(codec);
        OrderBook book = new OrderBook(256, 1024);

        long id = 1;
        for (int i = 0; i < 200; i++) {
            book.addResting(makeOrder(id++, 1, Order.SIDE_BUY, 100L - i, 10L));
            book.addResting(makeOrder(id++, 1, Order.SIDE_SELL, 200L + i, 10L));
        }

        int totalLevels = 400;
        int expectedParts = (totalLevels + BookSnapshotBuilder.levelsPerPart() - 1)
                / BookSnapshotBuilder.levelsPerPart();

        ByteBuffer scratch = ByteBuffer.allocate(FeedCodec.MAX_DATAGRAM_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        int[] partsSeen = { 0 };
        short[] totalSeen = { 0 };
        int[] levelCount = { 0 };
        long[] startingSeq = { -1 };
        builder.snapshot(seq, book, scratch, (buf) -> {
            codec.decode(buf, new FeedCodec.FeedVisitor() {
                @Override
                public void onSnapshotBegin(long s, long starting, short part, short total, int levels) {
                    partsSeen[0]++;
                    totalSeen[0] = total;
                    if (startingSeq[0] < 0) startingSeq[0] = starting;
                    assertThat(starting).isEqualTo(startingSeq[0]); // all parts share startingSequence
                }
                @Override
                public void onSnapshotLevel(byte side, long price, long quantity, int orderCount) {
                    levelCount[0]++;
                }
            });
        });
        assertThat(partsSeen[0]).isEqualTo(expectedParts);
        assertThat((int) totalSeen[0]).isEqualTo(expectedParts);
        assertThat(levelCount[0]).isEqualTo(totalLevels);
    }
}
