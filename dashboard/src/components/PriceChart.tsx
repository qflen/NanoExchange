import { useMemo, useState } from "react";
import { scaleLinear } from "d3-scale";
import { line as d3Line } from "d3-shape";
import { useExchange } from "../state/ExchangeContext";

// Last-trade line + volume bars. Stays on raw SVG + d3-scale because
// Recharts wants array reconciliation per tick, and at 10k msg/s that
// is a renderer-level cost we do not need. With a 5k-deep ring of
// trades in the reducer, a cold filter per frame is fine — a user
// rarely keeps a narrow window open long enough to notice.

const WIDTH = 620;
const HEIGHT = 200;
const MARGIN = { top: 10, right: 12, bottom: 28, left: 40 };
const VOL_FRACTION = 0.28; // portion of plot height for volume bars

const WINDOWS: { label: string; seconds: number }[] = [
  { label: "10s", seconds: 10 },
  { label: "1m", seconds: 60 },
  { label: "5m", seconds: 300 },
];

export function PriceChart() {
  const { state } = useExchange();
  const [windowSec, setWindowSec] = useState<number>(60);

  const points = useMemo(() => {
    if (state.trades.length === 0) return [] as { t: number; p: number; q: number }[];
    const now = state.trades[state.trades.length - 1].ts_ns / 1_000_000;
    const cutoff = now - windowSec * 1_000;
    const filtered: { t: number; p: number; q: number }[] = [];
    for (const tr of state.trades) {
      const tMs = tr.ts_ns / 1_000_000;
      if (tMs >= cutoff) filtered.push({ t: tMs, p: tr.price, q: tr.quantity });
    }
    return filtered;
  }, [state.trades, windowSec]);

  const view = useMemo(() => {
    if (points.length === 0) return null;
    const t0 = points[0].t;
    const t1 = points[points.length - 1].t;
    const minP = Math.min(...points.map((p) => p.p));
    const maxP = Math.max(...points.map((p) => p.p));
    const padding = (maxP - minP) * 0.1 || 0.25;
    const x = scaleLinear()
      .domain([t0, t1 || t0 + 1])
      .range([MARGIN.left, WIDTH - MARGIN.right]);
    const priceBottom = HEIGHT - MARGIN.bottom - (HEIGHT - MARGIN.top - MARGIN.bottom) * VOL_FRACTION;
    const y = scaleLinear()
      .domain([minP - padding, maxP + padding])
      .range([priceBottom, MARGIN.top]);
    const maxVol = Math.max(1, ...points.map((p) => p.q));
    const yVol = scaleLinear()
      .domain([0, maxVol])
      .range([HEIGHT - MARGIN.bottom, priceBottom + 2]);
    const path = d3Line<{ t: number; p: number }>()
      .x((d) => x(d.t))
      .y((d) => y(d.p))(points);
    return { x, y, yVol, path, minP, maxP, priceBottom };
  }, [points]);

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-2 border-b border-panel-border">
        <h2 className="text-sm font-bold">Price</h2>
        <div className="flex items-center gap-1 text-xs">
          {WINDOWS.map((w) => (
            <button
              key={w.label}
              type="button"
              onClick={() => setWindowSec(w.seconds)}
              className={
                windowSec === w.seconds
                  ? "px-2 py-0.5 rounded bg-highlight/20 text-highlight border border-highlight/40"
                  : "px-2 py-0.5 rounded border border-panel-border text-neutral-fg/60 hover:text-neutral-fg"
              }
            >
              {w.label}
            </button>
          ))}
        </div>
      </div>
      <div className="flex-1 min-h-0 p-2">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          preserveAspectRatio="none"
          className="w-full h-full"
        >
          {view ? (
            <>
              <line
                x1={MARGIN.left}
                x2={WIDTH - MARGIN.right}
                y1={view.priceBottom}
                y2={view.priceBottom}
                className="stroke-panel-border"
              />
              {/* volume bars */}
              {points.map((p, i) => (
                <rect
                  key={i}
                  x={view.x(p.t) - 1}
                  y={view.yVol(p.q)}
                  width={2}
                  height={HEIGHT - MARGIN.bottom - view.yVol(p.q)}
                  className="fill-neutral-fg/40"
                />
              ))}
              {/* price line */}
              {view.path && (
                <path
                  d={view.path}
                  className="stroke-highlight fill-none"
                  strokeWidth={1.25}
                />
              )}
              {/* y-axis labels */}
              <text
                x={MARGIN.left - 4}
                y={MARGIN.top + 10}
                textAnchor="end"
                className="fill-neutral-fg/60 text-[10px]"
              >
                {view.maxP.toFixed(2)}
              </text>
              <text
                x={MARGIN.left - 4}
                y={view.priceBottom}
                textAnchor="end"
                className="fill-neutral-fg/60 text-[10px]"
              >
                {view.minP.toFixed(2)}
              </text>
            </>
          ) : (
            <text
              x={WIDTH / 2}
              y={HEIGHT / 2}
              textAnchor="middle"
              className="fill-neutral-fg/50 text-xs"
            >
              no trades in window
            </text>
          )}
        </svg>
      </div>
    </div>
  );
}
