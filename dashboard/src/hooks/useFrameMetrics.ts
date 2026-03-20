import { useEffect, useRef, useSyncExternalStore } from "react";

/**
 * Frame-level metrics for the dashboard.
 *
 * The sparkline in `LatencyMonitor` needs a rolling view of the time
 * between consecutive rAF ticks — the browser's actual achieved
 * frame cadence, not the ideal 16.67 ms. Under load, a long reducer
 * update or a D3 redraw will show up here as a spike even before the
 * user notices jank.
 *
 * We expose a synchronous snapshot via `useSyncExternalStore` so the
 * sparkline subscribes once and re-renders only when we tell it to
 * (every second, on a coarse interval — a full 60-point redraw per
 * animation frame would defeat the point).
 */

const SAMPLES = 60; // one second of rAF at 60 Hz → 60 samples

type Listener = () => void;

const frameDurations: number[] = new Array(SAMPLES).fill(0);
let frameDurationIdx = 0;
const listeners = new Set<Listener>();

export interface FrameSample {
  lastFrameMs: number;
  avg60Ms: number;
  p99Ms: number;
  buffer: readonly number[];
}

let cached: FrameSample | null = null;

function snapshot(): FrameSample {
  if (cached) return cached;
  let sum = 0;
  let worst = 0;
  for (const v of frameDurations) {
    sum += v;
    if (v > worst) worst = v;
  }
  const avg = sum / SAMPLES;
  // Cheap p99: take the max of a 60-sample window. Valid because we
  // sample every frame and the histogram is typically bimodal
  // (normal frames + occasional long task). Replaces a real quantile
  // with something that is defensibly close for a 60-sample window.
  cached = {
    lastFrameMs: frameDurations[(frameDurationIdx - 1 + SAMPLES) % SAMPLES],
    avg60Ms: avg,
    p99Ms: worst,
    buffer: frameDurations,
  };
  return cached;
}

function subscribe(listener: Listener) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function recordFrame(durationMs: number): void {
  frameDurations[frameDurationIdx] = durationMs;
  frameDurationIdx = (frameDurationIdx + 1) % SAMPLES;
  cached = null;
}

export function useFrameSampler(): void {
  // Spins up a single rAF loop measuring inter-frame deltas. Notifies
  // listeners coarsely (once per second) so the UI only redraws when
  // the sparkline has meaningful new data.
  const lastRef = useRef<number | null>(null);
  useEffect(() => {
    let rafId = 0;
    let notifyTimer: ReturnType<typeof setInterval> | null = null;
    const tick = (now: number) => {
      if (lastRef.current !== null) {
        recordFrame(now - lastRef.current);
      }
      lastRef.current = now;
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);
    notifyTimer = setInterval(() => {
      for (const l of listeners) l();
    }, 1_000);
    return () => {
      cancelAnimationFrame(rafId);
      if (notifyTimer !== null) clearInterval(notifyTimer);
    };
  }, []);
}

export function useFrameMetrics(): FrameSample {
  return useSyncExternalStore(subscribe, snapshot, snapshot);
}
