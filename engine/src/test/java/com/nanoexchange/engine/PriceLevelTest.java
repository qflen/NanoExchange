package com.nanoexchange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceLevelTest {

    private static Order o(long id, long qty) {
        Order o = new Order();
        o.reset(id, 1L, Order.SIDE_BUY, Order.TYPE_LIMIT, 100L, qty, 0L);
        return o;
    }

    @Test
    void appendMaintainsFifoOrder() {
        PriceLevel level = new PriceLevel();
        level.reset(100L);
        Order a = o(1, 5), b = o(2, 3), c = o(3, 7);
        level.append(a);
        level.append(b);
        level.append(c);
        assertThat(level.head).isSameAs(a);
        assertThat(level.tail).isSameAs(c);
        assertThat(a.next).isSameAs(b);
        assertThat(b.next).isSameAs(c);
        assertThat(c.next).isNull();
        assertThat(level.orderCount).isEqualTo(3);
        assertThat(level.totalDisplayQuantity).isEqualTo(15L);
    }

    @Test
    void removeMiddleAdjustsTotals() {
        PriceLevel level = new PriceLevel();
        level.reset(100L);
        Order a = o(1, 5), b = o(2, 3), c = o(3, 7);
        level.append(a); level.append(b); level.append(c);
        level.remove(b);
        assertThat(level.head).isSameAs(a);
        assertThat(level.tail).isSameAs(c);
        assertThat(a.next).isSameAs(c);
        assertThat(c.prev).isSameAs(a);
        assertThat(level.orderCount).isEqualTo(2);
        assertThat(level.totalDisplayQuantity).isEqualTo(12L);
    }

    @Test
    void removeHeadAdvancesHead() {
        PriceLevel level = new PriceLevel();
        level.reset(100L);
        Order a = o(1, 4), b = o(2, 6);
        level.append(a); level.append(b);
        level.remove(a);
        assertThat(level.head).isSameAs(b);
        assertThat(b.prev).isNull();
        assertThat(level.totalDisplayQuantity).isEqualTo(6L);
    }

    @Test
    void removeTailAdjustsTail() {
        PriceLevel level = new PriceLevel();
        level.reset(100L);
        Order a = o(1, 4), b = o(2, 6);
        level.append(a); level.append(b);
        level.remove(b);
        assertThat(level.tail).isSameAs(a);
        assertThat(a.next).isNull();
        assertThat(level.totalDisplayQuantity).isEqualTo(4L);
    }

    @Test
    void decrementDisplayKeepsTotalConsistent() {
        PriceLevel level = new PriceLevel();
        level.reset(100L);
        Order a = o(1, 10);
        level.append(a);
        level.decrementDisplay(a, 3);
        assertThat(a.displayQuantity).isEqualTo(7L);
        assertThat(level.totalDisplayQuantity).isEqualTo(7L);
    }

    @Test
    void resetClearsState() {
        PriceLevel level = new PriceLevel();
        level.reset(100L);
        level.append(o(1, 10));
        level.reset(0L);
        assertThat(level.price).isZero();
        assertThat(level.head).isNull();
        assertThat(level.tail).isNull();
        assertThat(level.orderCount).isZero();
        assertThat(level.totalDisplayQuantity).isZero();
    }
}
