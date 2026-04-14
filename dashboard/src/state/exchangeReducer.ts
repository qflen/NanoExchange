import type {
  BatchMsg,
  BookUpdateMsg,
  ExecReportMsg,
  Side,
  SnapshotMsg,
  TradeMsg,
} from "../types";

// Keep the last N trades in-memory. The trade tape (stage 11) reads
// the tail; the price chart reads the whole buffer. 5_000 keeps a few
// minutes of activity at realistic rates without breaking memo budgets.
export const TRADE_BUFFER_CAP = 5_000;

// Hard cap on the myOrders map. With the simulator pushing 100 k
// orders and ~80 % resting passively, the map would otherwise grow to
// tens of thousands of OPEN entries and take the browser with it when
// the Open Orders list tries to render them as DOM. Once we exceed the
// cap we evict the oldest-inserted entries (insertion order is
// preserved in JS object keys for integer-like keys up to 2^31).
export const MY_ORDERS_CAP = 500;

export interface BookSide {
  // price (float) → quantity. Record wins over Map because React does
  // referential comparison and a new Record is cheap to allocate.
  levels: Record<number, number>;
  // order_count per level, same key space as `levels`.
  orders: Record<number, number>;
}

export interface MyOrder {
  order_id: number;
  client_id: number;
  side: Side;
  price: number;
  remaining: number;
  status: "OPEN" | "PARTIAL" | "FILLED" | "CANCELLED" | "REJECTED";
}

export interface ExchangeState {
  connected: boolean;
  degraded: boolean;
  lastSeq: number;
  bids: BookSide;
  asks: BookSide;
  trades: TradeMsg[];
  execs: ExecReportMsg[];
  myOrders: Record<number, MyOrder>;
}

export const initialExchangeState: ExchangeState = {
  connected: false,
  degraded: false,
  lastSeq: 0,
  bids: { levels: {}, orders: {} },
  asks: { levels: {}, orders: {} },
  trades: [],
  execs: [],
  myOrders: {},
};

export type ExchangeAction =
  | { type: "connection/open" }
  | { type: "connection/close" }
  | { type: "batch"; batch: BatchMsg };

// Apply a whole batch of updates in one pass, cloning each side's
// records exactly once. The prior per-update spread was O(updates ×
// levels) and turned a single 200-update batch on a 10 k-level book
// into ~2 M property copies — the root cause of Firefox stalls when
// the simulator floods the bridge.
function applyBookUpdates(
  bids: BookSide,
  asks: BookSide,
  updates: readonly BookUpdateMsg[],
): { bids: BookSide; asks: BookSide } {
  let bidsDirty = false;
  let asksDirty = false;
  for (const u of updates) {
    if (u.side === "BUY") bidsDirty = true;
    else asksDirty = true;
    if (bidsDirty && asksDirty) break;
  }
  const nextBids = bidsDirty
    ? { levels: { ...bids.levels }, orders: { ...bids.orders } }
    : bids;
  const nextAsks = asksDirty
    ? { levels: { ...asks.levels }, orders: { ...asks.orders } }
    : asks;
  for (const u of updates) {
    const target = u.side === "BUY" ? nextBids : nextAsks;
    if (u.quantity <= 0) {
      delete target.levels[u.price];
      delete target.orders[u.price];
    } else {
      target.levels[u.price] = u.quantity;
      target.orders[u.price] = u.order_count;
    }
  }
  return { bids: nextBids, asks: nextAsks };
}

function applySnapshot(
  msg: SnapshotMsg,
): { bids: BookSide; asks: BookSide } {
  const bids: BookSide = { levels: {}, orders: {} };
  const asks: BookSide = { levels: {}, orders: {} };
  for (const l of msg.levels) {
    const target = l.side === "BUY" ? bids : asks;
    target.levels[l.price] = l.quantity;
    target.orders[l.price] = l.order_count;
  }
  return { bids, asks };
}

// Apply a batch of execs in one pass. Terminal states (FILLED,
// CANCELLED, REJECTED) are dropped from the map immediately — the UI
// only renders OPEN/PARTIAL, and keeping 100 k filled simulator
// orders around made every subsequent batch O(n) per exec on the
// spread. The single clone at the top bounds the per-batch cost to
// O(myOrders + batch) regardless of batch size.
function applyExecs(
  my: Record<number, MyOrder>,
  execs: readonly ExecReportMsg[],
): Record<number, MyOrder> {
  if (execs.length === 0) return my;
  const next = { ...my };
  for (const exec of execs) {
    const existing = next[exec.order_id];
    switch (exec.report_type) {
      case "FILL":
      case "CANCELED":
      case "REJECTED":
        delete next[exec.order_id];
        continue;
      case "PARTIAL_FILL":
        next[exec.order_id] = {
          order_id: exec.order_id,
          client_id: exec.client_id,
          side: exec.side,
          price: existing?.price ?? exec.price,
          remaining: exec.remaining_quantity,
          status: "PARTIAL",
        };
        continue;
      default:
        next[exec.order_id] = {
          order_id: exec.order_id,
          client_id: exec.client_id,
          side: exec.side,
          price: existing?.price ?? exec.price,
          remaining: exec.remaining_quantity,
          status: "OPEN",
        };
    }
  }
  // Enforce the cap. Object.keys preserves insertion order for
  // integer keys up to 2^31, so the oldest orders are at the front
  // and we drop from there. This is an "insurance" path — under
  // normal trading the cap never trips; it only matters when the
  // simulator floods the bridge faster than the user reviews orders.
  const keys = Object.keys(next);
  if (keys.length > MY_ORDERS_CAP) {
    const overflow = keys.length - MY_ORDERS_CAP;
    for (let i = 0; i < overflow; i++) delete next[keys[i] as unknown as number];
  }
  return next;
}

export function exchangeReducer(
  state: ExchangeState,
  action: ExchangeAction,
): ExchangeState {
  switch (action.type) {
    case "connection/open":
      return { ...state, connected: true };
    case "connection/close":
      return { ...state, connected: false };
    case "batch": {
      let bids = state.bids;
      let asks = state.asks;
      let lastSeq = state.lastSeq;

      for (const snap of action.batch.snapshots) {
        const next = applySnapshot(snap);
        bids = next.bids;
        asks = next.asks;
        if (snap.seq > lastSeq) lastSeq = snap.seq;
      }
      if (action.batch.book_updates.length > 0) {
        const next = applyBookUpdates(bids, asks, action.batch.book_updates);
        bids = next.bids;
        asks = next.asks;
        for (const upd of action.batch.book_updates) {
          if (upd.seq > lastSeq) lastSeq = upd.seq;
        }
      }

      // Trade buffer: append, drop oldest past cap. Allocate once.
      // Stamp client arrival time — the engine's ts_ns is a monotonic
      // counter, not wall-clock, so chart bucketing would otherwise
      // scatter across arbitrary "minutes".
      let trades = state.trades;
      if (action.batch.trades.length > 0) {
        const now = Date.now();
        const stamped = action.batch.trades.map((t) =>
          t.rx_ms === undefined ? { ...t, rx_ms: now } : t,
        );
        const combined = state.trades.concat(stamped);
        trades =
          combined.length > TRADE_BUFFER_CAP
            ? combined.slice(combined.length - TRADE_BUFFER_CAP)
            : combined;
      }

      let execs = state.execs;
      let myOrders = state.myOrders;
      if (action.batch.execs.length > 0) {
        execs = state.execs.concat(action.batch.execs).slice(-500);
        myOrders = applyExecs(myOrders, action.batch.execs);
      }

      return {
        ...state,
        degraded: action.batch.degraded === true,
        lastSeq,
        bids,
        asks,
        trades,
        execs,
        myOrders,
      };
    }
  }
}

// --- Selectors -------------------------------------------------------------

export interface LadderRow {
  price: number;
  quantity: number;
  orderCount: number;
}

export function selectBidRows(bids: BookSide): LadderRow[] {
  return Object.keys(bids.levels)
    .map(Number)
    .sort((a, b) => b - a)
    .map((price) => ({
      price,
      quantity: bids.levels[price],
      orderCount: bids.orders[price] ?? 0,
    }));
}

export function selectAskRows(asks: BookSide): LadderRow[] {
  return Object.keys(asks.levels)
    .map(Number)
    .sort((a, b) => a - b)
    .map((price) => ({
      price,
      quantity: asks.levels[price],
      orderCount: asks.orders[price] ?? 0,
    }));
}

export function selectBestBidAsk(
  bids: BookSide,
  asks: BookSide,
): { bestBid: number | null; bestAsk: number | null; mid: number | null } {
  // Explicit loops rather than Math.max/min(...arr): a deep book with
  // tens of thousands of levels can exceed the JS engine's argument
  // length limit on spread (Firefox is stricter than Chrome here) and
  // either throw or silently return the wrong answer.
  let bestBid: number | null = null;
  for (const k in bids.levels) {
    const p = +k;
    if (bestBid === null || p > bestBid) bestBid = p;
  }
  let bestAsk: number | null = null;
  for (const k in asks.levels) {
    const p = +k;
    if (bestAsk === null || p < bestAsk) bestAsk = p;
  }
  const mid =
    bestBid !== null && bestAsk !== null ? (bestBid + bestAsk) / 2 : null;
  return { bestBid, bestAsk, mid };
}
