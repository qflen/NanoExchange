package com.nanoexchange.network.protocol;

import com.nanoexchange.engine.ExecutionReport;
import com.nanoexchange.engine.Order;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Encode and decode frames on the order-entry wire. All multi-byte integers are little-endian.
 *
 * <h2>Frame layout</h2>
 * <pre>
 *   offset  size  field
 *   ------  ----  ---------------------------------------
 *        0     4  length (LE int32) — bytes *following* this field, i.e. type + payload + crc
 *        4     1  type (see {@link MessageType})
 *        5     N  payload (variable, per type)
 *    5 + N     4  crc32 over bytes [4, 5+N)  — type + payload, in LE int32
 * </pre>
 * Length-prefix framing is used (not a delimiter) because payloads contain arbitrary binary
 * bytes — any delimiter byte choice would eventually collide.
 *
 * <h2>CRC coverage</h2>
 * The CRC is computed over the type byte and payload bytes only — NOT over the length prefix
 * and NOT over the CRC itself. This keeps CRC semantics content-addressed: identical messages
 * always have identical CRCs regardless of how framing is wrapped.
 *
 * <p>The codec is allocation-free: callers pass in pre-sized {@link ByteBuffer}s in the
 * correct byte order. Decode into a provided {@link Order} or {@link ExecutionReport} (the
 * engine owns pooling of those objects).
 */
public final class WireCodec {

    /** Bytes of prefix (length) + suffix (CRC). Per-message overhead is this plus the 1-byte type. */
    public static final int FRAME_OVERHEAD = 4 + 4;
    /** Byte size of the NEW_ORDER payload (see format table in docs/PROTOCOL.md). */
    public static final int NEW_ORDER_PAYLOAD_SIZE = 8 + 8 + 1 + 1 + 8 + 8 + 8 + 8;
    /** Byte size of the CANCEL payload. */
    public static final int CANCEL_PAYLOAD_SIZE = 8 + 8;
    /** Byte size of the MODIFY payload. */
    public static final int MODIFY_PAYLOAD_SIZE = 8 + 8 + 8 + 8;
    /**
     * Byte size of the execution report payload (any of ACK/FILL/PARTIAL_FILL/CANCELED/
     * REJECTED/MODIFIED). The kind is already conveyed by the frame type byte, so we do not
     * repeat it inside the payload.
     */
    public static final int EXEC_REPORT_PAYLOAD_SIZE =
            8 /* orderId */
          + 8 /* clientId */
          + 1 /* side */
          + 8 /* price */
          + 8 /* executedQty */
          + 8 /* executedPrice */
          + 8 /* remainingQty */
          + 8 /* counterpartyOrderId */
          + 1 /* rejectReason */
          + 8 /* timestampNanos */;

    private final CRC32 crc = new CRC32();

    // =========================================================================================
    // encoding
    // =========================================================================================

    /**
     * Encode a NEW_ORDER frame for {@code order} into {@code out}. The buffer's position
     * advances by the total frame size. The buffer must have {@link ByteOrder#LITTLE_ENDIAN}.
     */
    public void encodeNewOrder(Order order, ByteBuffer out) {
        int startPos = out.position();
        // length placeholder
        out.putInt(0);
        int headerStart = out.position(); // CRC range begins here (the type byte)
        out.put(MessageType.NEW_ORDER);
        out.putLong(order.clientId());
        out.putLong(order.orderId());
        out.put(order.side());
        out.put(order.orderType());
        out.putLong(order.price());
        out.putLong(order.quantity());
        out.putLong(order.displaySize());
        out.putLong(order.timestampNanos());
        int crcEnd = out.position();
        finishFrame(out, startPos, headerStart, crcEnd);
    }

    public void encodeCancel(long orderId, long timestampNanos, ByteBuffer out) {
        int startPos = out.position();
        out.putInt(0);
        int headerStart = out.position();
        out.put(MessageType.CANCEL);
        out.putLong(orderId);
        out.putLong(timestampNanos);
        int crcEnd = out.position();
        finishFrame(out, startPos, headerStart, crcEnd);
    }

    public void encodeModify(long orderId, long newPrice, long newQuantity, long timestampNanos,
                             ByteBuffer out) {
        int startPos = out.position();
        out.putInt(0);
        int headerStart = out.position();
        out.put(MessageType.MODIFY);
        out.putLong(orderId);
        out.putLong(newPrice);
        out.putLong(newQuantity);
        out.putLong(timestampNanos);
        int crcEnd = out.position();
        finishFrame(out, startPos, headerStart, crcEnd);
    }

    public void encodeExecutionReport(ExecutionReport report, ByteBuffer out) {
        int startPos = out.position();
        out.putInt(0);
        int headerStart = out.position();
        byte wireType = switch (report.reportType) {
            case ExecutionReport.TYPE_ACK -> MessageType.ACK;
            case ExecutionReport.TYPE_PARTIAL_FILL -> MessageType.PARTIAL_FILL;
            case ExecutionReport.TYPE_FILL -> MessageType.FILL;
            case ExecutionReport.TYPE_CANCELED -> MessageType.CANCELED;
            case ExecutionReport.TYPE_REJECTED -> MessageType.REJECTED;
            case ExecutionReport.TYPE_MODIFIED -> MessageType.MODIFIED;
            default -> throw new IllegalArgumentException("unknown report type " + report.reportType);
        };
        out.put(wireType);
        out.putLong(report.orderId);
        out.putLong(report.clientId);
        out.put(report.side);
        out.putLong(report.price);
        out.putLong(report.executedQuantity);
        out.putLong(report.executedPrice);
        out.putLong(report.remainingQuantity);
        out.putLong(report.counterpartyOrderId);
        out.put(report.rejectReason);
        out.putLong(report.timestampNanos);
        int crcEnd = out.position();
        finishFrame(out, startPos, headerStart, crcEnd);
    }

    public void encodeHeartbeat(ByteBuffer out) {
        int startPos = out.position();
        out.putInt(0);
        int headerStart = out.position();
        out.put(MessageType.HEARTBEAT);
        int crcEnd = out.position();
        finishFrame(out, startPos, headerStart, crcEnd);
    }

    private void finishFrame(ByteBuffer out, int startPos, int headerStart, int crcEnd) {
        int bodyLen = crcEnd - headerStart; // type + payload
        int crcValue = computeCrc(out, headerStart, crcEnd);
        out.putInt(crcEnd, crcValue);
        // length field covers everything after itself: body + crc
        out.putInt(startPos, bodyLen + 4);
        out.position(crcEnd + 4);
    }

    // =========================================================================================
    // decoding
    // =========================================================================================

    /**
     * Decode status from {@link #tryDecode}. Exactly one of {@link #NEED_MORE_BYTES},
     * {@link #DECODED}, or {@link #BAD_FRAME} is returned.
     */
    public static final int NEED_MORE_BYTES = 0;
    public static final int DECODED = 1;
    public static final int BAD_FRAME = -1;

    /**
     * Attempt to decode one complete frame from {@code in}. On {@link #DECODED}, the decoded
     * message type and fields are written into {@code sink}, and {@code in.position()} advances
     * past the frame. On {@link #NEED_MORE_BYTES}, {@code in}'s position is unchanged and more
     * bytes must be read from the wire. On {@link #BAD_FRAME}, the frame was malformed or the
     * CRC failed; the caller should close the connection.
     *
     * <p>The sink is a callback-style receiver so the codec can feed decoded fields into
     * pooled {@link Order} / {@link ExecutionReport} instances owned by the caller without
     * allocating a decoded-message DTO.
     */
    public int tryDecode(ByteBuffer in, DecodedSink sink) {
        int start = in.position();
        int remaining = in.remaining();
        if (remaining < 4) return NEED_MORE_BYTES;
        int length = in.getInt(start);
        if (length < 5 /* type + crc */ || length > 1 << 20) {
            // sanity bound: absurd length means desync / corruption
            return BAD_FRAME;
        }
        if (remaining < 4 + length) return NEED_MORE_BYTES;

        int headerStart = start + 4;
        int crcPos = headerStart + length - 4; // crc is the trailing 4 bytes
        int expected = in.getInt(crcPos);
        int actual = computeCrc(in, headerStart, crcPos);
        if (expected != actual) return BAD_FRAME;

        byte type = in.get(headerStart);
        int payloadStart = headerStart + 1;
        int payloadLen = length - 1 - 4; // minus type, minus crc
        switch (type) {
            case MessageType.NEW_ORDER -> {
                if (payloadLen != NEW_ORDER_PAYLOAD_SIZE) return BAD_FRAME;
                long clientId       = in.getLong(payloadStart);
                long orderId        = in.getLong(payloadStart + 8);
                byte side           = in.get(payloadStart + 16);
                byte orderType      = in.get(payloadStart + 17);
                long price          = in.getLong(payloadStart + 18);
                long quantity       = in.getLong(payloadStart + 26);
                long displaySize    = in.getLong(payloadStart + 34);
                long timestampNanos = in.getLong(payloadStart + 42);
                sink.onNewOrder(clientId, orderId, side, orderType, price, quantity, displaySize, timestampNanos);
            }
            case MessageType.CANCEL -> {
                if (payloadLen != CANCEL_PAYLOAD_SIZE) return BAD_FRAME;
                long orderId        = in.getLong(payloadStart);
                long timestampNanos = in.getLong(payloadStart + 8);
                sink.onCancel(orderId, timestampNanos);
            }
            case MessageType.MODIFY -> {
                if (payloadLen != MODIFY_PAYLOAD_SIZE) return BAD_FRAME;
                long orderId        = in.getLong(payloadStart);
                long newPrice       = in.getLong(payloadStart + 8);
                long newQuantity    = in.getLong(payloadStart + 16);
                long timestampNanos = in.getLong(payloadStart + 24);
                sink.onModify(orderId, newPrice, newQuantity, timestampNanos);
            }
            case MessageType.ACK, MessageType.PARTIAL_FILL, MessageType.FILL,
                 MessageType.CANCELED, MessageType.REJECTED, MessageType.MODIFIED -> {
                if (payloadLen != EXEC_REPORT_PAYLOAD_SIZE) return BAD_FRAME;
                byte reportType          = wireToReportType(type);
                long orderId             = in.getLong(payloadStart);
                long clientId            = in.getLong(payloadStart + 8);
                byte side                = in.get(payloadStart + 16);
                long price               = in.getLong(payloadStart + 17);
                long executedQuantity    = in.getLong(payloadStart + 25);
                long executedPrice       = in.getLong(payloadStart + 33);
                long remainingQuantity   = in.getLong(payloadStart + 41);
                long counterpartyOrderId = in.getLong(payloadStart + 49);
                byte rejectReason        = in.get(payloadStart + 57);
                long timestampNanos      = in.getLong(payloadStart + 58);
                sink.onExecutionReport(reportType, orderId, clientId, side, price,
                        executedQuantity, executedPrice, remainingQuantity,
                        counterpartyOrderId, rejectReason, timestampNanos);
            }
            case MessageType.HEARTBEAT -> {
                if (payloadLen != 0) return BAD_FRAME;
                sink.onHeartbeat();
            }
            default -> {
                return BAD_FRAME;
            }
        }

        in.position(start + 4 + length);
        return DECODED;
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private static byte wireToReportType(byte wireType) {
        return switch (wireType) {
            case MessageType.ACK -> ExecutionReport.TYPE_ACK;
            case MessageType.PARTIAL_FILL -> ExecutionReport.TYPE_PARTIAL_FILL;
            case MessageType.FILL -> ExecutionReport.TYPE_FILL;
            case MessageType.CANCELED -> ExecutionReport.TYPE_CANCELED;
            case MessageType.REJECTED -> ExecutionReport.TYPE_REJECTED;
            case MessageType.MODIFIED -> ExecutionReport.TYPE_MODIFIED;
            default -> throw new IllegalStateException("not a report type " + wireType);
        };
    }

    private int computeCrc(ByteBuffer buf, int fromInclusive, int toExclusive) {
        crc.reset();
        for (int i = fromInclusive; i < toExclusive; i++) {
            crc.update(buf.get(i));
        }
        return (int) crc.getValue();
    }

    /**
     * Callback-style receiver for decoded messages. Exactly one method is invoked per
     * successful {@link #tryDecode} call.
     */
    public interface DecodedSink {
        default void onNewOrder(long clientId, long orderId, byte side, byte orderType,
                                long price, long quantity, long displaySize, long timestampNanos) { }
        default void onCancel(long orderId, long timestampNanos) { }
        default void onModify(long orderId, long newPrice, long newQuantity, long timestampNanos) { }
        default void onExecutionReport(byte reportType, long orderId, long clientId, byte side,
                                       long price, long executedQuantity, long executedPrice,
                                       long remainingQuantity, long counterpartyOrderId,
                                       byte rejectReason, long timestampNanos) { }
        default void onHeartbeat() { }
    }
}
