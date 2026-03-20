import { useEffect, useMemo, useRef, useState } from "react";
import { useExchange } from "../state/ExchangeContext";
import type { TradeMsg } from "../types";

// Show the most recent trades first. Scroll-lock UX: if the user
// scrolls down (away from the newest), stop auto-scrolling until they
// return. `scrollTop === 0` means we're pinned at the top.
const TAPE_RENDER_CAP = 200;

export function TradeTape() {
  const { state } = useExchange();
  const containerRef = useRef<HTMLDivElement>(null);
  const [pinned, setPinned] = useState(true);

  const recent: TradeMsg[] = useMemo(() => {
    const t = state.trades;
    if (t.length <= TAPE_RENDER_CAP) return t.slice().reverse();
    return t.slice(t.length - TAPE_RENDER_CAP).reverse();
  }, [state.trades]);

  useEffect(() => {
    if (!pinned) return;
    const el = containerRef.current;
    if (el) el.scrollTop = 0;
  }, [recent, pinned]);

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    const top = e.currentTarget.scrollTop;
    // Allow a little slack — `scrollTop` can be a fractional value
    // after a zoom or a fast wheel event.
    setPinned(top < 4);
  };

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-2 border-b border-panel-border">
        <h2 className="text-sm font-bold">Trade Tape</h2>
        <div className="flex items-center gap-2 text-xs text-neutral-fg/60">
          <span>{state.trades.length} total</span>
          {!pinned && (
            <button
              type="button"
              onClick={() => setPinned(true)}
              className="px-1.5 py-0.5 rounded bg-highlight/20 text-highlight border border-highlight/40 text-[10px]"
            >
              jump to latest
            </button>
          )}
        </div>
      </div>
      <div className="grid grid-cols-4 gap-1 px-3 py-1 text-[11px] text-neutral-fg/50 border-b border-panel-border">
        <div className="text-left">Time</div>
        <div className="text-right">Qty</div>
        <div className="text-right">Price</div>
        <div className="text-right">Side</div>
      </div>
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="flex-1 min-h-0 overflow-y-auto font-mono tabular-nums"
      >
        {recent.map((t) => (
          <TradeRow key={`${t.seq}-${t.ts_ns}`} trade={t} />
        ))}
      </div>
    </div>
  );
}

function TradeRow({ trade }: { trade: TradeMsg }) {
  const dir = trade.taker_side === "BUY" ? "text-bid-green" : "text-ask-red";
  const hhmmss = useMemo(() => {
    if (trade.ts_ns === 0) return "—";
    const ms = Math.floor(trade.ts_ns / 1_000_000);
    return new Date(ms).toLocaleTimeString("en-US", { hour12: false });
  }, [trade.ts_ns]);
  return (
    <div className="grid grid-cols-4 gap-1 px-3 py-0.5 text-[11px] border-b border-panel-border/40">
      <div className="text-neutral-fg/70">{hhmmss}</div>
      <div className="text-right">{trade.quantity}</div>
      <div className={`text-right ${dir}`}>{trade.price.toFixed(4)}</div>
      <div className={`text-right ${dir}`}>{trade.taker_side}</div>
    </div>
  );
}
