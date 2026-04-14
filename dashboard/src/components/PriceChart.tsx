import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { scaleLinear } from "d3-scale";
import { useExchange } from "../state/ExchangeContext";

// OHLC candles + volume bars. We bucket the trade ring by the selected
// timeframe, compute open/high/low/close/volume per bucket, and draw
// wicks + bodies. Raw SVG + d3-scale: Recharts reconciles arrays per
// tick and the 60 Hz batch traffic makes that too expensive for what
// is a purely visual component.
//
// Layout: candles are fixed-width and grow left-to-right like a broker
// app. The SVG is wider than the viewport when there's overflow; the
// containing div scrolls horizontally and auto-pins to the right so
// the newest candle is always visible.

const HEIGHT = 220;
const SLOT_W = 16;            // horizontal pixels per candle
const BODY_W = 10;            // candle body width
const AXIS_W = 48;            // right-side price axis gutter
const LEFT_PAD = 8;
const MARGIN = { top: 10, bottom: 24 };
const VOL_FRACTION = 0.25;
const MAX_CANDLES = 240;

interface Timeframe {
  label: string;
  seconds: number;
  bucketMs: number;
}

// Each entry is (candle width, visible window). The window caps how far
// back we look; the bucket size decides how many candles fit. We size
// the window generously — dormant buckets won't render (no trades → no
// candle), so "10s" means "show me up to MAX_CANDLES candles of 10s
// each, most recent first".
const TIMEFRAMES: Timeframe[] = [
  { label: "3s", seconds: 3 * MAX_CANDLES, bucketMs: 3_000 },
  { label: "10s", seconds: 10 * MAX_CANDLES, bucketMs: 10_000 },
  { label: "1m", seconds: 60 * MAX_CANDLES, bucketMs: 60_000 },
  { label: "3m", seconds: 180 * MAX_CANDLES, bucketMs: 180_000 },
];

interface Candle {
  t: number; // bucket start, ms
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;
}

export function PriceChart() {
  const { state } = useExchange();
  const [tfIdx, setTfIdx] = useState<number>(0);
  const tf = TIMEFRAMES[tfIdx];

  // Re-memo once a second so empty buckets march forward even when no
  // trades arrive. `nowTick` is in the memo deps — without it, the
  // memo would keep returning the first computation's candle list
  // because `state.trades` hasn't changed.
  const [nowTick, setNowTick] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNowTick(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const candles = useMemo<Candle[]>(() => {
    const trades = state.trades;
    // `rx_ms` is the client-side arrival time stamped in the reducer.
    // The engine's ts_ns is a monotonic counter (not wall-clock), so
    // rx_ms is the only field that aligns with real minutes.
    const now = nowTick;
    const cutoff = now - tf.seconds * 1_000;
    const byBucket = new Map<number, Candle>();

    for (let i = 0; i < trades.length; i++) {
      const tr = trades[i];
      const tMs = tr.rx_ms ?? now;
      if (tMs < cutoff) continue;
      const bucket = Math.floor(tMs / tf.bucketMs) * tf.bucketMs;
      const existing = byBucket.get(bucket);
      if (!existing) {
        byBucket.set(bucket, {
          t: bucket,
          o: tr.price,
          h: tr.price,
          l: tr.price,
          c: tr.price,
          v: tr.quantity,
        });
      } else {
        if (tr.price > existing.h) existing.h = tr.price;
        if (tr.price < existing.l) existing.l = tr.price;
        existing.c = tr.price;
        existing.v += tr.quantity;
      }
    }

    if (byBucket.size === 0) return [];

    // Fill-forward: emit one candle per bucket from the first real
    // candle through "now", inserting carry-forward placeholders
    // (o=h=l=c=prev.c, v=0) for periods with no trades. The chart
    // draws these as a flat 1-pixel tick so the viewer still sees
    // time advancing.
    const sortedKeys = Array.from(byBucket.keys()).sort((a, b) => a - b);
    const startBucket = sortedKeys[0];
    const nowBucket = Math.floor(now / tf.bucketMs) * tf.bucketMs;
    const list: Candle[] = [];
    let prevClose: number | null = null;
    for (let b = startBucket; b <= nowBucket; b += tf.bucketMs) {
      const hit = byBucket.get(b);
      if (hit) {
        list.push(hit);
        prevClose = hit.c;
      } else if (prevClose !== null) {
        list.push({ t: b, o: prevClose, h: prevClose, l: prevClose, c: prevClose, v: 0 });
      }
    }
    return list.slice(-MAX_CANDLES);
  }, [state.trades, tf, nowTick]);

  const chartW = LEFT_PAD + Math.max(1, candles.length) * SLOT_W;
  const totalW = chartW + AXIS_W;

  const view = useMemo(() => {
    if (candles.length === 0) return null;
    const minP = Math.min(...candles.map((c) => c.l));
    const maxP = Math.max(...candles.map((c) => c.h));
    const pad = (maxP - minP) * 0.08 || 0.25;
    const priceBottom =
      HEIGHT - MARGIN.bottom - (HEIGHT - MARGIN.top - MARGIN.bottom) * VOL_FRACTION;
    const y = scaleLinear()
      .domain([minP - pad, maxP + pad])
      .range([priceBottom, MARGIN.top]);
    const maxV = Math.max(1, ...candles.map((c) => c.v));
    const yVol = scaleLinear()
      .domain([0, maxV])
      .range([HEIGHT - MARGIN.bottom, priceBottom + 2]);
    return { y, yVol, minP, maxP, priceBottom };
  }, [candles]);

  const lastClose = candles.length ? candles[candles.length - 1].c : null;

  // Auto-scroll the viewport to the right edge so the newest candle is
  // always visible. Runs synchronously on layout to avoid a visible
  // jump. If the user has scrolled away from the right, leave them
  // there — only auto-pin when they're already near the edge.
  const scrollRef = useRef<HTMLDivElement>(null);
  const pinnedRef = useRef(true);
  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (pinnedRef.current) {
      el.scrollLeft = el.scrollWidth - el.clientWidth;
    }
  }, [chartW]);
  const onScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const maxScroll = el.scrollWidth - el.clientWidth;
    pinnedRef.current = maxScroll - el.scrollLeft < 8;
  };

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-between px-3 py-1.5 border-b border-panel-border text-xs">
        <span className="text-neutral-fg/60 tabular-nums">
          {lastClose !== null ? `last ${lastClose.toFixed(2)}` : "—"}
        </span>
        <div className="flex items-center gap-1">
          {TIMEFRAMES.map((t, i) => (
            <button
              key={t.label}
              type="button"
              onClick={() => setTfIdx(i)}
              className={
                tfIdx === i
                  ? "px-2 py-0.5 rounded bg-highlight/20 text-highlight border border-highlight/40"
                  : "px-2 py-0.5 rounded border border-panel-border text-neutral-fg/60 hover:text-neutral-fg"
              }
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>
      <div
        ref={scrollRef}
        onScroll={onScroll}
        className="flex-1 min-h-0 overflow-x-auto overflow-y-hidden p-2"
      >
        <svg
          width={totalW}
          height="100%"
          viewBox={`0 0 ${totalW} ${HEIGHT}`}
          preserveAspectRatio="none"
          style={{ display: "block", minWidth: totalW }}
        >
          {view ? (
            <>
              <line
                x1={LEFT_PAD}
                x2={chartW}
                y1={view.priceBottom}
                y2={view.priceBottom}
                className="stroke-panel-border"
              />
              {candles.map((c, i) => {
                const cx = LEFT_PAD + SLOT_W * i + SLOT_W / 2;
                const up = c.c >= c.o;
                const cls = up ? "fill-bid-green stroke-bid-green" : "fill-ask-red stroke-ask-red";
                const yHigh = view.y(c.h);
                const yLow = view.y(c.l);
                const yOpen = view.y(c.o);
                const yClose = view.y(c.c);
                // Minimum 2 viewBox units so a near-doji (o ≈ c) or a
                // carry-forward placeholder (o = c = prev) still shows
                // up as a visible tick after the SVG gets scaled down
                // to fit its container — 1px can fall below one
                // physical pixel.
                const bodyTop = Math.min(yOpen, yClose);
                const bodyH = Math.max(2, Math.abs(yClose - yOpen));
                const hasRange = c.h !== c.l;
                const volY = view.yVol(c.v);
                const volH = Math.max(0, HEIGHT - MARGIN.bottom - volY);
                return (
                  <g key={c.t}>
                    {hasRange && (
                      <line
                        x1={cx}
                        x2={cx}
                        y1={yHigh}
                        y2={yLow}
                        className={cls}
                        strokeWidth={1}
                      />
                    )}
                    <rect
                      x={cx - BODY_W / 2}
                      y={bodyTop}
                      width={BODY_W}
                      height={bodyH}
                      className={cls}
                    />
                    {volH > 0 && (
                      <rect
                        x={cx - BODY_W / 2}
                        y={volY}
                        width={BODY_W}
                        height={volH}
                        className={up ? "fill-bid-green/50" : "fill-ask-red/50"}
                      />
                    )}
                  </g>
                );
              })}
              <text
                x={chartW + 4}
                y={MARGIN.top + 8}
                className="fill-neutral-fg/60 text-[10px]"
              >
                {view.maxP.toFixed(2)}
              </text>
              <text
                x={chartW + 4}
                y={view.priceBottom}
                className="fill-neutral-fg/60 text-[10px]"
              >
                {view.minP.toFixed(2)}
              </text>
              {lastClose !== null && (
                <g>
                  <line
                    x1={LEFT_PAD}
                    x2={chartW}
                    y1={view.y(lastClose)}
                    y2={view.y(lastClose)}
                    className="stroke-highlight/40"
                    strokeDasharray="2 3"
                    strokeWidth={1}
                  />
                  <text
                    x={chartW + 4}
                    y={view.y(lastClose) + 3}
                    className="fill-highlight text-[10px] tabular-nums"
                  >
                    {lastClose.toFixed(2)}
                  </text>
                </g>
              )}
            </>
          ) : (
            <text
              x={totalW / 2}
              y={HEIGHT / 2}
              textAnchor="middle"
              className="fill-neutral-fg/50 text-xs"
            >
              waiting…
            </text>
          )}
        </svg>
      </div>
    </div>
  );
}
