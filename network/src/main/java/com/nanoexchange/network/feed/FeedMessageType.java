package com.nanoexchange.network.feed;

/**
 * Message-type constants for the UDP market-data feed. Separate numbering space from the
 * order-entry wire protocol — different transport, different schema.
 */
public final class FeedMessageType {
    private FeedMessageType() { }

    /** Incremental book update: one price level, one side, new aggregate quantity. */
    public static final byte BOOK_UPDATE = 0x01;
    /** A trade occurred between a taker and a maker. */
    public static final byte TRADE       = 0x02;
    /** Full book snapshot (possibly across multiple packets). */
    public static final byte SNAPSHOT    = 0x03;
    /** Liveness signal; clients use gaps in timing to trigger reconnect. */
    public static final byte HEARTBEAT   = 0x04;

    public static String name(byte type) {
        return switch (type) {
            case BOOK_UPDATE -> "BOOK_UPDATE";
            case TRADE -> "TRADE";
            case SNAPSHOT -> "SNAPSHOT";
            case HEARTBEAT -> "HEARTBEAT";
            default -> "UNKNOWN(0x" + Integer.toHexString(type & 0xFF) + ")";
        };
    }
}
