import { useEffect, useMemo, useRef, useState } from "react";
import { ConnectionStatus } from "./components/ConnectionStatus";
import { OrderBookLadder } from "./components/OrderBookLadder";
import { VirtualizedLadder } from "./components/VirtualizedLadder";
import { DepthChart } from "./components/DepthChart";
import { TradeTape } from "./components/TradeTape";
import { PriceChart } from "./components/PriceChart";
import { OrderEntry } from "./components/OrderEntry";
import { LatencyMonitor } from "./components/LatencyMonitor";
import { MetricsPanel } from "./components/MetricsPanel";
import { Window, type WindowGeom } from "./components/Window";
import { useWindowManager } from "./hooks/useWindowManager";
import { useExchange } from "./state/ExchangeContext";

const VIRTUALISATION_THRESHOLD = 100;
const HEADER_PX = 44;

const PANEL_IDS = [
  "ladder",
  "priceChart",
  "orderEntry",
  "depthChart",
  "tradeTape",
  "latency",
  "metrics",
] as const;

type PanelId = (typeof PANEL_IDS)[number];

// Initial layout in fractions of the viewport. The window manager keeps
// per-window geometry after this; users drag and resize from here.
function computeInitialLayout(vw: number, vh: number): Record<PanelId, WindowGeom> {
  const w = Math.max(640, vw);
  const h = Math.max(480, vh - HEADER_PX);
  const g = 6;
  const colL = Math.floor(w * 0.25);
  const colM = Math.floor(w * 0.5);
  const colR = w - colL - colM - 2 * g;
  const rowTop = Math.floor(h * 0.5);
  const rowBot = h - rowTop - g;
  const orderEntryH = Math.floor(h * 0.66);
  const tradeTapeH = h - orderEntryH - g;
  const smallH = Math.floor(tradeTapeH * 0.5);
  return {
    ladder: { x: 0, y: 0, w: colL, h },
    priceChart: { x: colL + g, y: 0, w: colM, h: rowTop },
    depthChart: { x: colL + g, y: rowTop + g, w: colM, h: rowBot },
    orderEntry: { x: colL + colM + 2 * g, y: 0, w: colR, h: orderEntryH },
    tradeTape: { x: colL + colM + 2 * g, y: orderEntryH + g, w: Math.floor(colR / 2), h: tradeTapeH },
    latency: {
      x: colL + colM + 2 * g + Math.floor(colR / 2) + g,
      y: orderEntryH + g,
      w: colR - Math.floor(colR / 2) - g,
      h: smallH,
    },
    metrics: {
      x: colL + colM + 2 * g + Math.floor(colR / 2) + g,
      y: orderEntryH + g + smallH + g,
      w: colR - Math.floor(colR / 2) - g,
      h: tradeTapeH - smallH - g,
    },
  };
}

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

  const desktopRef = useRef<HTMLDivElement>(null);
  const initialLayoutRef = useRef<Record<PanelId, WindowGeom> | null>(null);
  if (initialLayoutRef.current === null) {
    initialLayoutRef.current = computeInitialLayout(
      typeof window !== "undefined" ? window.innerWidth : 1440,
      typeof window !== "undefined" ? window.innerHeight : 900,
    );
  }
  const layout = initialLayoutRef.current;

  // Recompute initial layout on first mount in case the SSR/initial
  // estimate was wrong (it shouldn't be in a Vite app, but cheap).
  useEffect(() => {
    if (!desktopRef.current) return;
    const r = desktopRef.current.getBoundingClientRect();
    initialLayoutRef.current = computeInitialLayout(r.width, r.height + HEADER_PX);
    // Initial-only: subsequent geometry is owned by Window components.
  }, []);

  const { zMap, focus } = useWindowManager(PANEL_IDS);

  const panels: Array<{ id: PanelId; title: string; node: React.ReactNode }> = [
    {
      id: "ladder",
      title: "Order Book",
      node: useVirtual ? <VirtualizedLadder /> : <OrderBookLadder />,
    },
    { id: "priceChart", title: "Price", node: <PriceChart /> },
    { id: "depthChart", title: "Depth", node: <DepthChart /> },
    { id: "orderEntry", title: "Order Entry", node: <OrderEntry /> },
    { id: "tradeTape", title: "Trades", node: <TradeTape /> },
    { id: "latency", title: "Latency", node: <LatencyMonitor /> },
    { id: "metrics", title: "Metrics", node: <MetricsPanel /> },
  ];

  return (
    <div className="h-screen flex flex-col">
      <header className="flex items-center justify-between px-4 py-2 border-b border-panel-border" style={{ height: HEADER_PX }}>
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
      <div ref={desktopRef} className="flex-1 relative overflow-hidden bg-black/30">
        {panels.map((p) => (
          <Window
            key={p.id}
            title={p.title}
            initial={layout[p.id]}
            z={zMap[p.id]}
            onFocus={() => focus(p.id)}
          >
            {p.node}
          </Window>
        ))}
      </div>
    </div>
  );
}
