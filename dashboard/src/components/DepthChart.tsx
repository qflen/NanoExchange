import { useEffect, useMemo, useRef } from "react";
import { scaleLinear } from "d3-scale";
import { area, curveStepAfter, curveStepBefore } from "d3-shape";
import { useExchange } from "../state/ExchangeContext";
import {
  selectAskRows,
  selectBidRows,
  selectBestBidAsk,
} from "../state/exchangeReducer";

// D3 is used purely as a "physics engine" — compute scales, build path
// strings. React owns the DOM: the path `d` is set declaratively, not
// via d3-selection. This sidesteps the StrictMode-duplicate-selection
// trap and keeps the render fully diffable.
const WIDTH = 600;
const HEIGHT = 220;
const MARGIN = { top: 10, right: 12, bottom: 24, left: 12 };

interface Cumulative {
  price: number;
  cumQty: number;
}

export function DepthChart() {
  const { state } = useExchange();

  const { bidsCum, asksCum, mid } = useMemo(() => {
    const bids = selectBidRows(state.bids);
    const asks = selectAskRows(state.asks);
    const { mid: midPrice } = selectBestBidAsk(state.bids, state.asks);
    let acc = 0;
    const bidsCum: Cumulative[] = bids.map((r) => {
      acc += r.quantity;
      return { price: r.price, cumQty: acc };
    });
    acc = 0;
    const asksCum: Cumulative[] = asks.map((r) => {
      acc += r.quantity;
      return { price: r.price, cumQty: acc };
    });
    return { bidsCum, asksCum, mid: midPrice };
  }, [state.bids, state.asks]);

  const { bidPath, askPath, x, y, ticks } = useMemo(() => {
    if (bidsCum.length === 0 && asksCum.length === 0) {
      return {
        bidPath: null,
        askPath: null,
        x: null,
        y: null,
        ticks: [] as number[],
      };
    }
    // Loops, not Math.min/max(...spread): a deep book with tens of
    // thousands of cumulative levels would otherwise exceed the JS
    // engine's argument-length limit on spread and silently return the
    // wrong domain — same crash surface as selectBestBidAsk.
    let minP = Infinity;
    let maxP = -Infinity;
    let maxQ = 0;
    for (const c of bidsCum) {
      if (c.price < minP) minP = c.price;
      if (c.price > maxP) maxP = c.price;
      if (c.cumQty > maxQ) maxQ = c.cumQty;
    }
    for (const c of asksCum) {
      if (c.price < minP) minP = c.price;
      if (c.price > maxP) maxP = c.price;
      if (c.cumQty > maxQ) maxQ = c.cumQty;
    }
    const padding = (maxP - minP) * 0.05 || 0.5;
    const xScale = scaleLinear()
      .domain([minP - padding, maxP + padding])
      .range([MARGIN.left, WIDTH - MARGIN.right]);
    const yScale = scaleLinear()
      .domain([0, maxQ * 1.1 || 1])
      .range([HEIGHT - MARGIN.bottom, MARGIN.top]);

    const bidArea = area<Cumulative>()
      .x((d) => xScale(d.price))
      .y0(HEIGHT - MARGIN.bottom)
      .y1((d) => yScale(d.cumQty))
      .curve(curveStepAfter);
    const askArea = area<Cumulative>()
      .x((d) => xScale(d.price))
      .y0(HEIGHT - MARGIN.bottom)
      .y1((d) => yScale(d.cumQty))
      .curve(curveStepBefore);

    return {
      bidPath: bidArea(bidsCum.slice().reverse()),
      askPath: askArea(asksCum),
      x: xScale,
      y: yScale,
      ticks: xScale.ticks(5),
    };
  }, [bidsCum, asksCum]);

  const svgRef = useRef<SVGSVGElement>(null);

  // Intentional no-op effect: memoising here so any future d3 axis
  // call can hook in without risking duplicate SVG children under
  // StrictMode.
  useEffect(() => {
    svgRef.current?.setAttribute("viewBox", `0 0 ${WIDTH} ${HEIGHT}`);
  }, []);

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-end px-3 py-1.5 border-b border-panel-border text-xs text-neutral-fg/60">
        {mid !== null ? <>mid {mid.toFixed(4)}</> : "—"}
      </div>
      <div className="flex-1 min-h-0 p-2">
        <svg
          ref={svgRef}
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          preserveAspectRatio="none"
          className="w-full h-full"
        >
          {bidPath && (
            <path
              d={bidPath}
              className="fill-bid-green/30 stroke-bid-green"
              strokeWidth={1}
            />
          )}
          {askPath && (
            <path
              d={askPath}
              className="fill-ask-red/30 stroke-ask-red"
              strokeWidth={1}
            />
          )}
          {x !== null && y !== null && mid !== null && (
            <line
              x1={x(mid)}
              x2={x(mid)}
              y1={MARGIN.top}
              y2={HEIGHT - MARGIN.bottom}
              className="stroke-highlight/60"
              strokeDasharray="2 2"
            />
          )}
          {x !== null &&
            ticks.map((t) => (
              <text
                key={t}
                x={x(t)}
                y={HEIGHT - 6}
                textAnchor="middle"
                className="fill-neutral-fg/60 text-[10px]"
              >
                {t.toFixed(2)}
              </text>
            ))}
        </svg>
      </div>
    </div>
  );
}
