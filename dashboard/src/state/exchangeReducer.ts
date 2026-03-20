import type {
  BatchMsg,
  BookUpdateMsg,
  ExecReportMsg,
  Side,
  SnapshotMsg,
  TradeMsg,
} from "../types";

// Keep the last N trades in-memory. The trade tape (slice 11) reads
// the tail; the price chart reads the whole buffer. 5_000 keeps a few
// minutes of activity at realistic rates without breaking memo budgets.
export const TRADE_BUFFER_CAP = 5_000;

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

function applyBookUpdate(side: BookSide, upd: BookUpdateMsg): BookSide {
  const levels = { ...side.levels };
  const orders = { ...side.orders };
  if (upd.quantity <= 0) {
    delete levels[upd.price];
    delete orders[upd.price];
  } else {
    levels[upd.price] = upd.quantity;
    orders[upd.price] = upd.order_count;
  }
  return { levels, orders };
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

function applyExec(
  my: Record<number, MyOrder>,
  exec: ExecReportMsg,
): Record<number, MyOrder> {
  const existing = my[exec.order_id];
  // A client_id of 0 on the exec means "not mine" — the bridge
  // stamps client_id = 0 for unknown orders. We track anyway: the UI
  // decides whether to show "My Orders" vs "All Execs".
  const next: MyOrder = {
    order_id: exec.order_id,
    client_id: exec.client_id,
    side: exec.side,
    price: existing?.price ?? exec.price,
    remaining: exec.remaining_quantity,
    status:
      exec.report_type === "FILL"
        ? "FILLED"
        : exec.report_type === "PARTIAL_FILL"
          ? "PARTIAL"
          : exec.report_type === "CANCELED"
            ? "CANCELLED"
            : exec.report_type === "REJECTED"
              ? "REJECTED"
              : "OPEN",
  };
  return { ...my, [exec.order_id]: next };
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
      for (const upd of action.batch.book_updates) {
        if (upd.side === "BUY") bids = applyBookUpdate(bids, upd);
        else asks = applyBookUpdate(asks, upd);
        if (upd.seq > lastSeq) lastSeq = upd.seq;
      }

      // Trade buffer: append, drop oldest past cap. Allocate once.
      let trades = state.trades;
      if (action.batch.trades.length > 0) {
        const combined = state.trades.concat(action.batch.trades);
        trades =
          combined.length > TRADE_BUFFER_CAP
            ? combined.slice(combined.length - TRADE_BUFFER_CAP)
            : combined;
      }

      let execs = state.execs;
      let myOrders = state.myOrders;
      if (action.batch.execs.length > 0) {
        execs = state.execs.concat(action.batch.execs).slice(-500);
        for (const e of action.batch.execs) {
          myOrders = applyExec(myOrders, e);
        }
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
  const bidPrices = Object.keys(bids.levels).map(Number);
  const askPrices = Object.keys(asks.levels).map(Number);
  const bestBid = bidPrices.length ? Math.max(...bidPrices) : null;
  const bestAsk = askPrices.length ? Math.min(...askPrices) : null;
  const mid =
    bestBid !== null && bestAsk !== null ? (bestBid + bestAsk) / 2 : null;
  return { bestBid, bestAsk, mid };
}
