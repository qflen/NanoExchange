import { describe, it, expect } from "vitest";
import {
  exchangeReducer,
  initialExchangeState,
  selectAskRows,
  selectBestBidAsk,
  selectBidRows,
  TRADE_BUFFER_CAP,
} from "./exchangeReducer";
import type { BatchMsg, BookUpdateMsg, SnapshotMsg, TradeMsg } from "../types";

const bu = (
  side: "BUY" | "SELL",
  price: number,
  quantity: number,
  seq = 1,
  order_count = 1,
): BookUpdateMsg => ({
  type: "book_update",
  seq,
  side,
  price,
  quantity,
  order_count,
});

const batch = (overrides: Partial<BatchMsg> = {}): BatchMsg => ({
  type: "batch",
  book_updates: [],
  trades: [],
  snapshots: [],
  execs: [],
  ...overrides,
});

describe("exchangeReducer", () => {
  it("applies a book update and tracks lastSeq", () => {
    const next = exchangeReducer(
      initialExchangeState,
      { type: "batch", batch: batch({ book_updates: [bu("BUY", 100.0, 5, 7)] }) },
    );
    expect(next.bids.levels[100.0]).toBe(5);
    expect(next.lastSeq).toBe(7);
  });

  it("removes a level when quantity goes to zero", () => {
    let s = exchangeReducer(initialExchangeState, {
      type: "batch",
      batch: batch({ book_updates: [bu("SELL", 101.5, 10, 1)] }),
    });
    expect(s.asks.levels[101.5]).toBe(10);
    s = exchangeReducer(s, {
      type: "batch",
      batch: batch({ book_updates: [bu("SELL", 101.5, 0, 2)] }),
    });
    expect(s.asks.levels[101.5]).toBeUndefined();
  });

  it("snapshot replaces both sides", () => {
    const snap: SnapshotMsg = {
      type: "snapshot",
      seq: 100,
      levels: [
        { side: "BUY", price: 99.5, quantity: 3, order_count: 1 },
        { side: "SELL", price: 100.5, quantity: 4, order_count: 2 },
      ],
    };
    const preload = exchangeReducer(initialExchangeState, {
      type: "batch",
      batch: batch({ book_updates: [bu("BUY", 50.0, 1, 1)] }),
    });
    const s = exchangeReducer(preload, {
      type: "batch",
      batch: batch({ snapshots: [snap] }),
    });
    expect(s.bids.levels[50.0]).toBeUndefined();
    expect(s.bids.levels[99.5]).toBe(3);
    expect(s.asks.levels[100.5]).toBe(4);
  });

  it("selectBidRows sorts descending by price", () => {
    const s = exchangeReducer(initialExchangeState, {
      type: "batch",
      batch: batch({
        book_updates: [bu("BUY", 99.0, 1), bu("BUY", 100.0, 2), bu("BUY", 99.5, 3)],
      }),
    });
    expect(selectBidRows(s.bids).map((r) => r.price)).toEqual([100.0, 99.5, 99.0]);
  });

  it("selectAskRows sorts ascending and best bid/ask picks the right extrema", () => {
    const s = exchangeReducer(initialExchangeState, {
      type: "batch",
      batch: batch({
        book_updates: [
          bu("BUY", 99.0, 1),
          bu("BUY", 100.0, 1),
          bu("SELL", 100.5, 1),
          bu("SELL", 101.0, 1),
        ],
      }),
    });
    expect(selectAskRows(s.asks).map((r) => r.price)).toEqual([100.5, 101.0]);
    const { bestBid, bestAsk, mid } = selectBestBidAsk(s.bids, s.asks);
    expect(bestBid).toBe(100.0);
    expect(bestAsk).toBe(100.5);
    expect(mid).toBe(100.25);
  });

  it("trade buffer caps at TRADE_BUFFER_CAP", () => {
    const mkTrade = (seq: number): TradeMsg => ({
      type: "trade",
      seq,
      taker_order_id: seq,
      maker_order_id: 0,
      taker_side: "BUY",
      price: 100.0,
      quantity: 1,
      ts_ns: seq,
    });
    // Push past the cap in two batches.
    const first: TradeMsg[] = Array.from(
      { length: TRADE_BUFFER_CAP },
      (_, i) => mkTrade(i),
    );
    const second: TradeMsg[] = Array.from({ length: 100 }, (_, i) =>
      mkTrade(TRADE_BUFFER_CAP + i),
    );
    let s = exchangeReducer(initialExchangeState, {
      type: "batch",
      batch: batch({ trades: first }),
    });
    s = exchangeReducer(s, { type: "batch", batch: batch({ trades: second }) });
    expect(s.trades.length).toBe(TRADE_BUFFER_CAP);
    expect(s.trades[0].seq).toBe(100);
    expect(s.trades[s.trades.length - 1].seq).toBe(TRADE_BUFFER_CAP + 99);
  });

  it("carries the degraded flag from the last batch", () => {
    const s = exchangeReducer(initialExchangeState, {
      type: "batch",
      batch: batch({ book_updates: [bu("BUY", 100, 1)], degraded: true }),
    });
    expect(s.degraded).toBe(true);
  });

  it("connection/open and connection/close toggle connected", () => {
    let s = exchangeReducer(initialExchangeState, { type: "connection/open" });
    expect(s.connected).toBe(true);
    s = exchangeReducer(s, { type: "connection/close" });
    expect(s.connected).toBe(false);
  });
});
