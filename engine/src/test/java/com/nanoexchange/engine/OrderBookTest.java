package com.nanoexchange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderBookTest {

    private static Order limit(long id, byte side, long price, long qty) {
        Order o = new Order();
        o.reset(id, 1L, side, Order.TYPE_LIMIT, price, qty, 0L);
        return o;
    }

    @Test
    void emptyBookReportsSentinelBbo() {
        OrderBook book = new OrderBook(16, 64);
        assertThat(book.bestBidPrice()).isEqualTo(Long.MIN_VALUE);
        assertThat(book.bestAskPrice()).isEqualTo(Long.MAX_VALUE);
        assertThat(book.bestBidQuantity()).isZero();
        assertThat(book.bestAskQuantity()).isZero();
    }

    @Test
    void bidsSortedHighestFirst() {
        OrderBook book = new OrderBook(16, 64);
        book.addResting(limit(1, Order.SIDE_BUY, 100L, 5));
        book.addResting(limit(2, Order.SIDE_BUY, 102L, 7));
        book.addResting(limit(3, Order.SIDE_BUY, 101L, 3));
        assertThat(book.bestBidPrice()).isEqualTo(102L);
        assertThat(book.bidPriceAtDepth(0)).isEqualTo(102L);
        assertThat(book.bidPriceAtDepth(1)).isEqualTo(101L);
        assertThat(book.bidPriceAtDepth(2)).isEqualTo(100L);
    }

    @Test
    void asksSortedLowestFirst() {
        OrderBook book = new OrderBook(16, 64);
        book.addResting(limit(1, Order.SIDE_SELL, 105L, 5));
        book.addResting(limit(2, Order.SIDE_SELL, 103L, 7));
        book.addResting(limit(3, Order.SIDE_SELL, 104L, 3));
        assertThat(book.bestAskPrice()).isEqualTo(103L);
        assertThat(book.askPriceAtDepth(0)).isEqualTo(103L);
        assertThat(book.askPriceAtDepth(1)).isEqualTo(104L);
        assertThat(book.askPriceAtDepth(2)).isEqualTo(105L);
    }

    @Test
    void multipleOrdersAtSameLevelSumInTotal() {
        OrderBook book = new OrderBook(16, 64);
        book.addResting(limit(1, Order.SIDE_BUY, 100L, 5));
        book.addResting(limit(2, Order.SIDE_BUY, 100L, 3));
        book.addResting(limit(3, Order.SIDE_BUY, 100L, 2));
        assertThat(book.bidLevelCount()).isEqualTo(1);
        assertThat(book.bestBidQuantity()).isEqualTo(10L);
    }

    @Test
    void removeRestingDropsEmptyLevel() {
        OrderBook book = new OrderBook(16, 64);
        book.addResting(limit(1, Order.SIDE_BUY, 100L, 5));
        book.addResting(limit(2, Order.SIDE_BUY, 101L, 3));
        book.removeResting(1);
        assertThat(book.bidLevelCount()).isEqualTo(1);
        assertThat(book.bestBidPrice()).isEqualTo(101L);
    }

    @Test
    void removeRestingPreservesLevelWhenOtherOrdersExist() {
        OrderBook book = new OrderBook(16, 64);
        book.addResting(limit(1, Order.SIDE_BUY, 100L, 5));
        book.addResting(limit(2, Order.SIDE_BUY, 100L, 3));
        book.removeResting(1);
        assertThat(book.bidLevelCount()).isEqualTo(1);
        assertThat(book.bestBidQuantity()).isEqualTo(3L);
    }

    @Test
    void lookupReturnsRestingOrder() {
        OrderBook book = new OrderBook(16, 64);
        Order o = limit(42, Order.SIDE_BUY, 100L, 5);
        book.addResting(o);
        assertThat(book.lookup(42)).isSameAs(o);
        assertThat(book.lookup(43)).isNull();
    }

    @Test
    void removeUnknownReturnsNull() {
        OrderBook book = new OrderBook(16, 64);
        assertThat(book.removeResting(99L)).isNull();
    }
}
