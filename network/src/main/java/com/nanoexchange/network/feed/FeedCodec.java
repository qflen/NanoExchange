package com.nanoexchange.network.feed;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * Encode and decode UDP market-data feed messages. Each UDP datagram carries exactly one
 * message with this layout:
 *
 * <pre>
 *   offset  size  field
 *   ------  ----  --------------------------------------
 *        0     8  feed sequence (LE int64, monotonic across entire feed)
 *        8     1  message type (see {@link FeedMessageType})
 *        9     N  payload (variable, per type)
 *    9 + N     4  crc32 (LE int32) — over bytes [0, 9+N)
 * </pre>
 *
 * <p>Packets are sized to fit within 1472 bytes (Ethernet MTU minus UDP/IP headers) to avoid
 * IP fragmentation. Snapshots too large for one packet split via part/total fields in the
 * SNAPSHOT payload header.
 */
public final class FeedCodec {

    /** Bytes of fixed overhead per message (seq + type + crc). */
    public static final int MESSAGE_OVERHEAD = 8 + 1 + 4;

    /** Payload size for a {@link FeedMessageType#BOOK_UPDATE} — side, price, qty, orderCount. */
    public static final int BOOK_UPDATE_PAYLOAD_SIZE = 1 + 8 + 8 + 4;
    /** Payload size for a {@link FeedMessageType#TRADE}. */
    public static final int TRADE_PAYLOAD_SIZE = 8 + 8 + 1 + 8 + 8 + 8;
    /** Fixed header prefix of a {@link FeedMessageType#SNAPSHOT} payload before per-level entries. */
    public static final int SNAPSHOT_HEADER_SIZE = 8 + 2 + 2 + 2;
    /** Size of one level entry inside a SNAPSHOT payload. */
    public static final int SNAPSHOT_LEVEL_ENTRY_SIZE = 1 + 8 + 8 + 4;

    /** Max UDP datagram we intend to emit — keep under typical MTU. */
    public static final int MAX_DATAGRAM_SIZE = 1472;

    private final CRC32 crc = new CRC32();

    // =========================================================================================
    // encode
    // =========================================================================================

    /**
     * Encode a BOOK_UPDATE message into {@code out}. The buffer's position advances by the
     * message size.
     *
     * @param quantity aggregate resting quantity at this level; {@code 0} means the level was
     *                 removed (consumers must erase it)
     */
    public void encodeBookUpdate(long seq, byte side, long price, long quantity, int orderCount,
                                 ByteBuffer out) {
        int start = out.position();
        out.putLong(seq);
        out.put(FeedMessageType.BOOK_UPDATE);
        out.put(side);
        out.putLong(price);
        out.putLong(quantity);
        out.putInt(orderCount);
        finishMessage(out, start);
    }

    public void encodeTrade(long seq, long takerOrderId, long makerOrderId, byte takerSide,
                            long price, long quantity, long timestampNanos, ByteBuffer out) {
        int start = out.position();
        out.putLong(seq);
        out.put(FeedMessageType.TRADE);
        out.putLong(takerOrderId);
        out.putLong(makerOrderId);
        out.put(takerSide);
        out.putLong(price);
        out.putLong(quantity);
        out.putLong(timestampNanos);
        finishMessage(out, start);
    }

    public void encodeHeartbeat(long seq, ByteBuffer out) {
        int start = out.position();
        out.putLong(seq);
        out.put(FeedMessageType.HEARTBEAT);
        finishMessage(out, start);
    }

    /**
     * Begin a SNAPSHOT message. The returned offset is to be passed back into
     * {@link #finishSnapshot}. Between these two calls the caller appends zero or more level
     * entries via {@link #appendSnapshotLevel}.
     */
    public int beginSnapshot(long seq, long startingSequence, short partNumber, short totalParts,
                             ByteBuffer out) {
        int start = out.position();
        out.putLong(seq);
        out.put(FeedMessageType.SNAPSHOT);
        out.putLong(startingSequence);
        out.putShort(partNumber);
        out.putShort(totalParts);
        // level count placeholder — finishSnapshot patches it
        out.putShort((short) 0);
        return start;
    }

    public void appendSnapshotLevel(byte side, long price, long quantity, int orderCount,
                                    ByteBuffer out) {
        out.put(side);
        out.putLong(price);
        out.putLong(quantity);
        out.putInt(orderCount);
    }

    public void finishSnapshot(int snapshotStart, int levelCount, ByteBuffer out) {
        // level count is at: snapshotStart + 8 (seq) + 1 (type) + 8 (startingSeq) + 2 + 2 = +21
        out.putShort(snapshotStart + 21, (short) levelCount);
        finishMessage(out, snapshotStart);
    }

    private void finishMessage(ByteBuffer out, int start) {
        int crcPos = out.position();
        int crcValue = computeCrc(out, start, crcPos);
        out.putInt(crcPos, crcValue);
        out.position(crcPos + 4);
    }

    // =========================================================================================
    // decode
    // =========================================================================================

    public static final int NEED_MORE_BYTES = 0;
    public static final int DECODED = 1;
    public static final int BAD_FRAME = -1;

    /**
     * Decode a single UDP datagram's worth of bytes. Unlike TCP, UDP gives us whole datagrams
     * at a time, so there is no "partial frame" in normal operation; the NEED_MORE_BYTES path
     * is retained for completeness when caller hands us a buffer they haven't fully filled.
     */
    public int decode(ByteBuffer in, FeedVisitor visitor) {
        int start = in.position();
        int remaining = in.remaining();
        if (remaining < MESSAGE_OVERHEAD) return NEED_MORE_BYTES;

        long seq = in.getLong(start);
        byte type = in.get(start + 8);
        int payloadStart = start + 9;

        int payloadLen;
        int levelCount = 0;
        switch (type) {
            case FeedMessageType.BOOK_UPDATE -> payloadLen = BOOK_UPDATE_PAYLOAD_SIZE;
            case FeedMessageType.TRADE -> payloadLen = TRADE_PAYLOAD_SIZE;
            case FeedMessageType.HEARTBEAT -> payloadLen = 0;
            case FeedMessageType.SNAPSHOT -> {
                if (remaining < 9 + SNAPSHOT_HEADER_SIZE + 4) return NEED_MORE_BYTES;
                levelCount = Short.toUnsignedInt(in.getShort(payloadStart + 12));
                payloadLen = SNAPSHOT_HEADER_SIZE + levelCount * SNAPSHOT_LEVEL_ENTRY_SIZE;
            }
            default -> { return BAD_FRAME; }
        }

        int totalLen = 9 + payloadLen + 4;
        if (remaining < totalLen) return NEED_MORE_BYTES;
        int crcPos = start + 9 + payloadLen;
        int expected = in.getInt(crcPos);
        int actual = computeCrc(in, start, crcPos);
        if (expected != actual) return BAD_FRAME;

        switch (type) {
            case FeedMessageType.BOOK_UPDATE -> {
                byte side        = in.get(payloadStart);
                long price       = in.getLong(payloadStart + 1);
                long quantity    = in.getLong(payloadStart + 9);
                int orderCount   = in.getInt(payloadStart + 17);
                visitor.onBookUpdate(seq, side, price, quantity, orderCount);
            }
            case FeedMessageType.TRADE -> {
                long takerId     = in.getLong(payloadStart);
                long makerId     = in.getLong(payloadStart + 8);
                byte takerSide   = in.get(payloadStart + 16);
                long price       = in.getLong(payloadStart + 17);
                long quantity    = in.getLong(payloadStart + 25);
                long ts          = in.getLong(payloadStart + 33);
                visitor.onTrade(seq, takerId, makerId, takerSide, price, quantity, ts);
            }
            case FeedMessageType.HEARTBEAT -> visitor.onHeartbeat(seq);
            case FeedMessageType.SNAPSHOT -> {
                long startingSeq = in.getLong(payloadStart);
                short part       = in.getShort(payloadStart + 8);
                short total      = in.getShort(payloadStart + 10);
                visitor.onSnapshotBegin(seq, startingSeq, part, total, levelCount);
                int off = payloadStart + SNAPSHOT_HEADER_SIZE;
                for (int i = 0; i < levelCount; i++) {
                    byte side        = in.get(off);
                    long price       = in.getLong(off + 1);
                    long quantity    = in.getLong(off + 9);
                    int orderCount   = in.getInt(off + 17);
                    visitor.onSnapshotLevel(side, price, quantity, orderCount);
                    off += SNAPSHOT_LEVEL_ENTRY_SIZE;
                }
                visitor.onSnapshotEnd(seq);
            }
            default -> throw new AssertionError();
        }

        in.position(start + totalLen);
        return DECODED;
    }

    private int computeCrc(ByteBuffer buf, int fromInclusive, int toExclusive) {
        crc.reset();
        for (int i = fromInclusive; i < toExclusive; i++) {
            crc.update(buf.get(i));
        }
        return (int) crc.getValue();
    }

    public interface FeedVisitor {
        default void onBookUpdate(long seq, byte side, long price, long quantity, int orderCount) { }
        default void onTrade(long seq, long takerOrderId, long makerOrderId, byte takerSide,
                             long price, long quantity, long timestampNanos) { }
        default void onHeartbeat(long seq) { }
        default void onSnapshotBegin(long seq, long startingSequence, short partNumber,
                                     short totalParts, int levelCount) { }
        default void onSnapshotLevel(byte side, long price, long quantity, int orderCount) { }
        default void onSnapshotEnd(long seq) { }
    }
}
