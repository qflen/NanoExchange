import { useEffect, useMemo, useRef, useState } from "react";
import { useExchange } from "../state/ExchangeContext";
import {
  selectAskRows,
  selectBestBidAsk,
  selectBidRows,
  type LadderRow,
} from "../state/exchangeReducer";

/**
 * Virtualised order-book ladder.
 *
 * Used in place of the plain `OrderBookLadder` when the book is deep
 * (hundreds of levels). Each row has a fixed pixel height; we render
 * only the rows whose `top` intersects the viewport plus a small
 * overscan above/below. Scroll is tracked via `onScroll` and a
 * requestAnimationFrame guard so we batch viewport updates per frame.
 *
 * A plain virtualisation library would work but would also pull in a
 * measurement cache and keyed re-renders we don't need — the ladder
 * is fixed-row-height and the keys are stable (side + price).
 */

const ROW_HEIGHT = 16;
const OVERSCAN = 8;

export function VirtualizedLadder() {
  const { state } = useExchange();
  const bidRows = useMemo(() => selectBidRows(state.bids), [state.bids]);
  const askRows = useMemo(() => selectAskRows(state.asks), [state.asks]);
  const { bestBid, bestAsk } = useMemo(
    () => selectBestBidAsk(state.bids, state.asks),
    [state.bids, state.asks],
  );
  const maxQty = useMemo(() => {
    let m = 0;
    for (const r of bidRows) if (r.quantity > m) m = r.quantity;
    for (const r of askRows) if (r.quantity > m) m = r.quantity;
    return m || 1;
  }, [bidRows, askRows]);

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-2 border-b border-panel-border">
        <h2 className="text-sm font-bold">Order Book (virtualised)</h2>
        <div className="text-xs text-neutral-fg/60">
          {bidRows.length} bid · {askRows.length} ask
        </div>
      </div>
      <div className="flex-1 min-h-0 grid grid-rows-2 gap-0.5">
        <VirtualList
          rows={askRows.slice().reverse()}
          side="SELL"
          maxQty={maxQty}
        />
        <div className="px-3 py-1 text-xs text-center text-highlight border-y border-panel-border">
          {bestBid !== null && bestAsk !== null
            ? `${bestBid.toFixed(4)}  /  ${bestAsk.toFixed(4)}`
            : "—"}
        </div>
        <VirtualList rows={bidRows} side="BUY" maxQty={maxQty} />
      </div>
    </div>
  );
}

interface VListProps {
  rows: LadderRow[];
  side: "BUY" | "SELL";
  maxQty: number;
}

function VirtualList({ rows, side, maxQty }: VListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const rafRef = useRef<number | null>(null);
  const [viewport, setViewport] = useState({ scrollTop: 0, height: 0 });

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    setViewport({ scrollTop: el.scrollTop, height: el.clientHeight });
    const ro = new ResizeObserver(() => {
      setViewport((v) => ({ ...v, height: el.clientHeight }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const onScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const target = e.currentTarget;
    if (rafRef.current !== null) return;
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null;
      setViewport((v) => ({ ...v, scrollTop: target.scrollTop }));
    });
  };

  const total = rows.length;
  const firstVisible = Math.max(
    0,
    Math.floor(viewport.scrollTop / ROW_HEIGHT) - OVERSCAN,
  );
  const visibleCount = Math.ceil(viewport.height / ROW_HEIGHT) + OVERSCAN * 2;
  const lastVisible = Math.min(total, firstVisible + visibleCount);

  const barColor = side === "BUY" ? "bg-bid-green/20" : "bg-ask-red/20";
  const priceColor = side === "BUY" ? "text-bid-green" : "text-ask-red";

  return (
    <div
      ref={containerRef}
      onScroll={onScroll}
      className="overflow-y-auto font-mono relative"
      data-testid={`vlist-${side}`}
    >
      <div style={{ height: `${total * ROW_HEIGHT}px` }}>
        {rows.slice(firstVisible, lastVisible).map((r, i) => {
          const top = (firstVisible + i) * ROW_HEIGHT;
          const pct = Math.min(100, (r.quantity / maxQty) * 100);
          return (
            <div
              key={`${side}-${r.price}`}
              style={{
                position: "absolute",
                top: `${top}px`,
                left: 0,
                right: 0,
                height: `${ROW_HEIGHT}px`,
              }}
              className="grid grid-cols-3 gap-1 px-3 text-[11px]"
            >
              <div
                aria-hidden="true"
                className={`absolute top-0 bottom-0 ${side === "BUY" ? "right-0" : "left-0"} ${barColor}`}
                style={{ width: `${pct}%` }}
              />
              <div className="relative text-right tabular-nums">{r.quantity}</div>
              <div className={`relative text-center tabular-nums ${priceColor}`}>
                {r.price.toFixed(4)}
              </div>
              <div className="relative text-left tabular-nums text-neutral-fg/60">
                {r.orderCount}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
