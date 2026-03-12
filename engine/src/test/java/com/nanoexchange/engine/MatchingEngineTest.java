package com.nanoexchange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Scenario coverage for the matching engine. The fixture captures every emitted
 * {@link ExecutionReport} into an immutable snapshot so assertions can be made after the fact
 * without relying on the pooled report reference staying valid.
 */
class MatchingEngineTest {

    private OrderBook book;
    private MatchingEngine engine;
    private CapturingSink sink;
    private long nextOrderId;

    @BeforeEach
    void setUp() {
        book = new OrderBook(32, 128);
        engine = new MatchingEngine(book, 256);
        sink = new CapturingSink();
        nextOrderId = 1;
    }

    // -----------------------------------------------------------------------------------------
    // basic matching
    // -----------------------------------------------------------------------------------------

    @Test
    void limitVsLimitExactCross() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 10L, 1L), sink);
        sink.clear();

        engine.submit(limit(Order.SIDE_BUY, 100L, 10L, 2L), sink);

        List<Report> reports = sink.snapshot();
        assertThat(reports).hasSize(3);
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_ACK);
        assertThat(reports.get(1).type).isEqualTo(ExecutionReport.TYPE_FILL); // maker
        assertThat(reports.get(1).executedQuantity).isEqualTo(10L);
        assertThat(reports.get(2).type).isEqualTo(ExecutionReport.TYPE_FILL); // taker
        assertThat(reports.get(2).executedQuantity).isEqualTo(10L);

        assertThat(book.bidLevelCount()).isZero();
        assertThat(book.askLevelCount()).isZero();
    }

    @Test
    void limitCrossesMultiplePriceLevels() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 5L, 1L), sink);
        engine.submit(limit(Order.SIDE_SELL, 101L, 5L, 1L), sink);
        engine.submit(limit(Order.SIDE_SELL, 102L, 5L, 1L), sink);
        sink.clear();

        engine.submit(limit(Order.SIDE_BUY, 101L, 8L, 2L), sink);

        long totalExecuted = sink.snapshot().stream()
                .filter(r -> r.clientId == 2L && (r.type == ExecutionReport.TYPE_PARTIAL_FILL
                        || r.type == ExecutionReport.TYPE_FILL))
                .mapToLong(r -> r.executedQuantity)
                .sum();
        assertThat(totalExecuted).isEqualTo(8L);

        // should have consumed the 100 level fully, 3 out of 5 of the 101 level
        assertThat(book.bestAskPrice()).isEqualTo(101L);
        assertThat(book.bestAskQuantity()).isEqualTo(2L);
    }

    @Test
    void partialFillLeavesResidualResting() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 5L, 1L), sink);
        sink.clear();

        engine.submit(limit(Order.SIDE_BUY, 100L, 12L, 2L), sink);

        // 5 filled, 7 rests as new best bid
        assertThat(book.bestBidPrice()).isEqualTo(100L);
        assertThat(book.bestBidQuantity()).isEqualTo(7L);
        assertThat(book.askLevelCount()).isZero();
    }

    @Test
    void restingOrderPreservesFifoTimePriority() {
        Order first = limit(Order.SIDE_SELL, 100L, 5L, 1L);
        Order second = limit(Order.SIDE_SELL, 100L, 5L, 2L);
        engine.submit(first, sink);
        engine.submit(second, sink);
        sink.clear();

        // Buy 5 → should take 'first' (earlier), not 'second'
        engine.submit(limit(Order.SIDE_BUY, 100L, 5L, 3L), sink);

        // 'first' is fully filled and gone; 'second' remains
        assertThat(book.lookup(first.orderId)).isNull();
        assertThat(book.lookup(second.orderId)).isNotNull();
        assertThat(book.bestAskQuantity()).isEqualTo(5L);
    }

    // -----------------------------------------------------------------------------------------
    // IOC / MARKET
    // -----------------------------------------------------------------------------------------

    @Test
    void iocPartialFillThenCancel() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 3L, 1L), sink);
        sink.clear();

        Order ioc = new Order();
        ioc.reset(nextOrderId++, 2L, Order.SIDE_BUY, Order.TYPE_IOC, 100L, 10L, 0L);
        engine.submit(ioc, sink);

        List<Report> reports = sink.snapshot();
        Report canceled = reports.stream()
                .filter(r -> r.type == ExecutionReport.TYPE_CANCELED)
                .findFirst()
                .orElseThrow();
        assertThat(canceled.remainingQuantity).isEqualTo(7L);
        assertThat(book.bidLevelCount()).isZero();
        assertThat(book.askLevelCount()).isZero();
    }

    @Test
    void marketOrderSweepsUntilExhaustion() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 5L, 1L), sink);
        engine.submit(limit(Order.SIDE_SELL, 101L, 5L, 1L), sink);
        sink.clear();

        Order market = new Order();
        market.reset(nextOrderId++, 2L, Order.SIDE_BUY, Order.TYPE_MARKET, 0L, 8L, 0L);
        engine.submit(market, sink);

        assertThat(book.bestAskPrice()).isEqualTo(101L);
        assertThat(book.bestAskQuantity()).isEqualTo(2L);
    }

    @Test
    void marketOrderWithNoLiquidityCancels() {
        Order market = new Order();
        market.reset(nextOrderId++, 1L, Order.SIDE_BUY, Order.TYPE_MARKET, 0L, 5L, 0L);
        engine.submit(market, sink);

        List<Report> reports = sink.snapshot();
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_ACK);
        assertThat(reports.get(1).type).isEqualTo(ExecutionReport.TYPE_CANCELED);
        assertThat(reports.get(1).remainingQuantity).isEqualTo(5L);
    }

    // -----------------------------------------------------------------------------------------
    // FOK
    // -----------------------------------------------------------------------------------------

    @Test
    void fokFailsWhenBookCannotSatisfyFullQuantity() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 3L, 1L), sink);
        sink.clear();

        Order fok = new Order();
        fok.reset(nextOrderId++, 2L, Order.SIDE_BUY, Order.TYPE_FOK, 100L, 10L, 0L);
        engine.submit(fok, sink);

        List<Report> reports = sink.snapshot();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_REJECTED);
        assertThat(reports.get(0).rejectReason).isEqualTo(ExecutionReport.REJECT_FOK_UNFILLABLE);
        assertThat(book.bestAskQuantity()).isEqualTo(3L); // untouched
    }

    @Test
    void fokSucceedsWhenFullyMatchable() {
        engine.submit(limit(Order.SIDE_SELL, 100L, 4L, 1L), sink);
        engine.submit(limit(Order.SIDE_SELL, 101L, 6L, 1L), sink);
        sink.clear();

        Order fok = new Order();
        fok.reset(nextOrderId++, 2L, Order.SIDE_BUY, Order.TYPE_FOK, 101L, 10L, 0L);
        engine.submit(fok, sink);

        long executed = sink.snapshot().stream()
                .filter(r -> r.clientId == 2L
                        && (r.type == ExecutionReport.TYPE_FILL
                        || r.type == ExecutionReport.TYPE_PARTIAL_FILL))
                .mapToLong(r -> r.executedQuantity)
                .sum();
        assertThat(executed).isEqualTo(10L);
        assertThat(book.askLevelCount()).isZero();
    }

    // -----------------------------------------------------------------------------------------
    // Iceberg
    // -----------------------------------------------------------------------------------------

    @Test
    void icebergOnlyShowsDisplayQuantityInBook() {
        Order iceberg = new Order();
        iceberg.reset(nextOrderId++, 1L, Order.SIDE_SELL, Order.TYPE_ICEBERG, 100L, 50L, 0L);
        iceberg.configureIceberg(10L);
        engine.submit(iceberg, sink);

        assertThat(book.bestAskQuantity()).isEqualTo(10L);
    }

    @Test
    void icebergRefillsFromHiddenAndMovesToTail() {
        Order iceberg = new Order();
        iceberg.reset(nextOrderId++, 1L, Order.SIDE_SELL, Order.TYPE_ICEBERG, 100L, 30L, 0L);
        iceberg.configureIceberg(10L);
        engine.submit(iceberg, sink);

        // Another resting at same price, added AFTER the iceberg so it's behind in FIFO.
        Order behind = limit(Order.SIDE_SELL, 100L, 5L, 99L);
        engine.submit(behind, sink);
        sink.clear();

        // Buy 10 — should take the iceberg's display first (it was earlier in queue).
        engine.submit(limit(Order.SIDE_BUY, 100L, 10L, 2L), sink);

        // Iceberg fully consumed its display tip; refill 10 from hidden (20 left hidden).
        // After refill it goes to the TAIL — so 'behind' is now at the head.
        assertThat(book.lookup(iceberg.orderId)).isNotNull();
        assertThat(iceberg.displayQuantity).isEqualTo(10L);
        assertThat(iceberg.hiddenQuantity).isEqualTo(10L);
        assertThat(iceberg.quantity).isEqualTo(20L);

        // Level total: 5 (behind) + 10 (iceberg refilled) = 15
        assertThat(book.bestAskQuantity()).isEqualTo(15L);

        // Confirm order in FIFO: next buy for 5 should consume 'behind' first.
        sink.clear();
        engine.submit(limit(Order.SIDE_BUY, 100L, 5L, 2L), sink);
        assertThat(book.lookup(behind.orderId)).isNull();
        assertThat(book.lookup(iceberg.orderId)).isNotNull();
    }

    @Test
    void icebergFinalRefillLessThanDisplaySize() {
        Order iceberg = new Order();
        iceberg.reset(nextOrderId++, 1L, Order.SIDE_SELL, Order.TYPE_ICEBERG, 100L, 15L, 0L);
        iceberg.configureIceberg(10L);
        engine.submit(iceberg, sink);
        sink.clear();

        // Sweep: buy 10 — consumes tip exactly, triggers refill with only 5 left hidden.
        engine.submit(limit(Order.SIDE_BUY, 100L, 10L, 2L), sink);
        assertThat(iceberg.displayQuantity).isEqualTo(5L);
        assertThat(iceberg.hiddenQuantity).isZero();
        assertThat(iceberg.quantity).isEqualTo(5L);
        assertThat(book.bestAskQuantity()).isEqualTo(5L);
    }

    // -----------------------------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------------------------

    @Test
    void cancelRestingOrder() {
        Order o = limit(Order.SIDE_BUY, 100L, 10L, 1L);
        engine.submit(o, sink);
        sink.clear();

        engine.cancel(o.orderId, 999L, sink);

        List<Report> reports = sink.snapshot();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_CANCELED);
        assertThat(reports.get(0).remainingQuantity).isEqualTo(10L);
        assertThat(book.bidLevelCount()).isZero();
    }

    @Test
    void cancelNonexistentOrderRejectsWithoutCrashing() {
        engine.cancel(12345L, 0L, sink);
        List<Report> reports = sink.snapshot();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_REJECTED);
        assertThat(reports.get(0).rejectReason).isEqualTo(ExecutionReport.REJECT_UNKNOWN_ORDER);
    }

    // -----------------------------------------------------------------------------------------
    // Modify
    // -----------------------------------------------------------------------------------------

    @Test
    void modifyPriceTriggersImmediateMatch() {
        engine.submit(limit(Order.SIDE_SELL, 101L, 10L, 1L), sink);
        Order resting = limit(Order.SIDE_BUY, 99L, 10L, 2L);
        engine.submit(resting, sink);
        sink.clear();

        // Lift the bid to 101 — crosses the ask, should match.
        engine.modify(resting.orderId, 101L, 10L, 123L, sink);

        assertThat(book.bidLevelCount()).isZero();
        assertThat(book.askLevelCount()).isZero();
    }

    @Test
    void modifyDecreaseQuantitySamePricePreservesPriority() {
        // First to arrive at price 100 — should stay at head.
        Order first = limit(Order.SIDE_BUY, 100L, 10L, 1L);
        Order second = limit(Order.SIDE_BUY, 100L, 10L, 2L);
        engine.submit(first, sink);
        engine.submit(second, sink);
        sink.clear();

        engine.modify(first.orderId, 100L, 4L, 123L, sink);
        assertThat(first.quantity).isEqualTo(4L);

        // A matching sell of 4 should hit 'first' (priority preserved), not 'second'.
        engine.submit(limit(Order.SIDE_SELL, 100L, 4L, 3L), sink);
        assertThat(book.lookup(first.orderId)).isNull();
        assertThat(book.lookup(second.orderId)).isNotNull();
    }

    @Test
    void modifyIncreaseQuantityLosesPriority() {
        Order first = limit(Order.SIDE_BUY, 100L, 5L, 1L);
        Order second = limit(Order.SIDE_BUY, 100L, 5L, 2L);
        engine.submit(first, sink);
        engine.submit(second, sink);
        sink.clear();

        engine.modify(first.orderId, 100L, 20L, 123L, sink);

        // Now 'second' is at the head; a sell of 5 should consume 'second'.
        engine.submit(limit(Order.SIDE_SELL, 100L, 5L, 3L), sink);
        assertThat(book.lookup(second.orderId)).isNull();
        assertThat(book.lookup(first.orderId)).isNotNull();
        assertThat(first.quantity).isEqualTo(20L);
    }

    @Test
    void modifyUnknownOrderRejects() {
        engine.modify(99999L, 100L, 5L, 0L, sink);
        List<Report> reports = sink.snapshot();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_REJECTED);
        assertThat(reports.get(0).rejectReason).isEqualTo(ExecutionReport.REJECT_MODIFY_UNKNOWN);
    }

    // -----------------------------------------------------------------------------------------
    // Self-trade prevention
    // -----------------------------------------------------------------------------------------

    @Test
    void selfTradeRejectsTakerWithoutTouchingBook() {
        // Same client has a resting bid; the client submits a crossing sell.
        engine.submit(limit(Order.SIDE_BUY, 100L, 5L, 7L), sink);
        sink.clear();

        Order crossing = new Order();
        crossing.reset(nextOrderId++, 7L, Order.SIDE_SELL, Order.TYPE_LIMIT, 100L, 5L, 0L);
        engine.submit(crossing, sink);

        List<Report> reports = sink.snapshot();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).type).isEqualTo(ExecutionReport.TYPE_REJECTED);
        assertThat(reports.get(0).rejectReason).isEqualTo(ExecutionReport.REJECT_SELF_TRADE);
        // Book unchanged.
        assertThat(book.bestBidQuantity()).isEqualTo(5L);
    }

    @Test
    void nonCrossingSelfSideIsNotSelfTrade() {
        // Same client can have bids and asks as long as they don't cross.
        engine.submit(limit(Order.SIDE_BUY, 99L, 5L, 7L), sink);
        engine.submit(limit(Order.SIDE_SELL, 101L, 5L, 7L), sink);

        // no rejects from the second submit
        assertThat(sink.snapshot().stream()
                .noneMatch(r -> r.type == ExecutionReport.TYPE_REJECTED))
                .isTrue();
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private Order limit(byte side, long price, long qty, long clientId) {
        Order o = new Order();
        o.reset(nextOrderId++, clientId, side, Order.TYPE_LIMIT, price, qty, 0L);
        return o;
    }

    private record Report(
            byte type, byte rejectReason, long orderId, long clientId, byte side,
            long price, long executedQuantity, long executedPrice, long remainingQuantity,
            long counterpartyOrderId, long timestampNanos) {
    }

    private static final class CapturingSink implements ExecutionSink {
        private final List<Report> captured = new ArrayList<>();

        @Override
        public void onExecutionReport(ExecutionReport r) {
            captured.add(new Report(
                    r.reportType, r.rejectReason, r.orderId, r.clientId, r.side,
                    r.price, r.executedQuantity, r.executedPrice, r.remainingQuantity,
                    r.counterpartyOrderId, r.timestampNanos));
        }

        List<Report> snapshot() {
            return List.copyOf(captured);
        }

        void clear() {
            captured.clear();
        }
    }
}
