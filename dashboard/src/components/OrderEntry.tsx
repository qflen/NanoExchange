import { useMemo, useState } from "react";
import { useExchange } from "../state/ExchangeContext";
import { useOrderSubmit, type OrderType, type Side } from "../hooks/useOrderSubmit";
import { selectBestBidAsk } from "../state/exchangeReducer";

// A form that encodes the lessons learned the hard way in other
// order-entry UIs:
//   1. Market orders disable the price field AND drop price from the
//      payload — otherwise the bridge forwards a zero-price limit.
//   2. Ticker up/down use text-aligned custom buttons, not the native
//      spinner (browser-inconsistent and visually awful at this scale).
//   3. Submit disables itself when the draft is invalid, so we never
//      ship a quantity=0 or negative-priced order.

const SIDES: Side[] = ["BUY", "SELL"];
const TYPES: OrderType[] = ["LIMIT", "MARKET", "IOC", "FOK"];

export function OrderEntry() {
  const { state } = useExchange();
  const { submit, cancel } = useOrderSubmit();
  const [side, setSide] = useState<Side>("BUY");
  const [orderType, setOrderType] = useState<OrderType>("LIMIT");
  const [price, setPrice] = useState<string>("");
  const [quantity, setQuantity] = useState<string>("");
  const [lastStatus, setLastStatus] = useState<string | null>(null);

  const { bestBid, bestAsk, mid } = useMemo(
    () => selectBestBidAsk(state.bids, state.asks),
    [state.bids, state.asks],
  );

  const priceNum = price === "" ? NaN : Number(price);
  const qtyNum = quantity === "" ? NaN : Number(quantity);
  const priceValid = orderType === "MARKET" || (Number.isFinite(priceNum) && priceNum > 0);
  const qtyValid = Number.isFinite(qtyNum) && qtyNum > 0;
  const canSubmit = state.connected && priceValid && qtyValid;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const out = submit({
      side,
      orderType,
      price: orderType === "MARKET" ? undefined : priceNum,
      quantity: Math.floor(qtyNum),
    });
    if (out) {
      setLastStatus(`submitted #${out.orderId}`);
    } else {
      setLastStatus("submit rejected locally");
    }
  };

  const adjustPrice = (delta: number) => {
    const n = Number.isFinite(priceNum) ? priceNum : (mid ?? 100);
    setPrice((n + delta).toFixed(2));
  };

  const openOrders = useMemo(
    () =>
      Object.values(state.myOrders)
        .filter((o) => o.status === "OPEN" || o.status === "PARTIAL")
        .sort((a, b) => b.order_id - a.order_id),
    [state.myOrders],
  );

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-2 border-b border-panel-border">
        <h2 className="text-sm font-bold">Order Entry</h2>
        <div className="text-xs text-neutral-fg/60">
          {bestBid !== null && bestAsk !== null ? (
            <>
              <span className="text-bid-green">{bestBid.toFixed(2)}</span>
              {" / "}
              <span className="text-ask-red">{bestAsk.toFixed(2)}</span>
            </>
          ) : (
            "—"
          )}
        </div>
      </div>

      <form className="flex flex-col gap-2 p-3" onSubmit={handleSubmit}>
        <div className="flex gap-1">
          {SIDES.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setSide(s)}
              className={
                "flex-1 py-1 rounded border text-xs font-bold " +
                (side === s
                  ? s === "BUY"
                    ? "bg-bid-green/30 border-bid-green text-bid-green"
                    : "bg-ask-red/30 border-ask-red text-ask-red"
                  : "border-panel-border text-neutral-fg/60 hover:text-neutral-fg")
              }
            >
              {s}
            </button>
          ))}
        </div>

        <div className="flex gap-1">
          {TYPES.map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => setOrderType(t)}
              className={
                "flex-1 py-1 rounded border text-[11px] " +
                (orderType === t
                  ? "bg-highlight/15 border-highlight text-highlight"
                  : "border-panel-border text-neutral-fg/60 hover:text-neutral-fg")
              }
            >
              {t}
            </button>
          ))}
        </div>

        <label className="flex flex-col gap-1 text-[11px] text-neutral-fg/60">
          Price
          <div className="flex">
            <button
              type="button"
              disabled={orderType === "MARKET"}
              onClick={() => adjustPrice(-0.01)}
              className="px-2 rounded-l border border-panel-border text-neutral-fg/70 hover:text-neutral-fg disabled:opacity-30"
            >
              −
            </button>
            <input
              inputMode="decimal"
              disabled={orderType === "MARKET"}
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              placeholder={orderType === "MARKET" ? "market" : mid ? mid.toFixed(2) : "0.00"}
              className="flex-1 min-w-0 px-2 py-1 bg-black/30 border-y border-panel-border text-right text-sm tabular-nums focus:outline-none focus:border-highlight/60 disabled:opacity-40"
            />
            <button
              type="button"
              disabled={orderType === "MARKET"}
              onClick={() => adjustPrice(+0.01)}
              className="px-2 rounded-r border border-panel-border text-neutral-fg/70 hover:text-neutral-fg disabled:opacity-30"
            >
              +
            </button>
          </div>
        </label>

        <label className="flex flex-col gap-1 text-[11px] text-neutral-fg/60">
          Quantity
          <input
            inputMode="numeric"
            value={quantity}
            onChange={(e) => setQuantity(e.target.value.replace(/[^0-9]/g, ""))}
            placeholder="0"
            className="px-2 py-1 bg-black/30 border border-panel-border rounded text-right text-sm tabular-nums focus:outline-none focus:border-highlight/60"
          />
        </label>

        <button
          type="submit"
          disabled={!canSubmit}
          className={
            "mt-1 py-1.5 rounded text-xs font-bold " +
            (side === "BUY"
              ? "bg-bid-green text-black disabled:opacity-40"
              : "bg-ask-red text-white disabled:opacity-40")
          }
        >
          Submit {side} {orderType}
        </button>
        {lastStatus && (
          <div className="text-[11px] text-neutral-fg/60">{lastStatus}</div>
        )}
      </form>

      <div className="border-t border-panel-border px-3 py-2">
        <div className="text-[11px] text-neutral-fg/60 mb-1">
          Open orders ({openOrders.length})
        </div>
        <div className="max-h-28 overflow-y-auto font-mono text-[11px]">
          {openOrders.length === 0 ? (
            <div className="text-neutral-fg/40">none</div>
          ) : (
            openOrders.map((o) => (
              <div
                key={o.order_id}
                className="flex items-center justify-between py-0.5"
              >
                <span className={o.side === "BUY" ? "text-bid-green" : "text-ask-red"}>
                  {o.side} {o.remaining} @ {o.price.toFixed(2)}
                </span>
                <button
                  type="button"
                  onClick={() => cancel(o.order_id)}
                  className="text-highlight/80 hover:text-highlight text-[10px]"
                >
                  cancel
                </button>
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
