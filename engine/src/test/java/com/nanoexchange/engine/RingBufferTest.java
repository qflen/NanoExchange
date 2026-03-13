package com.nanoexchange.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class RingBufferTest {

    @Test
    void capacityIsRoundedUpToPowerOfTwo() {
        assertThat(new RingBuffer<Object>(2).capacity()).isEqualTo(2);
        assertThat(new RingBuffer<Object>(3).capacity()).isEqualTo(4);
        assertThat(new RingBuffer<Object>(5).capacity()).isEqualTo(8);
        assertThat(new RingBuffer<Object>(1024).capacity()).isEqualTo(1024);
        assertThat(new RingBuffer<Object>(1025).capacity()).isEqualTo(2048);
    }

    @Test
    void constructorRejectsSmallCapacity() {
        assertThatThrownBy(() -> new RingBuffer<Object>(1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RingBuffer<Object>(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullItemRejected() {
        RingBuffer<Object> r = new RingBuffer<>(4);
        assertThatThrownBy(() -> r.publish(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.tryPublish(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void tryPublishReturnsFalseWhenFull() {
        RingBuffer<Integer> r = new RingBuffer<>(4);
        assertThat(r.tryPublish(1)).isTrue();
        assertThat(r.tryPublish(2)).isTrue();
        assertThat(r.tryPublish(3)).isTrue();
        assertThat(r.tryPublish(4)).isTrue();
        assertThat(r.tryPublish(5)).isFalse();
        assertThat(r.size()).isEqualTo(4);
    }

    @Test
    void pollReturnsNullWhenEmpty() {
        RingBuffer<Integer> r = new RingBuffer<>(4);
        assertThat(r.poll()).isNull();
    }

    @Test
    void fifoOrderIsPreserved() {
        RingBuffer<Integer> r = new RingBuffer<>(8);
        for (int i = 0; i < 8; i++) r.publish(i);
        for (int i = 0; i < 8; i++) assertThat(r.poll()).isEqualTo(i);
        assertThat(r.poll()).isNull();
    }

    @Test
    void sizeTracksPendingItems() {
        RingBuffer<Integer> r = new RingBuffer<>(4);
        assertThat(r.size()).isEqualTo(0);
        r.publish(1);
        r.publish(2);
        assertThat(r.size()).isEqualTo(2);
        r.poll();
        assertThat(r.size()).isEqualTo(1);
    }

    @Test
    void wrapsAroundManyTimes() {
        RingBuffer<Integer> r = new RingBuffer<>(4);
        for (int i = 0; i < 10_000; i++) {
            assertThat(r.tryPublish(i)).isTrue();
            assertThat(r.poll()).isEqualTo(i);
        }
    }

    /**
     * Stress: one producer publishes 1_000_000 monotonic ints, one consumer drains. Producer
     * spins on full, consumer spins on empty. Both sides must see strictly monotonic sequence
     * with no duplicates, no gaps, no reordering.
     */
    @Test
    void spscStressOneMillionMessages() throws Exception {
        final int N = 1_000_000;
        RingBuffer<Integer> r = new RingBuffer<>(1024);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                for (int expected = 0; expected < N; expected++) {
                    Integer got = r.take();
                    if (got == null || got.intValue() != expected) {
                        failure.set("expected " + expected + " got " + got);
                        return;
                    }
                }
            } catch (Throwable t) {
                failure.set(t.toString());
            } finally {
                done.countDown();
            }
        }, "rb-consumer");
        consumer.start();

        for (int i = 0; i < N; i++) {
            r.publish(i);
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(r.size()).isEqualTo(0);
    }
}
