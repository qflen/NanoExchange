// Wire types mirror the JSON that `nanoexchange_bridge` emits.
// Keep them in sync with `bridge/src/nanoexchange_bridge/translator.py`.

export type Side = "BUY" | "SELL";

export interface BookUpdateMsg {
  type: "book_update";
  seq: number;
  side: Side;
  price: number;
  quantity: number;
  order_count: number;
}

export interface TradeMsg {
  type: "trade";
  seq: number;
  taker_order_id: number;
  maker_order_id: number;
  taker_side: Side;
  price: number;
  quantity: number;
  ts_ns: number;
}

export interface SnapshotLevel {
  side: Side;
  price: number;
  quantity: number;
  order_count: number;
}

export interface SnapshotMsg {
  type: "snapshot";
  seq: number;
  levels: SnapshotLevel[];
}

export type ReportType =
  | "ACK"
  | "PARTIAL_FILL"
  | "FILL"
  | "CANCELED"
  | "REJECTED"
  | "MODIFIED";

export interface ExecReportMsg {
  type: "exec_report";
  report_type: ReportType;
  order_id: number;
  client_id: number;
  side: Side;
  price: number;
  executed_price: number;
  executed_quantity: number;
  remaining_quantity: number;
  counterparty_order_id: number;
  ts_ns: number;
}

export interface BatchMsg {
  type: "batch";
  book_updates: BookUpdateMsg[];
  trades: TradeMsg[];
  snapshots: SnapshotMsg[];
  execs: ExecReportMsg[];
  degraded?: boolean;
}

export interface NoticeMsg {
  type: "notice";
  message: string;
}

export type ServerMsg = BatchMsg | NoticeMsg;
