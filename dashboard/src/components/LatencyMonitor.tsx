import { useMemo } from "react";
import { useFrameSampler, useFrameMetrics } from "../hooks/useFrameMetrics";

// A 60-sample SVG path of recent frame durations. No animation —
// animating the interpolation between ticks would hide the very
// spikes the panel exists to surface. A spike stays on screen for
// 60 seconds, then scrolls out.

const WIDTH = 260;
const HEIGHT = 56;
const TARGET_MS = 16.67;

export function LatencyMonitor() {
  useFrameSampler();
  const { lastFrameMs, avg60Ms, p99Ms, buffer } = useFrameMetrics();

  const path = useMemo(() => {
    if (buffer.length === 0) return "";
    const maxY = Math.max(TARGET_MS * 2, Math.max(...buffer));
    const stepX = WIDTH / (buffer.length - 1);
    let d = "";
    for (let i = 0; i < buffer.length; i++) {
      const x = i * stepX;
      const y = HEIGHT - (buffer[i] / maxY) * HEIGHT;
      d += i === 0 ? `M${x.toFixed(1)},${y.toFixed(1)}` : `L${x.toFixed(1)},${y.toFixed(1)}`;
    }
    return d;
  }, [buffer]);

  const budgetY = HEIGHT - (TARGET_MS / Math.max(TARGET_MS * 2, p99Ms || TARGET_MS * 2)) * HEIGHT;

  return (
    <div className="flex flex-col h-full bg-panel-bg border border-panel-border rounded">
      <div className="flex items-center justify-end gap-3 px-3 py-1.5 border-b border-panel-border text-xs text-neutral-fg/60 tabular-nums whitespace-nowrap overflow-hidden">
        <span>
          last <span className="text-neutral-fg">{lastFrameMs.toFixed(1)}</span> ms
        </span>
        <span>
          avg <span className="text-neutral-fg">{avg60Ms.toFixed(1)}</span>
        </span>
        <span>
          p99{" "}
          <span className={p99Ms > TARGET_MS * 2 ? "text-ask-red" : "text-neutral-fg"}>
            {p99Ms.toFixed(1)}
          </span>
        </span>
      </div>
      <div className="flex-1 min-h-0 p-2">
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          preserveAspectRatio="none"
          className="w-full h-full"
          role="img"
          aria-label="frame duration sparkline"
        >
          <line
            x1={0}
            x2={WIDTH}
            y1={budgetY}
            y2={budgetY}
            className="stroke-highlight/60"
            strokeDasharray="2 2"
          />
          {path && (
            <path
              d={path}
              fill="none"
              className="stroke-bid-green"
              strokeWidth={1.25}
            />
          )}
        </svg>
      </div>
    </div>
  );
}
