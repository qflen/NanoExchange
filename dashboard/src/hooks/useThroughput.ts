import { useEffect, useRef, useState } from "react";
import { useExchange } from "../state/ExchangeContext";

/**
 * Rolling message-throughput counters.
 *
 * Watches the reducer's `lastSeq`, `trades.length`, and `execs.length`
 * and converts deltas into per-second rates using a one-second
 * interval. A heavier implementation could track each batch arrival
 * timestamp for a finer histogram; at this scale the 1 Hz aggregate
 * is enough for the metrics panel and keeps the hook cheap.
 */
export interface Throughput {
  bookUpdatesPerSec: number;
  tradesPerSec: number;
  execsPerSec: number;
  totalBookUpdates: number;
  totalTrades: number;
  totalExecs: number;
}

export function useThroughput(): Throughput {
  const { state } = useExchange();
  const [snap, setSnap] = useState<Throughput>({
    bookUpdatesPerSec: 0,
    tradesPerSec: 0,
    execsPerSec: 0,
    totalBookUpdates: 0,
    totalTrades: 0,
    totalExecs: 0,
  });

  // Cumulative counters read out of the reducer: reducer tracks
  // lastSeq which ticks once per inbound feed message. Trades and
  // execs are append-only in the buffer but trades has a fixed cap,
  // so we separately count incoming arrivals by noticing when the
  // length hits the cap.
  const lastSeqRef = useRef(0);
  const cumTradesRef = useRef(0);
  const cumExecsRef = useRef(0);
  const prevTradesLenRef = useRef(0);
  const prevExecsLenRef = useRef(0);

  // Update cumulative counters on every render — the ref writes are
  // synchronous and avoid a stale snapshot when the interval fires.
  const seq = state.lastSeq;
  if (seq > lastSeqRef.current) {
    lastSeqRef.current = seq;
  }
  const tl = state.trades.length;
  const td = tl - prevTradesLenRef.current;
  if (td > 0) cumTradesRef.current += td;
  prevTradesLenRef.current = tl;
  const el = state.execs.length;
  const ed = el - prevExecsLenRef.current;
  if (ed > 0) cumExecsRef.current += ed;
  prevExecsLenRef.current = el;

  useEffect(() => {
    const lastSample = {
      seq: lastSeqRef.current,
      trades: cumTradesRef.current,
      execs: cumExecsRef.current,
      time: Date.now(),
    };
    const id = setInterval(() => {
      const now = Date.now();
      const elapsed = (now - lastSample.time) / 1_000;
      if (elapsed <= 0) return;
      const newSnap: Throughput = {
        bookUpdatesPerSec: (lastSeqRef.current - lastSample.seq) / elapsed,
        tradesPerSec: (cumTradesRef.current - lastSample.trades) / elapsed,
        execsPerSec: (cumExecsRef.current - lastSample.execs) / elapsed,
        totalBookUpdates: lastSeqRef.current,
        totalTrades: cumTradesRef.current,
        totalExecs: cumExecsRef.current,
      };
      setSnap(newSnap);
      lastSample.seq = lastSeqRef.current;
      lastSample.trades = cumTradesRef.current;
      lastSample.execs = cumExecsRef.current;
      lastSample.time = now;
    }, 1_000);
    return () => clearInterval(id);
  }, []);

  return snap;
}
