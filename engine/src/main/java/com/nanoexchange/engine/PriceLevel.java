package com.nanoexchange.engine;

/**
 * A single price level holding an intrusive FIFO queue of orders at that price. Time priority
 * is preserved via append-to-tail on insert and consume-from-head on match.
 *
 * <p>Package-private — external callers work through {@link OrderBook}. Instances are
 * reusable via {@link #reset} so the book can recycle them when a level empties, avoiding
 * allocation on the hot path.
 */
final class PriceLevel {

    long price;
    long totalDisplayQuantity;
    int orderCount;
    Order head;
    Order tail;

    /** Reset this level to hold a new price with no orders. */
    void reset(long price) {
        this.price = price;
        this.totalDisplayQuantity = 0L;
        this.orderCount = 0;
        this.head = null;
        this.tail = null;
    }

    boolean isEmpty() {
        return head == null;
    }

    /** Append {@code order} to the tail (new arrivals lose priority to existing orders). */
    void append(Order order) {
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        orderCount++;
        totalDisplayQuantity += order.displayQuantity;
    }

    /**
     * Unlink {@code order} from this level. Caller must ensure the order was actually appended
     * here. The order's {@code displayQuantity} is used to adjust the running total — so if
     * the order's display has been decremented via {@link #decrementDisplay(Order, long)}
     * the book total stays consistent.
     */
    void remove(Order order) {
        if (order.prev != null) {
            order.prev.next = order.next;
        } else {
            head = order.next;
        }
        if (order.next != null) {
            order.next.prev = order.prev;
        } else {
            tail = order.prev;
        }
        order.prev = null;
        order.next = null;
        orderCount--;
        totalDisplayQuantity -= order.displayQuantity;
    }

    /**
     * Reduce the display quantity of {@code order} by {@code amount} and keep the level's
     * running total in sync. Used when a match partially consumes a resting order.
     */
    void decrementDisplay(Order order, long amount) {
        order.displayQuantity -= amount;
        totalDisplayQuantity -= amount;
    }

    /**
     * Increase the display quantity of {@code order} by {@code amount}. Used by iceberg
     * refills.
     */
    void incrementDisplay(Order order, long amount) {
        order.displayQuantity += amount;
        totalDisplayQuantity += amount;
    }
}
