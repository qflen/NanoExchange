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
      <div className="min-h-0 overflow-y-auto px-3 py-2 flex flex-col gap-0.5 text-xs tabular-nums">
        <Row label="book updates / s" value={tp.bookUpdatesPerSec.toFixed(0)} />
        <Row label="trades / s" value={tp.tradesPerSec.toFixed(1)} />
        <Row label="execs / s" value={tp.execsPerSec.toFixed(1)} />
        <Row label="levels (bid/ask)" value={`${bidLevels}/${askLevels}`} />
        <Row label="last seq" value={tp.totalBookUpdates.toString()} />
        <Row label="trades total" value={tp.totalTrades.toString()} />
        <Row
          label="best bid"
          value={bestBid !== null ? bestBid.toFixed(4) : "—"}
          valueClass="text-bid-green"
        />
        <Row
          label="best ask"
          value={bestAsk !== null ? bestAsk.toFixed(4) : "—"}
          valueClass="text-ask-red"
        />
        <Row
          label="mid"
          value={mid !== null ? mid.toFixed(4) : "—"}
          valueClass="text-highlight"
        />
        <Row
          label="degraded"
          value={state.degraded ? "YES" : "no"}
          valueClass={state.degraded ? "text-ask-red" : "text-neutral-fg/60"}
        />
      </div>
    </div>
  );
}

function Row({
  label,
  value,
  valueClass,
}: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <div className="flex items-center justify-between gap-2 whitespace-nowrap">
      <span className="text-neutral-fg/50 truncate">{label}</span>
      <span className={`text-right ${valueClass ?? ""}`}>{value}</span>
    </div>
  );
}
