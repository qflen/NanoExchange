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
// How long the return traffic must be fully quiet before we consider
// the simulation truly finished. Activity = bytes still buffered on
// the WS OR new exec reports / trades hitting the reducer. A 500 ms
// lull is conservative: the bridge flushes a batch every ~16 ms, so
// during a live run the reducer sees churn many times inside this
// window. Only a real silence trips the timer.
const DRAIN_QUIET_MS = 500;
// Absolute safety cap — if the engine wedges or packets are lost we
// still want the Simulate button to come back eventually. 30 s is far
// longer than a healthy 100 k run.
const DRAIN_MAX_MS = 30_000;

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
  // "sending" while the tick loop is pushing orders onto the socket;
  // "draining" after the last send, while we wait for exec reports
  // and trades to stop streaming back. Both phases keep the button
  // labelled Cancel so the user can abort at any time but can't kick
  // off a second run on top of the first. Also exposed so the UI can
  // show an accurate status string.
  phase: "idle" | "sending" | "draining";
}

interface SimulatorAPI {
  start: (count: number) => void;
  cancel: () => void;
  progress: SimProgress;
}

const INITIAL: SimProgress = { running: false, total: 0, sent: 0, phase: "idle" };

export function useOrderSimulator(): SimulatorAPI {
  const { state, send, sockRef } = useExchange();
  const stateRef = useRef(state);
  stateRef.current = state;

  const [progress, setProgress] = useState<SimProgress>(INITIAL);

  const cancelRef = useRef(false);
  // `busy` covers sending AND draining. Only the drain watchdog
  // (which owns the transition to idle) is allowed to flip it back to
  // false — `cancel()` must not, because a rapid Cancel→Simulate would
  // otherwise start a new tick while the previous rAF is still
  // queued, and two concurrent loops would both push orders on the
  // same socket.
  const busyRef = useRef(false);
  const idRef = useRef<number>(Date.now() % 900_000_000 + 100_000_000);
  const drainTimerRef = useRef<number | null>(null);

  const cancel = useCallback(() => {
    cancelRef.current = true;
  }, []);

  const start = useCallback(
    (count: number) => {
      // Guard against re-entry. Trips if:
      //   1. A tick is still sending (large runs take seconds).
      //   2. We're in the drain-watch window after the last send.
      //   3. A burst of rapid clicks lands before React flips
      //      `progress.running` to true.
      if (busyRef.current) return;
      busyRef.current = true;
      const total = Math.max(1, Math.min(SIM_MAX_ORDERS, Math.floor(count)));
      cancelRef.current = false;
      setProgress({ running: true, total, sent: 0, phase: "sending" });
      const rng = makeRng((Date.now() ^ total) >>> 0);
      let sent = 0;

      // Drain watchdog. Polls every animation frame after the last
      // send, watching two signals:
      //
      //   - `ws.bufferedAmount` > 0  → bytes haven't left the browser
      //   - `state.execs.length` or `state.trades.length` changed
      //     this frame → the reducer is still receiving backlog
      //
      // The run is "really" finished only after both signals stay
      // quiet for DRAIN_QUIET_MS. Cancel does NOT short-circuit this
      // — it only stops sending more orders; the already-enqueued
      // traffic still has to clear before the Simulate button re-arms.
      // Without that rule, rapid Cancel→Simulate cycles let the user
      // spam one-order runs and flood the reducer faster than it can
      // render. DRAIN_MAX_MS caps the worst case so a wedged engine
      // can't strand the button forever.
      const beginDrain = () => {
        setProgress((p) => ({ ...p, phase: "draining" }));
        const startT = performance.now();
        let lastActivityT = startT;
        let lastExecCount = stateRef.current.execs.length;
        let lastTradeCount = stateRef.current.trades.length;

        const poll = () => {
          const now = performance.now();
          const ws = sockRef?.current;
          const bufBusy = !!(ws && ws.bufferedAmount > 0);
          const execN = stateRef.current.execs.length;
          const tradeN = stateRef.current.trades.length;
          const reducerBusy = execN !== lastExecCount || tradeN !== lastTradeCount;
          lastExecCount = execN;
          lastTradeCount = tradeN;
          if (bufBusy || reducerBusy) {
            lastActivityT = now;
          }
          if (now - lastActivityT >= DRAIN_QUIET_MS || now - startT >= DRAIN_MAX_MS) {
            end();
            return;
          }
          drainTimerRef.current = requestAnimationFrame(poll);
        };
        drainTimerRef.current = requestAnimationFrame(poll);
      };

      const end = () => {
        if (drainTimerRef.current !== null) {
          cancelAnimationFrame(drainTimerRef.current);
          drainTimerRef.current = null;
        }
        cancelRef.current = false;
        busyRef.current = false;
        setProgress((p) => ({ ...p, running: false, phase: "idle" }));
      };

      const tick = () => {
        if (cancelRef.current) {
          // Cancel during the send phase: stop sending more orders,
          // but still hand off to the drain watchdog — already-queued
          // bytes and the engine's in-flight reports have to clear
          // before we can accept another click.
          beginDrain();
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
          // spread to produce trades and exec reports). Aggressive
          // prices anchor on the live best bid/ask so they're
          // guaranteed to cross; passive prices drift around mid so
          // the book thickens on both sides.
          const aggressive = rng() < 0.2;
          const buy = rng() < 0.5;
          const drift = rng() * 1.5;
          let price: number;
          if (aggressive) {
            if (buy) {
              const ref = bestAsk ?? anchor + halfSpread;
              price = +(ref + rng() * 0.1).toFixed(2);
            } else {
              const ref = bestBid ?? anchor - halfSpread;
              price = +(ref - rng() * 0.1).toFixed(2);
            }
          } else {
            price = buy
              ? +(anchor - halfSpread - drift).toFixed(2)
              : +(anchor + halfSpread + drift).toFixed(2);
          }
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
            timestamp_nanos: Date.now() * 1_000_000,
          });
          sent++;
        }
        setProgress({ running: true, total, sent, phase: "sending" });
        if (sent >= total) {
          beginDrain();
          return;
        }
        requestAnimationFrame(tick);
      };

      requestAnimationFrame(tick);
    },
    [send, sockRef],
  );

  // If the component unmounts mid-run, stop generating and cancel any
  // in-flight drain poll.
  useEffect(() => () => {
    cancelRef.current = true;
    if (drainTimerRef.current !== null) {
      cancelAnimationFrame(drainTimerRef.current);
      drainTimerRef.current = null;
    }
  }, []);

  return { start, cancel, progress };
}
