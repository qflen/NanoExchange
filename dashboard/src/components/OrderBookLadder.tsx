import { useMemo, useRef, useEffect } from "react";
import { useExchange } from "../state/ExchangeContext";
import {
  selectAskRows,
  selectBidRows,
  selectBestBidAsk,
  type LadderRow,
} from "../state/exchangeReducer";

// Render a fixed window of ±N price levels around the BBO so the UI
// does not reflow on every update. Deep books (thousands of levels)
// fall to the virtualised ladder in slice 12.
const ROWS_PER_SIDE = 12;

export function OrderBookLadder() {
  const { state } = useExchange();

  const bidRows = useMemo(() => selectBidRows(state.bids).slice(0, ROWS_PER_SIDE), [state.bids]);
  const askRows = useMemo(
    () =>
      selectAskRows(state.asks)
        .slice(0, ROWS_PER_SIDE)
        .reverse(),
    [state.asks],
  );
  const { bestBid, bestAsk, mid } = useMemo(
    () => selectBestBidAsk(state.bids, state.asks),
    [state.bids, state.asks],
  );
  const spread = bestBid !== null && bestAsk !== null ? bestAsk - bestBid : null;

  const maxQty = useMemo(() => {
    let m = 0;
    for (const r of bidRows) if (r.quantity > m) m = r.quantity;
    for (const r of askRows) if (r.quantity > m) m = r.quantity;
    return m || 1;
  }, [bidRows, askRows]);

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-2 border-b border-panel-border">
        <h2 className="text-sm font-bold">Order Book</h2>
        <div className="text-xs text-neutral-fg/70">
          {mid !== null ? (
            <>
              mid <span className="text-neutral-fg">{mid.toFixed(4)}</span>
              {spread !== null && (
                <>
                  {" "}· spread{" "}
                  <span className="text-neutral-fg">{spread.toFixed(4)}</span>
                </>
              )}
            </>
          ) : (
            <>waiting for data…</>
          )}
        </div>
      </div>
      <div className="grid grid-cols-3 gap-1 px-3 py-1 text-[11px] text-neutral-fg/50 border-b border-panel-border">
        <div className="text-right">Qty</div>
        <div className="text-center">Price</div>
        <div className="text-left">Orders</div>
      </div>
      <div className="flex-1 min-h-0 grid grid-cols-1">
        <LadderSide rows={askRows} side="SELL" maxQty={maxQty} />
        <div className="border-y border-panel-border px-3 py-1 text-xs text-center text-highlight">
          {bestBid !== null && bestAsk !== null
            ? `${bestBid.toFixed(4)}  /  ${bestAsk.toFixed(4)}`
            : "—"}
        </div>
        <LadderSide rows={bidRows} side="BUY" maxQty={maxQty} />
      </div>
    </div>
  );
}

interface SideProps {
  rows: LadderRow[];
  side: "BUY" | "SELL";
  maxQty: number;
}

function LadderSide({ rows, side, maxQty }: SideProps) {
  return (
    <div className="flex flex-col">
      {rows.map((r) => (
        <LadderRowView key={`${side}-${r.price}`} row={r} side={side} maxQty={maxQty} />
      ))}
    </div>
  );
}

interface RowProps {
  row: LadderRow;
  side: "BUY" | "SELL";
  maxQty: number;
}

// Flash animation: track previous qty in a ref; when it changes,
// toggle a CSS class for 150ms. The class is re-applied via React
// state so the animation restarts on each change.
function LadderRowView({ row, side, maxQty }: RowProps) {
  const prevQty = useRef(row.quantity);
  const rowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = rowRef.current;
    if (!el) return;
    if (row.quantity !== prevQty.current) {
      const direction = row.quantity > prevQty.current ? "flash-up" : "flash-down";
      el.classList.remove("flash-up", "flash-down");
      // Force reflow so the animation re-triggers.
      void el.offsetWidth;
      el.classList.add(direction);
      prevQty.current = row.quantity;
    }
  }, [row.quantity]);

  const barColor = side === "BUY" ? "bg-bid-green/20" : "bg-ask-red/20";
  const priceColor = side === "BUY" ? "text-bid-green" : "text-ask-red";
  const pct = Math.min(100, (row.quantity / maxQty) * 100);

  return (
    <div
      ref={rowRef}
      className="relative grid grid-cols-3 gap-1 px-3 py-0.5 text-xs"
      data-testid={`ladder-row-${side}-${row.price}`}
    >
      <div
        aria-hidden="true"
        className={`absolute top-0 bottom-0 ${side === "BUY" ? "right-0" : "left-0"} ${barColor}`}
        style={{ width: `${pct}%` }}
      />
      <div className="relative text-right tabular-nums">{row.quantity}</div>
      <div className={`relative text-center tabular-nums ${priceColor}`}>
        {row.price.toFixed(4)}
      </div>
      <div className="relative text-left tabular-nums text-neutral-fg/60">
        {row.orderCount}
      </div>
    </div>
  );
}
