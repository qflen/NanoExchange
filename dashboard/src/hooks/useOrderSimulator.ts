import { useCallback, useEffect, useRef, useState } from "react";
import { useExchange } from "../state/ExchangeContext";
import { selectBestBidAsk } from "../state/exchangeReducer";

export const SIM_MAX_ORDERS = 100_000;

// Per-frame send budget. The matching engine itself can absorb millions
// of orders per second, but the WebSocket send + JSON.stringify path on
// the browser side is the real bottleneck. 200 orders / frame ≈ 12 k/s
// at 60 fps which keeps the main thread responsive while still draining
// 100 k orders in under 9 seconds. We back off further if the socket's
// `bufferedAmount` grows — that signals the network or the bridge is
// not keeping up and adding more would just bloat memory.
const PER_FRAME_BUDGET = 200;
const BACKPRESSURE_BYTES = 1_000_000;

interface RNG {
  (): number;
}

function makeRng(seed: number): RNG {
  // Mulberry32 — small, fast, deterministic so failures repro.
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface SimProgress {
  running: boolean;
  total: number;
  sent: number;
}

interface SimulatorAPI {
  start: (count: number) => void;
  cancel: () => void;
  progress: SimProgress;
}

export function useOrderSimulator(): SimulatorAPI {
  const { state, send, sockRef } = useExchange();
  const stateRef = useRef(state);
  stateRef.current = state;

  const [progress, setProgress] = useState<SimProgress>({
    running: false,
    total: 0,
    sent: 0,
  });
  const cancelRef = useRef(false);
  const idRef = useRef<number>(Date.now() % 900_000_000 + 100_000_000);

  const cancel = useCallback(() => {
    cancelRef.current = true;
  }, []);

  const start = useCallback(
    (count: number) => {
      const total = Math.max(1, Math.min(SIM_MAX_ORDERS, Math.floor(count)));
      cancelRef.current = false;
      setProgress({ running: true, total, sent: 0 });
      const rng = makeRng((Date.now() ^ total) >>> 0);
      let sent = 0;

      const tick = () => {
        if (cancelRef.current) {
          setProgress((p) => ({ ...p, running: false }));
          return;
        }
        // Honour back-pressure: if the WS hasn't drained, wait a frame.
        const ws = sockRef?.current;
        if (ws && ws.bufferedAmount > BACKPRESSURE_BYTES) {
          requestAnimationFrame(tick);
          return;
        }

        const { mid, bestBid, bestAsk } = selectBestBidAsk(
          stateRef.current.bids,
          stateRef.current.asks,
        );
        // Anchor synthetic prices around the live mid when present.
        // Cold-start (empty book) seeds at 100.00 so the dashboard has
        // something to render before any feed traffic.
        const anchor = mid ?? 100;
        const halfSpread =
          bestBid !== null && bestAsk !== null
            ? Math.max(0.01, (bestAsk - bestBid) / 2)
            : 0.05;

        const remaining = total - sent;
        const batch = Math.min(PER_FRAME_BUDGET, remaining);
        for (let i = 0; i < batch; i++) {
          // 80% passive (rest in the book), 20% aggressive (cross the
          // spread to produce trades and exec reports).
          const aggressive = rng() < 0.2;
          const buy = rng() < 0.5;
          const drift = rng() * 1.5;
          const price = aggressive
            ? buy
              ? +(anchor + halfSpread + rng() * 0.3).toFixed(2)
              : +(anchor - halfSpread - rng() * 0.3).toFixed(2)
            : buy
              ? +(anchor - halfSpread - drift).toFixed(2)
              : +(anchor + halfSpread + drift).toFixed(2);
          if (price <= 0) continue;
          const quantity = 1 + Math.floor(rng() * 50);
          idRef.current += 1;
          send({
            type: "new_order",
            order_id: idRef.current,
            side: buy ? "BUY" : "SELL",
            order_type: "LIMIT",
            price,
            quantity,
          });
          sent++;
        }
        setProgress({ running: sent < total, total, sent });
        if (sent >= total) {
          cancelRef.current = false;
          return;
        }
        requestAnimationFrame(tick);
      };

      requestAnimationFrame(tick);
    },
    [send, sockRef],
  );

  // If the component unmounts mid-run, stop generating.
  useEffect(() => () => {
    cancelRef.current = true;
  }, []);

  return { start, cancel, progress };
}
