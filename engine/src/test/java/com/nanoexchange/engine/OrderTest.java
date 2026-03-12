package com.nanoexchange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void resetPopulatesAllFieldsAndClearsLinks() {
        Order o = new Order();
        o.prev = new Order();
        o.next = new Order();
        o.displayQuantity = 99L;
        o.hiddenQuantity = 55L;
        o.displaySize = 33L;

        o.reset(42L, 7L, Order.SIDE_BUY, Order.TYPE_LIMIT, 100_00000000L, 10L, 123L);

        assertThat(o.orderId()).isEqualTo(42L);
        assertThat(o.clientId()).isEqualTo(7L);
        assertThat(o.side()).isEqualTo(Order.SIDE_BUY);
        assertThat(o.orderType()).isEqualTo(Order.TYPE_LIMIT);
        assertThat(o.price()).isEqualTo(100_00000000L);
        assertThat(o.quantity()).isEqualTo(10L);
        assertThat(o.displayQuantity()).isEqualTo(10L);
        assertThat(o.hiddenQuantity()).isZero();
        assertThat(o.displaySize()).isZero();
        assertThat(o.timestampNanos()).isEqualTo(123L);
        assertThat(o.prev).isNull();
        assertThat(o.next).isNull();
    }

    @Test
    void resetIcebergOrderLeavesDisplayQuantityZeroUntilConfigure() {
        Order o = new Order();
        o.reset(1L, 1L, Order.SIDE_BUY, Order.TYPE_ICEBERG, 100L, 50L, 0L);
        assertThat(o.displayQuantity()).isZero();
        o.configureIceberg(10L);
        assertThat(o.displayQuantity()).isEqualTo(10L);
        assertThat(o.hiddenQuantity()).isEqualTo(40L);
        assertThat(o.displaySize()).isEqualTo(10L);
    }

    @Test
    void configureIcebergRequiresIcebergType() {
        Order o = new Order();
        o.reset(1L, 1L, Order.SIDE_BUY, Order.TYPE_LIMIT, 100L, 50L, 0L);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> o.configureIceberg(10L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearZeroesAllFields() {
        Order o = new Order();
        o.reset(1L, 1L, Order.SIDE_SELL, Order.TYPE_IOC, 50L, 5L, 999L);
        o.next = new Order();

        o.clear();

        assertThat(o.orderId()).isZero();
        assertThat(o.clientId()).isZero();
        assertThat(o.side()).isZero();
        assertThat(o.orderType()).isZero();
        assertThat(o.price()).isZero();
        assertThat(o.quantity()).isZero();
        assertThat(o.displayQuantity()).isZero();
        assertThat(o.timestampNanos()).isZero();
        assertThat(o.prev).isNull();
        assertThat(o.next).isNull();
    }

    @Test
    void toStringIncludesIdAndSideLabel() {
        Order o = new Order();
        o.reset(5L, 1L, Order.SIDE_SELL, Order.TYPE_LIMIT, 100L, 3L, 0L);
        assertThat(o.toString()).contains("id=5").contains("SELL");
    }
}
