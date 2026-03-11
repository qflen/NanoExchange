package com.nanoexchange.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LongHashMapTest {

    @Test
    void putGetRemoveRoundTrip() {
        LongHashMap<String> m = new LongHashMap<>();
        assertThat(m.put(1L, "a")).isNull();
        assertThat(m.put(2L, "b")).isNull();
        assertThat(m.size()).isEqualTo(2);
        assertThat(m.get(1L)).isEqualTo("a");
        assertThat(m.get(2L)).isEqualTo("b");
        assertThat(m.get(3L)).isNull();

        assertThat(m.remove(1L)).isEqualTo("a");
        assertThat(m.size()).isEqualTo(1);
        assertThat(m.get(1L)).isNull();
        assertThat(m.remove(1L)).isNull();
    }

    @Test
    void putReplacesExistingValueAndReturnsPrevious() {
        LongHashMap<String> m = new LongHashMap<>();
        m.put(5L, "first");
        assertThat(m.put(5L, "second")).isEqualTo("first");
        assertThat(m.size()).isEqualTo(1);
        assertThat(m.get(5L)).isEqualTo("second");
    }

    @Test
    void nullValueRejected() {
        LongHashMap<String> m = new LongHashMap<>();
        assertThatThrownBy(() -> m.put(1L, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void tombstoneSlotReusedOnInsert() {
        LongHashMap<String> m = new LongHashMap<>(4);
        m.put(1L, "a");
        m.put(2L, "b");
        int beforeCap = m.capacity();
        m.remove(1L);
        m.put(3L, "c"); // should land in the tombstone or another slot, not grow
        assertThat(m.capacity()).isEqualTo(beforeCap);
        assertThat(m.get(3L)).isEqualTo("c");
        assertThat(m.size()).isEqualTo(2);
    }

    @Test
    void probeChainSurvivesTombstones() {
        LongHashMap<String> m = new LongHashMap<>(8);
        // Force collisions by inserting many keys and then removing an interior one;
        // the get for a later-probed key must still find it.
        for (long k = 1; k <= 10; k++) m.put(k, "v" + k);
        m.remove(5L);
        for (long k = 1; k <= 10; k++) {
            if (k == 5L) assertThat(m.get(k)).isNull();
            else assertThat(m.get(k)).isEqualTo("v" + k);
        }
    }

    @Test
    void resizeGrowsCapacityAndPreservesEntries() {
        LongHashMap<Long> m = new LongHashMap<>(8);
        int startCap = m.capacity();
        for (long k = 1; k <= 1_000; k++) m.put(k, k * 10);
        assertThat(m.capacity()).isGreaterThan(startCap);
        assertThat(m.size()).isEqualTo(1_000);
        for (long k = 1; k <= 1_000; k++) {
            assertThat(m.get(k)).isEqualTo(k * 10);
        }
    }

    @Test
    void reservedKeyTripsAssertion() {
        LongHashMap<String> m = new LongHashMap<>();
        // Assertions must be enabled for JUnit runtime (Gradle's `test` task enables -ea).
        assertThatThrownBy(() -> m.put(0L, "x")).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> m.get(0L)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> m.remove(0L)).isInstanceOf(AssertionError.class);
    }

    @Test
    void clearResetsToEmpty() {
        LongHashMap<String> m = new LongHashMap<>();
        m.put(1L, "a");
        m.put(2L, "b");
        m.clear();
        assertThat(m.size()).isZero();
        assertThat(m.get(1L)).isNull();
        m.put(1L, "fresh");
        assertThat(m.get(1L)).isEqualTo("fresh");
    }

    @Test
    void matchesStdlibHashMapOnRandomOperations() {
        LongHashMap<Long> ours = new LongHashMap<>();
        Map<Long, Long> ref = new HashMap<>();
        Random r = new Random(0xDEADBEEFL);
        for (int i = 0; i < 20_000; i++) {
            long key = r.nextLong();
            if (key == 0L) continue;
            int op = r.nextInt(3);
            switch (op) {
                case 0 -> {
                    long v = r.nextLong();
                    assertThat(ours.put(key, v)).isEqualTo(ref.put(key, v));
                }
                case 1 -> assertThat(ours.get(key)).isEqualTo(ref.get(key));
                case 2 -> assertThat(ours.remove(key)).isEqualTo(ref.remove(key));
                default -> throw new IllegalStateException();
            }
            assertThat(ours.size()).isEqualTo(ref.size());
        }
        for (Map.Entry<Long, Long> e : ref.entrySet()) {
            assertThat(ours.get(e.getKey())).isEqualTo(e.getValue());
        }
    }
}
