package com.nanoexchange.network.feed;

import com.nanoexchange.engine.Order;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class FeedCodecTest {

    private static ByteBuffer buf(int cap) {
        return ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static final class Recorder implements FeedCodec.FeedVisitor {
        final List<String> log = new ArrayList<>();
        @Override
        public void onBookUpdate(long seq, byte side, long price, long quantity, int orderCount) {
            log.add("BU seq=" + seq + " s=" + side + " p=" + price + " q=" + quantity + " n=" + orderCount);
        }
        @Override
        public void onTrade(long seq, long takerOrderId, long makerOrderId, byte takerSide,
                            long price, long quantity, long timestampNanos) {
            log.add("TR seq=" + seq + " t=" + takerOrderId + " m=" + makerOrderId
                    + " s=" + takerSide + " p=" + price + " q=" + quantity + " ts=" + timestampNanos);
        }
        @Override
        public void onHeartbeat(long seq) {
            log.add("HB seq=" + seq);
        }
        @Override
        public void onSnapshotBegin(long seq, long startingSequence, short partNumber,
                                    short totalParts, int levelCount) {
            log.add("SB seq=" + seq + " start=" + startingSequence + " part=" + partNumber
                    + "/" + totalParts + " levels=" + levelCount);
        }
        @Override
        public void onSnapshotLevel(byte side, long price, long quantity, int orderCount) {
            log.add("SL s=" + side + " p=" + price + " q=" + quantity + " n=" + orderCount);
        }
        @Override
        public void onSnapshotEnd(long seq) {
            log.add("SE seq=" + seq);
        }
    }

    @Test
    void bookUpdateRoundTrip() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(128);
        codec.encodeBookUpdate(42L, Order.SIDE_BUY, 100_50000000L, 250L, 3, b);
        b.flip();
        Recorder r = new Recorder();
        assertThat(codec.decode(b, r)).isEqualTo(FeedCodec.DECODED);
        assertThat(r.log).containsExactly("BU seq=42 s=0 p=10050000000 q=250 n=3");
    }

    @Test
    void bookUpdateRemovesLevelWhenQuantityZero() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(128);
        codec.encodeBookUpdate(99L, Order.SIDE_SELL, 200L, 0L, 0, b);
        b.flip();
        Recorder r = new Recorder();
        codec.decode(b, r);
        assertThat(r.log.get(0)).contains("q=0").contains("n=0");
    }

    @Test
    void tradeRoundTrip() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(128);
        codec.encodeTrade(7L, 100L, 200L, Order.SIDE_BUY, 105_00000000L, 50L, 1234567L, b);
        b.flip();
        Recorder r = new Recorder();
        assertThat(codec.decode(b, r)).isEqualTo(FeedCodec.DECODED);
        assertThat(r.log).containsExactly("TR seq=7 t=100 m=200 s=0 p=10500000000 q=50 ts=1234567");
    }

    @Test
    void heartbeatRoundTrip() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(32);
        codec.encodeHeartbeat(1L, b);
        b.flip();
        Recorder r = new Recorder();
        assertThat(codec.decode(b, r)).isEqualTo(FeedCodec.DECODED);
        assertThat(r.log).containsExactly("HB seq=1");
    }

    @Test
    void snapshotRoundTripWithLevels() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(1024);
        int start = codec.beginSnapshot(10L, 9L, (short) 1, (short) 1, b);
        codec.appendSnapshotLevel(Order.SIDE_BUY,  99_00000000L,  100L, 1, b);
        codec.appendSnapshotLevel(Order.SIDE_BUY,  98_00000000L,  200L, 2, b);
        codec.appendSnapshotLevel(Order.SIDE_SELL, 101_00000000L, 150L, 3, b);
        codec.finishSnapshot(start, 3, b);
        b.flip();
        Recorder r = new Recorder();
        assertThat(codec.decode(b, r)).isEqualTo(FeedCodec.DECODED);
        assertThat(r.log).containsExactly(
                "SB seq=10 start=9 part=1/1 levels=3",
                "SL s=0 p=9900000000 q=100 n=1",
                "SL s=0 p=9800000000 q=200 n=2",
                "SL s=1 p=10100000000 q=150 n=3",
                "SE seq=10"
        );
    }

    @Test
    void emptySnapshotDecodes() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(64);
        int start = codec.beginSnapshot(5L, 4L, (short) 1, (short) 1, b);
        codec.finishSnapshot(start, 0, b);
        b.flip();
        Recorder r = new Recorder();
        assertThat(codec.decode(b, r)).isEqualTo(FeedCodec.DECODED);
        assertThat(r.log).containsExactly(
                "SB seq=5 start=4 part=1/1 levels=0",
                "SE seq=5"
        );
    }

    @Test
    void corruptedCrcReturnsBadFrame() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(64);
        codec.encodeHeartbeat(1L, b);
        // flip a byte in the sequence
        b.put(2, (byte) 0xFF);
        b.flip();
        assertThat(codec.decode(b, new Recorder())).isEqualTo(FeedCodec.BAD_FRAME);
    }

    @Test
    void unknownTypeReturnsBadFrame() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(32);
        b.putLong(1L);
        b.put((byte) 0x7F);
        // impossible to compute proper crc without going through encode; any crc will fail
        b.putInt(0);
        b.flip();
        assertThat(codec.decode(b, new Recorder())).isEqualTo(FeedCodec.BAD_FRAME);
    }

    @Test
    void sequenceGapDetectableAcrossMessages() {
        FeedCodec codec = new FeedCodec();
        ByteBuffer b = buf(256);
        codec.encodeHeartbeat(1L, b);
        codec.encodeHeartbeat(3L, b); // gap: seq 2 dropped
        codec.encodeHeartbeat(4L, b);
        b.flip();
        List<Long> seen = new ArrayList<>();
        FeedCodec.FeedVisitor v = new FeedCodec.FeedVisitor() {
            @Override public void onHeartbeat(long seq) { seen.add(seq); }
        };
        while (b.hasRemaining()) codec.decode(b, v);
        assertThat(seen).containsExactly(1L, 3L, 4L);
    }
}
