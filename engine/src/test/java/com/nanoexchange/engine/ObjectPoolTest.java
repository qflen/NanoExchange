package com.nanoexchange.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ObjectPoolTest {

    @Test
    void capacityIsRoundedUpToPowerOfTwo() {
        ObjectPool<Order> pool = new ObjectPool<>(7, Order::new);
        assertThat(pool.capacity()).isEqualTo(8);
        assertThat(pool.size()).isEqualTo(8);
    }

    @Test
    void acquireThenReleaseRoundTrip() {
        ObjectPool<Order> pool = new ObjectPool<>(4, Order::new);
        Order a = pool.acquire();
        Order b = pool.acquire();
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a).isNotSameAs(b);
        assertThat(pool.size()).isEqualTo(2);

        assertThat(pool.release(a)).isTrue();
        assertThat(pool.release(b)).isTrue();
        assertThat(pool.size()).isEqualTo(4);
    }

    @Test
    void acquireOnEmptyReturnsNull() {
        ObjectPool<Order> pool = new ObjectPool<>(2, Order::new);
        assertThat(pool.acquire()).isNotNull();
        assertThat(pool.acquire()).isNotNull();
        assertThat(pool.acquire()).isNull();
    }

    @Test
    void releaseOnFullReturnsFalse() {
        ObjectPool<Order> pool = new ObjectPool<>(2, Order::new);
        Order extra = new Order();
        assertThat(pool.release(extra)).isFalse();
    }

    @Test
    void releaseNullThrows() {
        ObjectPool<Order> pool = new ObjectPool<>(2, Order::new);
        assertThatThrownBy(() -> pool.release(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroCapacityRejected() {
        assertThatThrownBy(() -> new ObjectPool<>(0, Order::new))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentProducersAndConsumersLoseNothing() throws InterruptedException {
        final int producers = 4;
        final int consumers = 4;
        final int perProducer = 5_000;
        final int capacity = 1 << 12;

        // Pool is pre-filled. Drain it first so the test exercises real producer/consumer
        // hand-offs rather than the initial population.
        ObjectPool<long[]> pool = new ObjectPool<>(capacity, () -> new long[] { 0L });
        for (int i = 0; i < capacity; i++) pool.acquire();

        AtomicLong nextId = new AtomicLong(1);
        AtomicInteger producedOk = new AtomicInteger();
        AtomicLong consumedSum = new AtomicLong();
        AtomicInteger consumedCount = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers + consumers);

        final int totalItems = producers * perProducer;

        for (int p = 0; p < producers; p++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perProducer; i++) {
                        long id = nextId.getAndIncrement();
                        long[] box = new long[] { id };
                        while (!pool.release(box)) {
                            Thread.onSpinWait();
                        }
                        producedOk.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "producer-" + p).start();
        }

        for (int c = 0; c < consumers; c++) {
            new Thread(() -> {
                try {
                    start.await();
                    while (consumedCount.get() < totalItems) {
                        long[] box = pool.acquire();
                        if (box == null) {
                            Thread.onSpinWait();
                            continue;
                        }
                        consumedSum.addAndGet(box[0]);
                        consumedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "consumer-" + c).start();
        }

        start.countDown();
        done.await();

        long expectedSum = (long) totalItems * (totalItems + 1) / 2;
        assertThat(producedOk.get()).isEqualTo(totalItems);
        assertThat(consumedCount.get()).isEqualTo(totalItems);
        assertThat(consumedSum.get()).isEqualTo(expectedSum);
    }

    @Test
    void returnedItemsAreNotDuplicated() {
        ObjectPool<Order> pool = new ObjectPool<>(4, Order::new);
        Set<Order> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            Order o = pool.acquire();
            assertThat(seen.add(o)).as("unique instance").isTrue();
        }
        for (Order o : seen) {
            assertThat(pool.release(o)).isTrue();
        }
        Set<Order> seen2 = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            Order o = pool.acquire();
            assertThat(seen2.add(o)).isTrue();
        }
        assertThat(seen2).containsExactlyInAnyOrderElementsOf(seen);
    }
}
