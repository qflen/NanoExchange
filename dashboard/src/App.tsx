import { useMemo, useState } from "react";
import { ConnectionStatus } from "./components/ConnectionStatus";
import { OrderBookLadder } from "./components/OrderBookLadder";
import { VirtualizedLadder } from "./components/VirtualizedLadder";
import { DepthChart } from "./components/DepthChart";
import { TradeTape } from "./components/TradeTape";
import { PriceChart } from "./components/PriceChart";
import { OrderEntry } from "./components/OrderEntry";
import { LatencyMonitor } from "./components/LatencyMonitor";
import { MetricsPanel } from "./components/MetricsPanel";
import { useExchange } from "./state/ExchangeContext";

const VIRTUALISATION_THRESHOLD = 100;

const PANEL = "min-h-0 min-w-0 resize overflow-auto";

export function App() {
  const { state } = useExchange();
  const [forceVirtual, setForceVirtual] = useState(false);
  const deepBook = useMemo(() => {
    const levels =
      Object.keys(state.bids.levels).length +
      Object.keys(state.asks.levels).length;
    return levels > VIRTUALISATION_THRESHOLD;
  }, [state.bids, state.asks]);
  const useVirtual = deepBook || forceVirtual;

  return (
    <div className="h-screen flex flex-col">
      <header className="flex items-center justify-between px-4 py-2 border-b border-panel-border">
        <div className="flex items-center gap-3">
          <h1 className="text-base font-bold tracking-tight">NanoExchange</h1>
          <span className="text-xs text-neutral-fg/60">
            low-latency matching engine · dashboard
          </span>
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-1 text-[11px] text-neutral-fg/70 cursor-pointer">
            <input
              type="checkbox"
              checked={forceVirtual}
              onChange={(e) => setForceVirtual(e.target.checked)}
              className="accent-highlight"
            />
            virtualise ladder
          </label>
          <ConnectionStatus />
        </div>
      </header>
      <main className="flex-1 min-h-0 grid grid-cols-12 grid-rows-6 gap-2 p-2">
        <section className={`col-span-3 row-span-6 ${PANEL}`}>
          {useVirtual ? <VirtualizedLadder /> : <OrderBookLadder />}
        </section>
        <section className={`col-span-6 row-span-3 ${PANEL}`}>
          <PriceChart />
        </section>
        <section className={`col-span-3 row-span-4 ${PANEL}`}>
          <OrderEntry />
        </section>
        <section className={`col-span-6 row-span-3 ${PANEL}`}>
          <DepthChart />
        </section>
        <section className={`col-span-3 row-span-2 ${PANEL}`}>
          <TradeTape />
        </section>
        <section className={`col-span-3 row-span-1 ${PANEL}`}>
          <LatencyMonitor />
        </section>
        <section className={`col-span-3 row-span-1 ${PANEL}`}>
          <MetricsPanel />
        </section>
      </main>
    </div>
  );
}
