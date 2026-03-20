package com.nanoexchange.engine;

/**
 * A single order in the book. Deliberately a mutable, pooled "struct" rather than an
 * immutable record: the matching engine reuses {@code Order} instances via {@link ObjectPool}
 * to keep the hot path allocation-free.
 *
 * <p>Fields are package-private and mutated directly by the engine. External callers must
 * go through the pool: acquire an instance, {@link #reset(long, long, byte, long, long, byte, long)}
 * it with the desired values, process it, and release it back to the pool.
 *
 * <p>The {@code prev} / {@code next} pointers are intrusive linked-list pointers used by the
 * per-price-level FIFO queue in {@code OrderBook} (stage 2). They live on the order itself to
 * avoid allocating a separate wrapper node on every insert.
 */
public final class Order {

    /** Buy side marker. Value matches the wire protocol. */
    public static final byte SIDE_BUY = 0;
    /** Sell side marker. Value matches the wire protocol. */
    public static final byte SIDE_SELL = 1;

    /** Resting limit order. */
    public static final byte TYPE_LIMIT = 0;
    /** Immediate market order, no resting portion. */
    public static final byte TYPE_MARKET = 1;
    /** Immediate-or-cancel: match what is available, cancel the rest. */
    public static final byte TYPE_IOC = 2;
    /** Fill-or-kill: match all of it or none of it. */
    public static final byte TYPE_FOK = 3;
    /** Iceberg: only {@code displayQuantity} is visible; refills from hidden reserve. */
    public static final byte TYPE_ICEBERG = 4;

    long orderId;
    long clientId;
    byte side;
    byte orderType;
    long price;
    long quantity;
    long displayQuantity;
    long hiddenQuantity;
    long displaySize;
    long timestampNanos;

    Order prev;
    Order next;

    /**
     * Reset this order to a fresh state. Called by the pool on acquire (or directly by the
     * engine immediately after acquire) so no stale data from a prior use leaks into the new one.
     *
     * @param orderId        unique, monotonically increasing order id
     * @param clientId       originating client (used for self-trade prevention)
     * @param side           {@link #SIDE_BUY} or {@link #SIDE_SELL}
     * @param orderType      one of the {@code TYPE_*} constants
     * @param price          fixed-point price (price × 1e8); unused for market orders
     * @param quantity       remaining quantity
     * @param timestampNanos engine-local nanosecond timestamp ({@link System#nanoTime()})
     */
    public void reset(
            long orderId,
            long clientId,
            byte side,
            byte orderType,
            long price,
            long quantity,
            long timestampNanos) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.side = side;
        this.orderType = orderType;
        this.price = price;
        this.quantity = quantity;
        this.displayQuantity = orderType == TYPE_ICEBERG ? 0L : quantity;
        this.hiddenQuantity = 0L;
        this.displaySize = 0L;
        this.timestampNanos = timestampNanos;
        this.prev = null;
        this.next = null;
    }

    /**
     * Configure this order as an iceberg. Must be called after {@link #reset} on an order
     * whose {@code orderType} is {@link #TYPE_ICEBERG}. The visible portion becomes
     * {@code displaySize} (or the remaining quantity if smaller), the rest is hidden.
     *
     * @param displaySize maximum visible quantity at any time (the "tip"); must be &gt; 0
     * @throws IllegalStateException    if this order is not an iceberg
     * @throws IllegalArgumentException if {@code displaySize} is not positive
     */
    public void configureIceberg(long displaySize) {
        if (orderType != TYPE_ICEBERG) {
            throw new IllegalStateException("configureIceberg called on non-iceberg order");
        }
        if (displaySize <= 0) {
            throw new IllegalArgumentException("displaySize must be positive");
        }
        this.displaySize = displaySize;
        long visible = Math.min(displaySize, quantity);
        this.displayQuantity = visible;
        this.hiddenQuantity = quantity - visible;
    }

    /** Clear fields before returning to the pool. Prevents accidental reuse of stale refs. */
    public void clear() {
        this.orderId = 0L;
        this.clientId = 0L;
        this.side = 0;
        this.orderType = 0;
        this.price = 0L;
        this.quantity = 0L;
        this.displayQuantity = 0L;
        this.hiddenQuantity = 0L;
        this.displaySize = 0L;
        this.timestampNanos = 0L;
        this.prev = null;
        this.next = null;
    }

    public long orderId()        { return orderId; }
    public long clientId()       { return clientId; }
    public byte side()           { return side; }
    public byte orderType()      { return orderType; }
    public long price()          { return price; }
    public long quantity()       { return quantity; }
    public long displayQuantity(){ return displayQuantity; }
    public long hiddenQuantity() { return hiddenQuantity; }
    public long displaySize()    { return displaySize; }
    public long timestampNanos() { return timestampNanos; }

    @Override
    public String toString() {
        return "Order{id=" + orderId
                + ", client=" + clientId
                + ", side=" + (side == SIDE_BUY ? "BUY" : "SELL")
                + ", type=" + orderType
                + ", price=" + price
                + ", qty=" + quantity
                + "}";
    }
}
