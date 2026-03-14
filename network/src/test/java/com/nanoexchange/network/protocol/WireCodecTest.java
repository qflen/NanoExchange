package com.nanoexchange.network.protocol;

import com.nanoexchange.engine.ExecutionReport;
import com.nanoexchange.engine.Order;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

final class WireCodecTest {

    private static ByteBuffer buf(int cap) {
        return ByteBuffer.allocate(cap).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Order makeOrder(long id, long client, byte side, byte type, long price,
                                   long qty, long displaySize) {
        Order o = new Order();
        o.reset(id, client, side, type, price, qty, 12345L);
        if (type == Order.TYPE_ICEBERG) o.configureIceberg(displaySize);
        return o;
    }

    private static final class Capture implements WireCodec.DecodedSink {
        final List<String> log = new ArrayList<>();
        @Override
        public void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                               long price, long quantity, long displaySize, long timestampNanos) {
            log.add(String.format("NEW c=%d o=%d s=%d t=%d p=%d q=%d ds=%d ts=%d",
                    clientId, orderId, side, orderType, price, quantity, displaySize, timestampNanos));
        }
        @Override
        public void onCancel(long orderId, long timestampNanos) {
            log.add("CANCEL o=" + orderId + " ts=" + timestampNanos);
        }
        @Override
        public void onModify(long orderId, long newPrice, long newQuantity, long timestampNanos) {
            log.add("MODIFY o=" + orderId + " p=" + newPrice + " q=" + newQuantity + " ts=" + timestampNanos);
        }
        @Override
        public void onExecutionReport(byte reportType, long orderId, long clientId, byte side,
                                      long price, long executedQuantity, long executedPrice,
                                      long remainingQuantity, long counterpartyOrderId,
                                      byte rejectReason, long timestampNanos) {
            log.add(String.format("REPORT rt=%d o=%d c=%d s=%d p=%d xq=%d xp=%d rq=%d cp=%d rr=%d ts=%d",
                    reportType, orderId, clientId, side, price, executedQuantity, executedPrice,
                    remainingQuantity, counterpartyOrderId, rejectReason, timestampNanos));
        }
        @Override
        public void onHeartbeat() { log.add("HEARTBEAT"); }
    }

    @Test
    void newOrderRoundTrip() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(128);
        Order o = makeOrder(777, 42, Order.SIDE_BUY, Order.TYPE_LIMIT, 100_50000000L, 500L, 0L);
        codec.encodeNewOrder(o, b);
        b.flip();

        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log).containsExactly(
                "NEW c=42 o=777 s=0 t=0 p=10050000000 q=500 ds=0 ts=12345");
        assertThat(b.remaining()).isEqualTo(0);
    }

    @Test
    void icebergRoundTripPreservesDisplaySize() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(128);
        Order o = makeOrder(1, 2, Order.SIDE_SELL, Order.TYPE_ICEBERG, 99_00000000L, 1000L, 100L);
        codec.encodeNewOrder(o, b);
        b.flip();
        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log.get(0)).contains("ds=100");
        assertThat(cap.log.get(0)).contains("t=" + Order.TYPE_ICEBERG);
    }

    @Test
    void cancelRoundTrip() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(64);
        codec.encodeCancel(999L, 88888L, b);
        b.flip();
        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log).containsExactly("CANCEL o=999 ts=88888");
    }

    @Test
    void modifyRoundTrip() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(64);
        codec.encodeModify(123L, 101_00000000L, 250L, 7L, b);
        b.flip();
        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log).containsExactly("MODIFY o=123 p=10100000000 q=250 ts=7");
    }

    @Test
    void executionReportRoundTrip() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(128);
        ExecutionReport r = new ExecutionReport();
        r.reportType = ExecutionReport.TYPE_PARTIAL_FILL;
        r.orderId = 101;
        r.clientId = 202;
        r.side = Order.SIDE_BUY;
        r.price = 100_00000000L;
        r.executedQuantity = 50L;
        r.executedPrice = 99_50000000L;
        r.remainingQuantity = 150L;
        r.counterpartyOrderId = 303;
        r.timestampNanos = 9999L;
        codec.encodeExecutionReport(r, b);
        b.flip();
        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log).containsExactly(
                "REPORT rt=1 o=101 c=202 s=0 p=10000000000 xq=50 xp=9950000000 rq=150 cp=303 rr=0 ts=9999");
    }

    @Test
    void rejectRoundTripCarriesReason() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(128);
        ExecutionReport r = new ExecutionReport();
        r.reportType = ExecutionReport.TYPE_REJECTED;
        r.rejectReason = ExecutionReport.REJECT_SELF_TRADE;
        r.orderId = 5;
        r.clientId = 1;
        r.side = Order.SIDE_SELL;
        codec.encodeExecutionReport(r, b);
        b.flip();
        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log.get(0)).contains("rt=4").contains("rr=1");
    }

    @Test
    void heartbeatRoundTrip() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(32);
        codec.encodeHeartbeat(b);
        b.flip();
        Capture cap = new Capture();
        assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        assertThat(cap.log).containsExactly("HEARTBEAT");
    }

    @Test
    void multipleFramesDecodedBackToBack() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(512);
        Order o = makeOrder(1, 1, Order.SIDE_BUY, Order.TYPE_LIMIT, 100L, 10L, 0L);
        codec.encodeNewOrder(o, b);
        codec.encodeCancel(1L, 0L, b);
        codec.encodeHeartbeat(b);
        b.flip();
        Capture cap = new Capture();
        for (int i = 0; i < 3; i++) {
            assertThat(codec.tryDecode(b, cap)).isEqualTo(WireCodec.DECODED);
        }
        assertThat(cap.log).hasSize(3);
        assertThat(cap.log.get(0)).startsWith("NEW");
        assertThat(cap.log.get(1)).startsWith("CANCEL");
        assertThat(cap.log.get(2)).isEqualTo("HEARTBEAT");
    }

    @Test
    void partialFrameReturnsNeedMoreBytes() {
        WireCodec codec = new WireCodec();
        ByteBuffer full = buf(128);
        Order o = makeOrder(1, 1, Order.SIDE_BUY, Order.TYPE_LIMIT, 100L, 10L, 0L);
        codec.encodeNewOrder(o, full);
        full.flip();
        // feed byte-by-byte
        ByteBuffer acc = buf(128);
        Capture cap = new Capture();
        while (full.hasRemaining()) {
            acc.put(full.get());
            acc.flip();
            int status = codec.tryDecode(acc, cap);
            if (status == WireCodec.DECODED) {
                // must be the last byte
                assertThat(full.hasRemaining()).isFalse();
            } else {
                assertThat(status).isEqualTo(WireCodec.NEED_MORE_BYTES);
            }
            acc.compact();
        }
        assertThat(cap.log).hasSize(1);
    }

    @Test
    void corruptedCrcReturnsBadFrame() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(64);
        codec.encodeCancel(1L, 1L, b);
        // flip a byte in the middle of the payload
        b.put(8, (byte) (b.get(8) ^ 0xFF));
        b.flip();
        assertThat(codec.tryDecode(b, new Capture())).isEqualTo(WireCodec.BAD_FRAME);
    }

    @Test
    void unknownTypeReturnsBadFrame() {
        // synthesize a frame with an unknown type byte but a valid CRC
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(32);
        b.putInt(5); // length = type(1) + payload(0) + crc(4)
        b.put((byte) 0x7A); // unknown
        // compute crc over just the type byte
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(0x7A);
        b.putInt((int) crc.getValue());
        b.flip();
        assertThat(codec.tryDecode(b, new Capture())).isEqualTo(WireCodec.BAD_FRAME);
    }

    @Test
    void absurdLengthReturnsBadFrame() {
        WireCodec codec = new WireCodec();
        ByteBuffer b = buf(16);
        b.putInt(Integer.MAX_VALUE);
        b.flip();
        assertThat(codec.tryDecode(b, new Capture())).isEqualTo(WireCodec.BAD_FRAME);
    }

    @Test
    void fuzzRandomBytesNeverCrashesOrLoops() {
        WireCodec codec = new WireCodec();
        Random rng = new Random(0xC0FFEEL);
        byte[] bytes = new byte[4096];
        Capture cap = new Capture();
        for (int trial = 0; trial < 2000; trial++) {
            rng.nextBytes(bytes);
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int safetyLimit = 2000;
            while (safetyLimit-- > 0) {
                int status = codec.tryDecode(b, cap);
                if (status != WireCodec.DECODED) break;
            }
            assertThat(safetyLimit).isGreaterThan(0); // no runaway loop
        }
    }
}
