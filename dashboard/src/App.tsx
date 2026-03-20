import { ConnectionStatus } from "./components/ConnectionStatus";
import { OrderBookLadder } from "./components/OrderBookLadder";
import { DepthChart } from "./components/DepthChart";
import { TradeTape } from "./components/TradeTape";
import { PriceChart } from "./components/PriceChart";
import { OrderEntry } from "./components/OrderEntry";

export function App() {
  return (
    <div className="h-screen flex flex-col">
      <header className="flex items-center justify-between px-4 py-2 border-b border-panel-border">
        <div className="flex items-center gap-3">
          <h1 className="text-base font-bold tracking-tight">NanoExchange</h1>
          <span className="text-xs text-neutral-fg/60">
            low-latency matching engine · dashboard
          </span>
        </div>
        <ConnectionStatus />
      </header>
      <main className="flex-1 min-h-0 grid grid-cols-12 grid-rows-6 gap-2 p-2">
        <section className="col-span-3 row-span-6 min-h-0">
          <OrderBookLadder />
        </section>
        <section className="col-span-6 row-span-3 min-h-0">
          <PriceChart />
        </section>
        <section className="col-span-3 row-span-4 min-h-0">
          <OrderEntry />
        </section>
        <section className="col-span-6 row-span-3 min-h-0">
          <DepthChart />
        </section>
        <section className="col-span-3 row-span-2 min-h-0">
          <TradeTape />
        </section>
        <Placeholder
          className="col-span-3 row-span-2"
          title="Metrics"
          subtitle="coming in slice 12"
        />
      </main>
    </div>
  );
}

interface PlaceholderProps {
  className: string;
  title: string;
  subtitle: string;
}

function Placeholder({ className, title, subtitle }: PlaceholderProps) {
  return (
    <section className={`${className} min-h-0`}>
      <div className="h-full flex flex-col items-center justify-center border border-dashed border-panel-border rounded bg-panel-bg/40">
        <div className="text-sm font-semibold text-neutral-fg/70">{title}</div>
        <div className="text-[10px] uppercase tracking-wider text-neutral-fg/40">
          {subtitle}
        </div>
      </div>
    </section>
  );
}
