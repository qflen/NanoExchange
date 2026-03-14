package com.nanoexchange.network.protocol;

/**
 * Byte constants for wire message types. Values are part of the public protocol; never change
 * a value already in production. Ranges:
 * <ul>
 *   <li>0x00–0x1F — inbound (client → engine)</li>
 *   <li>0x20–0x3F — outbound (engine → client)</li>
 *   <li>0x40–0x4F — session control (either direction)</li>
 * </ul>
 * See {@code docs/PROTOCOL.md} for the byte-level layout of each message.
 */
public final class MessageType {
    private MessageType() { }

    // ---- inbound -------------------------------------------------------------------------
    public static final byte NEW_ORDER = 0x01;
    public static final byte CANCEL    = 0x02;
    public static final byte MODIFY    = 0x03;

    // ---- outbound ------------------------------------------------------------------------
    public static final byte ACK           = 0x20;
    public static final byte PARTIAL_FILL  = 0x21;
    public static final byte FILL          = 0x22;
    public static final byte CANCELED      = 0x23;
    public static final byte REJECTED      = 0x24;
    public static final byte MODIFIED      = 0x25;

    // ---- session control -----------------------------------------------------------------
    public static final byte HEARTBEAT = 0x40;

    /** Human-readable label for logs and tests. */
    public static String name(byte type) {
        return switch (type) {
            case NEW_ORDER -> "NEW_ORDER";
            case CANCEL -> "CANCEL";
            case MODIFY -> "MODIFY";
            case ACK -> "ACK";
            case PARTIAL_FILL -> "PARTIAL_FILL";
            case FILL -> "FILL";
            case CANCELED -> "CANCELED";
            case REJECTED -> "REJECTED";
            case MODIFIED -> "MODIFIED";
            case HEARTBEAT -> "HEARTBEAT";
            default -> "UNKNOWN(0x" + Integer.toHexString(type & 0xFF) + ")";
        };
    }
}
