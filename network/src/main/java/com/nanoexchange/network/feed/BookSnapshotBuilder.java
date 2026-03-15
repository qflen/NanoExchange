package com.nanoexchange.network.feed;

import com.nanoexchange.engine.Order;
import com.nanoexchange.engine.OrderBook;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Walks an {@link OrderBook} and produces one or more SNAPSHOT UDP datagrams that together
 * describe the full visible book state.
 *
 * <p>Snapshots can exceed the MTU (1472 bytes) if the book has many price levels. This
 * builder splits the state across multiple parts using the snapshot header's
 * {@code partNumber}/{@code totalParts} fields so a consumer can reassemble the full book.
 * Each part is a self-contained, CRC-validated UDP datagram.
 *
 * <p>Not thread-safe; intended to be driven on the publisher thread that owns the buffer.
 */
public final class BookSnapshotBuilder {

    private static final int PAYLOAD_BUDGET =
            FeedCodec.MAX_DATAGRAM_SIZE
                    - FeedCodec.MESSAGE_OVERHEAD
                    - FeedCodec.SNAPSHOT_HEADER_SIZE;
    private static final int LEVELS_PER_PART = PAYLOAD_BUDGET / FeedCodec.SNAPSHOT_LEVEL_ENTRY_SIZE;

    private final FeedCodec codec;

    public BookSnapshotBuilder(FeedCodec codec) {
        this.codec = codec;
    }

    /** @return the maximum number of level entries that fit in one datagram. */
    public static int levelsPerPart() { return LEVELS_PER_PART; }

    /**
     * Emit a sequence of SNAPSHOT datagrams describing the current state of {@code book}. Each
     * datagram is written into {@code scratch} (which is cleared between parts) and handed to
     * {@code sink} as a read-only flipped {@link ByteBuffer}.
     *
     * @param feedSeq          sequence counter providing per-part sequence numbers; advanced as
     *                         parts are emitted
     * @param book             the book being snapshotted
     * @param scratch          reusable buffer large enough for {@link FeedCodec#MAX_DATAGRAM_SIZE}
     *                         bytes, in little-endian order
     * @param sink             invoked once per part with the fully-encoded datagram bytes
     */
    public void snapshot(FeedSequence feedSeq, OrderBook book, ByteBuffer scratch,
                         Consumer<ByteBuffer> sink) {
        int bidLevels = book.bidLevelCount();
        int askLevels = book.askLevelCount();
        int totalLevels = bidLevels + askLevels;
        int totalParts = Math.max(1, (totalLevels + LEVELS_PER_PART - 1) / LEVELS_PER_PART);
        long startingSequence = feedSeq.current();

        int written = 0;
        int partNumber = 1;
        int levelsRemaining = totalLevels;
        while (partNumber <= totalParts) {
            int levelsThisPart = Math.min(LEVELS_PER_PART, levelsRemaining);
            scratch.clear();
            long thisSeq = feedSeq.next();
            int start = codec.beginSnapshot(
                    thisSeq, startingSequence,
                    (short) partNumber, (short) totalParts, scratch);

            for (int i = 0; i < levelsThisPart; i++) {
                int global = written + i;
                if (global < bidLevels) {
                    int depth = global;
                    codec.appendSnapshotLevel(
                            Order.SIDE_BUY,
                            book.bidPriceAtDepth(depth),
                            book.bidQuantityAtDepth(depth),
                            book.bidOrderCountAtDepth(depth),
                            scratch);
                } else {
                    int depth = global - bidLevels;
                    codec.appendSnapshotLevel(
                            Order.SIDE_SELL,
                            book.askPriceAtDepth(depth),
                            book.askQuantityAtDepth(depth),
                            book.askOrderCountAtDepth(depth),
                            scratch);
                }
            }
            codec.finishSnapshot(start, levelsThisPart, scratch);
            scratch.flip();
            sink.accept(scratch);

            written += levelsThisPart;
            levelsRemaining -= levelsThisPart;
            partNumber++;
        }
    }
}
