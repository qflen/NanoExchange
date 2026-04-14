import { useEffect, useMemo, useRef } from "react";
import { ConnectionStatus } from "./components/ConnectionStatus";
import { ThemeToggle } from "./components/ThemeToggle";
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
  // Right column splits vertically: a short order-entry panel up top,
  // then a bottom region split horizontally between the trade tape and
  // a metrics+latency stack. Metrics needs ~240 px to render its ten
  // rows without scrolling, so latency takes the leftover.
  const orderEntryH = Math.floor(h * 0.5);
  const bottomH = h - orderEntryH - g;
  const colRright = colR - Math.floor(colR / 2) - g;
  const colRleft = Math.floor(colR / 2);
  // Metrics just needs enough vertical room for its ten tight rows.
  // Latency inherits whatever is left of the bottom region.
  const metricsH = Math.max(170, Math.min(200, bottomH - 160));
  const latencyH = bottomH - metricsH - g;
  return {
    ladder: { x: 0, y: 0, w: colL, h },
    priceChart: { x: colL + g, y: 0, w: colM, h: rowTop },
    depthChart: { x: colL + g, y: rowTop + g, w: colM, h: rowBot },
    orderEntry: { x: colL + colM + 2 * g, y: 0, w: colR, h: orderEntryH },
    tradeTape: { x: colL + colM + 2 * g, y: orderEntryH + g, w: colRleft, h: bottomH },
    metrics: {
      x: colL + colM + 2 * g + colRleft + g,
      y: orderEntryH + g,
      w: colRright,
      h: metricsH,
    },
    latency: {
      x: colL + colM + 2 * g + colRleft + g,
      y: orderEntryH + g + metricsH + g,
      w: colRright,
      h: latencyH,
    },
  };
}

export function App() {
  const { state } = useExchange();
  // Auto-switch to the virtualised ladder once the book is too deep to
  // paint every row each frame. No manual override — when both sides
  // of the book are shallow, both renderers look identical, so a
  // user-facing toggle just added a visual no-op.
  const useVirtual = useMemo(() => {
    const levels =
      Object.keys(state.bids.levels).length +
      Object.keys(state.asks.levels).length;
    return levels > VIRTUALISATION_THRESHOLD;
  }, [state.bids, state.asks]);

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
      title: useVirtual ? "Order Book (virtual)" : "Order Book",
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
      <header className="relative flex items-center px-4 py-2 border-b border-panel-border" style={{ height: HEADER_PX }}>
        {/* Centred brand: logo + title + subtitle. Absolute-positioned
            right-side status so the centring is anchored to the header,
            not pushed by the status width. */}
        <div className="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 flex items-center gap-3">
          <img
            src="/favicon.svg"
            alt=""
            aria-hidden="true"
            className="w-6 h-6 flex-shrink-0"
          />
          <h1 className="text-base font-bold tracking-tight">NanoExchange</h1>
          <span className="text-xs text-neutral-fg/60">
            low-latency matching engine · dashboard
          </span>
        </div>
        <div className="ml-auto flex items-center gap-3">
          <ThemeToggle />
          <ConnectionStatus />
        </div>
      </header>
      <div ref={desktopRef} className="flex-1 relative overflow-hidden">
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
