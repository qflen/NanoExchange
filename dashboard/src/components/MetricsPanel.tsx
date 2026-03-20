import { useMemo } from "react";
import { useExchange } from "../state/ExchangeContext";
import { useThroughput } from "../hooks/useThroughput";
import { selectBestBidAsk } from "../state/exchangeReducer";

export function MetricsPanel() {
  const { state } = useExchange();
  const tp = useThroughput();
  const { bestBid, bestAsk, mid } = useMemo(
    () => selectBestBidAsk(state.bids, state.asks),
    [state.bids, state.asks],
  );
  const bidLevels = Object.keys(state.bids.levels).length;
  const askLevels = Object.keys(state.asks.levels).length;

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-2 border-b border-panel-border">
        <h2 className="text-sm font-bold">Metrics</h2>
      </div>
      <div className="flex-1 min-h-0 p-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs tabular-nums">
        <Cell label="book updates / s" value={tp.bookUpdatesPerSec.toFixed(0)} />
        <Cell label="trades / s" value={tp.tradesPerSec.toFixed(1)} />
        <Cell label="execs / s" value={tp.execsPerSec.toFixed(1)} />
        <Cell label="levels (bid/ask)" value={`${bidLevels}/${askLevels}`} />
        <Cell label="last seq" value={tp.totalBookUpdates.toString()} />
        <Cell label="trades total" value={tp.totalTrades.toString()} />
        <Cell
          label="best bid"
          value={bestBid !== null ? bestBid.toFixed(4) : "—"}
          valueClass="text-bid-green"
        />
        <Cell
          label="best ask"
          value={bestAsk !== null ? bestAsk.toFixed(4) : "—"}
          valueClass="text-ask-red"
        />
        <Cell
          label="mid"
          value={mid !== null ? mid.toFixed(4) : "—"}
          valueClass="text-highlight"
        />
        <Cell
          label="degraded"
          value={state.degraded ? "YES" : "no"}
          valueClass={state.degraded ? "text-ask-red" : "text-neutral-fg/60"}
        />
      </div>
    </div>
  );
}

function Cell({
  label,
  value,
  valueClass,
}: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <>
      <div className="text-neutral-fg/50">{label}</div>
      <div className={`text-right ${valueClass ?? ""}`}>{value}</div>
    </>
  );
}
