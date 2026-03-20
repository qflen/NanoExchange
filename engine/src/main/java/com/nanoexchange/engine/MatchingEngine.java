package com.nanoexchange.engine;

/**
 * Price-time priority matching engine. Single-threaded by design — see DECISIONS.md ADR on
 * why real exchanges run a core matcher on one thread and scale out across instruments rather
 * than trying to parallelize within an instrument.
 *
 * <p>Inputs: {@link Order} instances via {@link #submit}, plus admin operations
 * {@link #cancel} and {@link #modify}. Outputs: {@link ExecutionReport} events via a caller-
 * supplied {@link ExecutionSink}. Reports are pooled; the sink must copy any fields it intends
 * to retain past the callback return.
 *
 * <h2>Supported order types</h2>
 * <ul>
 *   <li><b>LIMIT</b> — match what crosses, rest the residual.</li>
 *   <li><b>MARKET</b> — match what crosses at any price, cancel the residual.</li>
 *   <li><b>IOC</b> — like LIMIT but cancel the residual instead of resting.</li>
 *   <li><b>FOK</b> — match fully or not at all; preflight checks viability.</li>
 *   <li><b>ICEBERG</b> — like LIMIT but only the {@code displayQuantity} tip is visible; on
 *       tip depletion the order refills from its hidden reserve and moves to the tail of the
 *       price level (standard real-exchange behavior — the refill loses time priority).</li>
 * </ul>
 *
 * <h2>Self-trade prevention</h2>
 * STP policy is <b>reject-new</b>: if an incoming order would cross any resting order from
 * the same {@code clientId} at any price that crosses, the new order is rejected in full with
 * {@link ExecutionReport#REJECT_SELF_TRADE}. No fills are emitted and no state changes. This
 * is simpler and safer than mid-match skip (which can produce inverted-priority fills) or
 * decrement policies (which silently mutate the existing book). Documented in DECISIONS.md.
 *
 * <h2>Ownership checks</h2>
 * The engine does not verify that cancels and modifies come from the original client. That
 * responsibility lives in the network layer (stage 4); by the time an instruction reaches the
 * engine it has been authorized.
 */
public final class MatchingEngine {

    private final OrderBook book;
    private final ObjectPool<ExecutionReport> reportPool;

    public MatchingEngine(OrderBook book, int reportPoolCapacity) {
        this.book = book;
        this.reportPool = new ObjectPool<>(reportPoolCapacity, ExecutionReport::new);
    }

    /** @return the underlying book (for inspection, typically tests and feed publishers). */
    public OrderBook book() {
        return book;
    }

    // =========================================================================================
    // public API
    // =========================================================================================

    /**
     * Submit a new order. Emits an ACK on acceptance, then zero or more fills, then a
     * terminal report (FILL, CANCELED, or REJECTED) or the order comes to rest silently.
     */
    public void submit(Order order, ExecutionSink sink) {
        if (hasCrossingSelfResting(order)) {
            emitReject(sink, order, ExecutionReport.REJECT_SELF_TRADE);
            return;
        }
        if (order.orderType == Order.TYPE_FOK) {
            if (availableNonSelfDisplayAtOrBetter(order) < order.quantity) {
                emitReject(sink, order, ExecutionReport.REJECT_FOK_UNFILLABLE);
                return;
            }
        }

        emitAck(sink, order);
        match(order, sink);
        handleResidual(order, sink);
    }

    /** Cancel a resting order by ID. Emits CANCELED on success, REJECTED if unknown. */
    public void cancel(long orderId, long timestampNanos, ExecutionSink sink) {
        Order removed = book.removeResting(orderId);
        if (removed == null) {
            ExecutionReport r = acquireReport();
            r.reportType = ExecutionReport.TYPE_REJECTED;
            r.rejectReason = ExecutionReport.REJECT_UNKNOWN_ORDER;
            r.orderId = orderId;
            r.timestampNanos = timestampNanos;
            dispatch(sink, r);
            return;
        }
        ExecutionReport r = acquireReport();
        r.reportType = ExecutionReport.TYPE_CANCELED;
        r.orderId = removed.orderId;
        r.clientId = removed.clientId;
        r.side = removed.side;
        r.price = removed.price;
        r.remainingQuantity = removed.quantity;
        r.timestampNanos = timestampNanos;
        dispatch(sink, r);
    }

    /**
     * Modify (cancel-replace) a resting LIMIT order. Time priority is preserved only when the
     * price is unchanged and the quantity strictly decreases; any other change (price change
     * or quantity increase) loses priority, matching real-exchange behavior. If the new price
     * crosses the opposite side, matching occurs immediately.
     */
    public void modify(long orderId, long newPrice, long newQuantity,
                       long timestampNanos, ExecutionSink sink) {
        Order existing = book.lookup(orderId);
        if (existing == null) {
            ExecutionReport r = acquireReport();
            r.reportType = ExecutionReport.TYPE_REJECTED;
            r.rejectReason = ExecutionReport.REJECT_MODIFY_UNKNOWN;
            r.orderId = orderId;
            r.timestampNanos = timestampNanos;
            dispatch(sink, r);
            return;
        }

        long oldPrice = existing.price;
        long oldQty = existing.quantity;

        if (newPrice == oldPrice && newQuantity == oldQty) {
            emitModified(sink, existing, timestampNanos);
            return;
        }

        if (newPrice == oldPrice && newQuantity < oldQty && existing.orderType == Order.TYPE_LIMIT) {
            // priority-preserving in-place reduction
            BookSide side = existing.side == Order.SIDE_BUY ? book.bids() : book.asks();
            PriceLevel level = side.find(oldPrice);
            long delta = oldQty - newQuantity;
            level.decrementDisplay(existing, delta);
            existing.quantity = newQuantity;
            emitModified(sink, existing, timestampNanos);
            return;
        }

        // Price change or quantity increase: remove, rewrite, re-submit through matching.
        book.removeResting(orderId);
        existing.price = newPrice;
        existing.quantity = newQuantity;
        existing.displayQuantity = newQuantity;
        existing.hiddenQuantity = 0L;
        existing.displaySize = 0L;
        existing.orderType = Order.TYPE_LIMIT;
        existing.timestampNanos = timestampNanos;

        // STP preflight for the modified order too.
        if (hasCrossingSelfResting(existing)) {
            emitReject(sink, existing, ExecutionReport.REJECT_SELF_TRADE);
            return;
        }

        emitModified(sink, existing, timestampNanos);
        match(existing, sink);
        handleResidual(existing, sink);
    }

    // =========================================================================================
    // matching
    // =========================================================================================

    private void match(Order taker, ExecutionSink sink) {
        BookSide opposing = taker.side == Order.SIDE_BUY ? book.asks() : book.bids();
        while (taker.quantity > 0) {
            PriceLevel level = opposing.bestLevel();
            if (level == null) break;
            if (!crosses(taker, level.price)) break;

            Order resting = level.head;
            while (taker.quantity > 0 && resting != null) {
                Order next = resting.next;

                long fillQty = Math.min(taker.quantity, resting.displayQuantity);
                long fillPrice = resting.price;

                // maker
                level.decrementDisplay(resting, fillQty);
                resting.quantity -= fillQty;

                // taker — always adjust display so a resting residual reflects the correct
                // visible quantity (for non-iceberg, display tracks quantity 1:1; for iceberg,
                // the tip is drained separately from the hidden reserve).
                taker.displayQuantity -= fillQty;
                taker.quantity -= fillQty;

                boolean makerFullyDone = (resting.quantity == 0);
                boolean makerRefillNeeded =
                        !makerFullyDone
                        && resting.displayQuantity == 0
                        && resting.hiddenQuantity > 0;

                emitFill(
                        sink, resting,
                        makerFullyDone ? ExecutionReport.TYPE_FILL : ExecutionReport.TYPE_PARTIAL_FILL,
                        fillQty, fillPrice, taker.orderId);
                emitFill(
                        sink, taker,
                        taker.quantity == 0 ? ExecutionReport.TYPE_FILL : ExecutionReport.TYPE_PARTIAL_FILL,
                        fillQty, fillPrice, resting.orderId);

                if (makerFullyDone) {
                    book.removeResting(resting.orderId);
                } else if (makerRefillNeeded) {
                    refillIceberg(resting);
                }

                resting = next;
            }

            // If the level is not empty and we haven't drained the taker, loop to pick up the
            // next head (possibly a refilled iceberg now at the tail). If the level emptied,
            // the outer bestLevel() lookup naturally advances.
            if (level.isEmpty() && taker.quantity > 0) {
                continue;
            }
            if (taker.quantity > 0 && resting == null && !level.isEmpty()) {
                // Ran off the end of the maker FIFO but level isn't empty — this happens when
                // a refilled iceberg re-joined the tail after we captured `next`. Loop to
                // re-enter from the new head.
                continue;
            }
        }
    }

    private void refillIceberg(Order resting) {
        // Pull from book first so the level's internal state is clean, then re-add at tail.
        book.removeResting(resting.orderId);
        long refill = Math.min(resting.displaySize, resting.hiddenQuantity);
        resting.displayQuantity = refill;
        resting.hiddenQuantity -= refill;
        book.addResting(resting);
    }

    private void handleResidual(Order order, ExecutionSink sink) {
        if (order.quantity == 0) {
            return;
        }
        switch (order.orderType) {
            case Order.TYPE_LIMIT, Order.TYPE_ICEBERG -> book.addResting(order);
            case Order.TYPE_MARKET, Order.TYPE_IOC, Order.TYPE_FOK -> {
                ExecutionReport r = acquireReport();
                r.reportType = ExecutionReport.TYPE_CANCELED;
                r.orderId = order.orderId;
                r.clientId = order.clientId;
                r.side = order.side;
                r.price = order.price;
                r.remainingQuantity = order.quantity;
                r.timestampNanos = order.timestampNanos;
                dispatch(sink, r);
            }
            default -> throw new IllegalStateException("unknown order type " + order.orderType);
        }
    }

    private static boolean crosses(Order taker, long restingPrice) {
        if (taker.orderType == Order.TYPE_MARKET) {
            return true;
        }
        if (taker.side == Order.SIDE_BUY) {
            return taker.price >= restingPrice;
        }
        return taker.price <= restingPrice;
    }

    // =========================================================================================
    // STP + FOK preflight
    // =========================================================================================

    private boolean hasCrossingSelfResting(Order incoming) {
        BookSide opposing = incoming.side == Order.SIDE_BUY ? book.asks() : book.bids();
        for (int i = 0; i < opposing.levelCount(); i++) {
            PriceLevel level = opposing.levelAtDepth(i);
            if (!crosses(incoming, level.price)) break;
            for (Order r = level.head; r != null; r = r.next) {
                if (r.clientId == incoming.clientId) return true;
            }
        }
        return false;
    }

    private long availableNonSelfDisplayAtOrBetter(Order incoming) {
        BookSide opposing = incoming.side == Order.SIDE_BUY ? book.asks() : book.bids();
        long total = 0L;
        for (int i = 0; i < opposing.levelCount(); i++) {
            PriceLevel level = opposing.levelAtDepth(i);
            if (!crosses(incoming, level.price)) break;
            for (Order r = level.head; r != null; r = r.next) {
                if (r.clientId != incoming.clientId) {
                    total += r.displayQuantity;
                    if (total >= incoming.quantity) return total;
                }
            }
        }
        return total;
    }

    // =========================================================================================
    // report plumbing
    // =========================================================================================

    private ExecutionReport acquireReport() {
        ExecutionReport r = reportPool.acquire();
        if (r == null) r = new ExecutionReport();
        r.clear();
        return r;
    }

    private void dispatch(ExecutionSink sink, ExecutionReport r) {
        sink.onExecutionReport(r);
        reportPool.release(r);
    }

    private void emitAck(ExecutionSink sink, Order order) {
        ExecutionReport r = acquireReport();
        r.reportType = ExecutionReport.TYPE_ACK;
        r.orderId = order.orderId;
        r.clientId = order.clientId;
        r.side = order.side;
        r.price = order.price;
        r.remainingQuantity = order.quantity;
        r.timestampNanos = order.timestampNanos;
        dispatch(sink, r);
    }

    private void emitFill(ExecutionSink sink, Order order, byte type,
                          long fillQty, long fillPrice, long counterparty) {
        ExecutionReport r = acquireReport();
        r.reportType = type;
        r.orderId = order.orderId;
        r.clientId = order.clientId;
        r.side = order.side;
        r.price = order.price;
        r.executedQuantity = fillQty;
        r.executedPrice = fillPrice;
        r.remainingQuantity = order.quantity;
        r.counterpartyOrderId = counterparty;
        r.timestampNanos = order.timestampNanos;
        dispatch(sink, r);
    }

    private void emitReject(ExecutionSink sink, Order order, byte reason) {
        ExecutionReport r = acquireReport();
        r.reportType = ExecutionReport.TYPE_REJECTED;
        r.rejectReason = reason;
        r.orderId = order.orderId;
        r.clientId = order.clientId;
        r.side = order.side;
        r.price = order.price;
        r.remainingQuantity = order.quantity;
        r.timestampNanos = order.timestampNanos;
        dispatch(sink, r);
    }

    private void emitModified(ExecutionSink sink, Order order, long timestampNanos) {
        ExecutionReport r = acquireReport();
        r.reportType = ExecutionReport.TYPE_MODIFIED;
        r.orderId = order.orderId;
        r.clientId = order.clientId;
        r.side = order.side;
        r.price = order.price;
        r.remainingQuantity = order.quantity;
        r.timestampNanos = timestampNanos;
        dispatch(sink, r);
    }
}
