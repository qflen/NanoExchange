package com.nanoexchange.engine;

/**
 * One execution event emitted by the matching engine in response to an inbound order.
 * Multiple reports can be produced per inbound order (a partial fill followed by another
 * partial fill, or a sequence of ACK + PARTIAL + DONE).
 *
 * <p>Mutable and pooled. The engine acquires an instance from its pool, fills it in, invokes
 * the sink callback, then releases back to the pool. The sink must copy any fields it wants
 * to retain — the object will be reused the moment the callback returns.
 */
public final class ExecutionReport {

    /** New order accepted, no fill yet. */
    public static final byte TYPE_ACK = 0;
    /** Partial fill, order remains working (resting limit or continuing to sweep). */
    public static final byte TYPE_PARTIAL_FILL = 1;
    /** Final fill — order is now done. */
    public static final byte TYPE_FILL = 2;
    /** Order canceled (explicit cancel, or IOC leftover, or FOK abort). */
    public static final byte TYPE_CANCELED = 3;
    /** Order rejected without entering the book. */
    public static final byte TYPE_REJECTED = 4;
    /** Modify applied (cancel-replace completed). */
    public static final byte TYPE_MODIFIED = 5;

    /** Reject because a same-client resting order would otherwise be crossed (STP). */
    public static final byte REJECT_SELF_TRADE = 1;
    /** FOK could not be filled in full. */
    public static final byte REJECT_FOK_UNFILLABLE = 2;
    /** Cancel targeted an order that does not exist. */
    public static final byte REJECT_UNKNOWN_ORDER = 3;
    /** Market order found no liquidity on the opposing side. */
    public static final byte REJECT_NO_LIQUIDITY = 4;
    /** Modify targeted a nonexistent order. */
    public static final byte REJECT_MODIFY_UNKNOWN = 5;

    public byte reportType;
    public byte rejectReason;
    public long orderId;
    public long clientId;
    public byte side;
    public long price;
    public long executedQuantity;
    public long executedPrice;
    public long remainingQuantity;
    public long counterpartyOrderId;
    public long timestampNanos;

    /** Reset all fields. Called by the engine before each fill-in. */
    public void clear() {
        this.reportType = 0;
        this.rejectReason = 0;
        this.orderId = 0L;
        this.clientId = 0L;
        this.side = 0;
        this.price = 0L;
        this.executedQuantity = 0L;
        this.executedPrice = 0L;
        this.remainingQuantity = 0L;
        this.counterpartyOrderId = 0L;
        this.timestampNanos = 0L;
    }

    @Override
    public String toString() {
        return "ExecutionReport{type=" + reportType
                + ", id=" + orderId
                + ", execQty=" + executedQuantity
                + ", execPx=" + executedPrice
                + ", remQty=" + remainingQuantity
                + ", reject=" + rejectReason
                + "}";
    }
}
