package com.nanoexchange.engine;

/**
 * The central limit order book. Holds two best-first {@link BookSide} instances (bid and ask)
 * plus a {@link LongHashMap} from order ID to resting {@link Order} for O(1) cancel and
 * modify.
 *
 * <p>All mutation is driven by the matching engine. The book does not enforce matching rules
 * itself — it is a data structure. Insert an order, remove an order, adjust display quantities;
 * matching logic lives one layer up.
 */
public final class OrderBook {

    private final BookSide bids;
    private final BookSide asks;
    private final LongHashMap<Order> orderIndex;

    public OrderBook(int expectedPriceLevels, int expectedLiveOrders) {
        int perSideLevels = Math.max(16, expectedPriceLevels);
        // Pool enough levels for both sides plus headroom — price levels churn fast.
        ObjectPool<PriceLevel> levelPool =
                new ObjectPool<>(perSideLevels * 4, PriceLevel::new);
        this.bids = new BookSide(true, perSideLevels, levelPool);
        this.asks = new BookSide(false, perSideLevels, levelPool);
        this.orderIndex = new LongHashMap<>(Math.max(64, expectedLiveOrders));
    }

    /** @return best bid price, or {@code Long.MIN_VALUE} if bid side is empty. */
    public long bestBidPrice() {
        PriceLevel l = bids.bestLevel();
        return l == null ? Long.MIN_VALUE : l.price;
    }

    /** @return best ask price, or {@code Long.MAX_VALUE} if ask side is empty. */
    public long bestAskPrice() {
        PriceLevel l = asks.bestLevel();
        return l == null ? Long.MAX_VALUE : l.price;
    }

    /** @return total displayed quantity at the best bid, or 0 when empty. */
    public long bestBidQuantity() {
        PriceLevel l = bids.bestLevel();
        return l == null ? 0L : l.totalDisplayQuantity;
    }

    /** @return total displayed quantity at the best ask, or 0 when empty. */
    public long bestAskQuantity() {
        PriceLevel l = asks.bestLevel();
        return l == null ? 0L : l.totalDisplayQuantity;
    }

    public int bidLevelCount() { return bids.levelCount(); }
    public int askLevelCount() { return asks.levelCount(); }

    /** @return total displayed qty at {@code depth} rank (0 = best), 0 if beyond available levels. */
    public long bidQuantityAtDepth(int depth) {
        PriceLevel l = bids.levelAtDepth(depth);
        return l == null ? 0L : l.totalDisplayQuantity;
    }

    public long askQuantityAtDepth(int depth) {
        PriceLevel l = asks.levelAtDepth(depth);
        return l == null ? 0L : l.totalDisplayQuantity;
    }

    /** @return price at {@code depth} rank on the bid side, or {@code Long.MIN_VALUE}. */
    public long bidPriceAtDepth(int depth) {
        PriceLevel l = bids.levelAtDepth(depth);
        return l == null ? Long.MIN_VALUE : l.price;
    }

    public long askPriceAtDepth(int depth) {
        PriceLevel l = asks.levelAtDepth(depth);
        return l == null ? Long.MAX_VALUE : l.price;
    }

    /** @return the resting order for {@code orderId}, or {@code null} if not present. */
    public Order lookup(long orderId) {
        return orderIndex.get(orderId);
    }

    /**
     * Add a resting order to its appropriate side and price level. The caller is responsible
     * for ensuring the order truly rests — market/IOC/FOK orders should not be added.
     */
    public void addResting(Order order) {
        BookSide side = order.side == Order.SIDE_BUY ? bids : asks;
        PriceLevel level = side.findOrCreate(order.price);
        level.append(order);
        orderIndex.put(order.orderId, order);
    }

    /**
     * Remove a resting order. Empty price levels are reclaimed. Returns {@code null} when the
     * order was not in the book (already filled or never added).
     */
    public Order removeResting(long orderId) {
        Order order = orderIndex.remove(orderId);
        if (order == null) return null;
        BookSide side = order.side == Order.SIDE_BUY ? bids : asks;
        PriceLevel level = side.find(order.price);
        if (level == null) {
            throw new IllegalStateException(
                    "order index and book out of sync: no level for " + order);
        }
        level.remove(order);
        if (level.isEmpty()) {
            side.removeLevel(level);
        }
        return order;
    }

    /** Package-private access for the matching engine. */
    BookSide bids() { return bids; }
    BookSide asks() { return asks; }
    LongHashMap<Order> orderIndex() { return orderIndex; }
}
