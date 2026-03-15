package com.nanoexchange.network.feed;

/**
 * Monotonic sequence generator for the market-data feed. The publisher stamps every outgoing
 * message with {@link #next()} so consumers can detect gaps (dropped UDP packets) and
 * compare against a snapshot's {@code startingSequence} to resynchronize.
 *
 * <p>Not thread-safe — called only from the feed-publishing thread.
 */
public final class FeedSequence {
    private long value = 0L;

    /** Returns the next sequence value (first call returns 1). */
    public long next() {
        return ++value;
    }

    /** Current value without advancing. Used when stamping snapshot `startingSequence` fields. */
    public long current() {
        return value;
    }
}
