package com.nanoexchange.engine;

/**
 * One side of the book (bids or asks) as a best-first sorted array of {@link PriceLevel}.
 * Index 0 is the best price: highest for bids, lowest for asks. Matching walks from index 0
 * upward; the moment the crossing check fails at a level, nothing deeper in the array can
 * match either.
 *
 * <p>Why a sorted array and not a red-black tree? In a healthy book the active-level count
 * near the BBO is small (typically &lt; 100) and matching almost always consumes the first
 * one or two entries. Linear scan from index 0 is cache-friendly and branch-predictable in a
 * way that pointer chasing through a tree is not. Insertion and removal shift elements, which
 * is O(N) worst case but in practice cheap because N is small and the affected memory is hot.
 * The tradeoff is revisited in stage 7 with benchmarks.
 *
 * <p>Package-private. Callers must be {@link OrderBook}.
 */
final class BookSide {

    /** {@code true} for the bid side (descending price sort); {@code false} for asks (ascending). */
    private final boolean bidSide;
    private final ObjectPool<PriceLevel> levelPool;

    private PriceLevel[] levels;
    private int size;

    BookSide(boolean bidSide, int initialCapacity, ObjectPool<PriceLevel> levelPool) {
        this.bidSide = bidSide;
        this.levelPool = levelPool;
        this.levels = new PriceLevel[Math.max(8, initialCapacity)];
        this.size = 0;
    }

    int levelCount() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** @return the best-priced level, or {@code null} if this side is empty. */
    PriceLevel bestLevel() {
        return size == 0 ? null : levels[0];
    }

    /** @return the level at rank {@code depth} from the best, or {@code null} if beyond. */
    PriceLevel levelAtDepth(int depth) {
        return depth < 0 || depth >= size ? null : levels[depth];
    }

    /**
     * Find an existing level with exactly {@code price}, or insert a new one at the correct
     * sorted position. The returned level is ready to append orders to.
     */
    PriceLevel findOrCreate(long price) {
        int idx = search(price);
        if (idx >= 0) {
            return levels[idx];
        }
        int insertAt = -(idx + 1);
        PriceLevel level = acquireLevel(price);
        ensureCapacity(size + 1);
        System.arraycopy(levels, insertAt, levels, insertAt + 1, size - insertAt);
        levels[insertAt] = level;
        size++;
        return level;
    }

    /**
     * Locate an existing level for {@code price}, or {@code null} if none.
     */
    PriceLevel find(long price) {
        int idx = search(price);
        return idx < 0 ? null : levels[idx];
    }

    /**
     * Remove the level at rank {@code index} from the best side, returning it to the pool.
     * The level must already be empty.
     */
    void removeLevelAt(int index) {
        PriceLevel level = levels[index];
        if (!level.isEmpty()) {
            throw new IllegalStateException("cannot remove non-empty level");
        }
        int tail = size - 1;
        if (index < tail) {
            System.arraycopy(levels, index + 1, levels, index, tail - index);
        }
        levels[tail] = null;
        size--;
        level.reset(0L);
        if (!levelPool.release(level)) {
            // pool full; let GC handle it — the pool is the fast path, not a leak detector.
        }
    }

    /** Remove {@code level} regardless of position. Linear scan; callers should prefer the
     *  indexed form when they already know where the level lives. */
    void removeLevel(PriceLevel level) {
        for (int i = 0; i < size; i++) {
            if (levels[i] == level) {
                removeLevelAt(i);
                return;
            }
        }
        throw new IllegalStateException("level not found");
    }

    /**
     * Binary-search for {@code price}. Returns the insertion index encoded as
     * {@code -(insertPoint + 1)} when absent, or the nonnegative index when present.
     */
    private int search(long price) {
        int lo = 0, hi = size - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long midPrice = levels[mid].price;
            int cmp = compare(price, midPrice);
            if (cmp == 0) return mid;
            if (cmp < 0) hi = mid - 1;
            else lo = mid + 1;
        }
        return -(lo + 1);
    }

    /** Best-first comparison: for bids, higher price is "less" (sorts earlier). */
    private int compare(long a, long b) {
        if (a == b) return 0;
        if (bidSide) {
            return a > b ? -1 : 1;
        } else {
            return a < b ? -1 : 1;
        }
    }

    private PriceLevel acquireLevel(long price) {
        PriceLevel level = levelPool.acquire();
        if (level == null) {
            // Pool exhausted — a rare event that signals a deep book or misconfiguration.
            // Allocate rather than reject; a one-off allocation is preferable to rejecting a
            // new price level. This case is logged by the engine in stage 3.
            level = new PriceLevel();
        }
        level.reset(price);
        return level;
    }

    private void ensureCapacity(int needed) {
        if (needed <= levels.length) return;
        int newCap = levels.length << 1;
        while (newCap < needed) newCap <<= 1;
        PriceLevel[] grown = new PriceLevel[newCap];
        System.arraycopy(levels, 0, grown, 0, size);
        this.levels = grown;
    }
}
