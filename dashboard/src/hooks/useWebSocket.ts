import { useEffect, useRef } from "react";
import type { ServerMsg } from "../types";

export interface WebSocketHookOptions {
  url: string;
  onMessage: (msg: ServerMsg) => void;
  onOpen?: () => void;
  onClose?: () => void;
}

/**
 * Single WebSocket with **rAF-batched** dispatch.
 *
 * Incoming messages land in an inbox ref. A requestAnimationFrame loop
 * drains the inbox once per frame and calls `onMessage` for each one.
 * This decouples socket arrival from render: even at 10_000 msg/s the
 * consumer is only driven at the browser's refresh rate (≤60 Hz).
 *
 * `onMessage` is tracked via a ref so callers can close over reducer
 * state without triggering reconnects.
 */
export function useWebSocket(options: WebSocketHookOptions): void {
  const { url } = options;
  const onMessageRef = useRef(options.onMessage);
  const onOpenRef = useRef(options.onOpen);
  const onCloseRef = useRef(options.onClose);

  useEffect(() => {
    onMessageRef.current = options.onMessage;
    onOpenRef.current = options.onOpen;
    onCloseRef.current = options.onClose;
  });

  useEffect(() => {
    let cancelled = false;
    let ws: WebSocket | null = null;
    let rafId: number | null = null;
    const inbox: ServerMsg[] = [];
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    const drain = () => {
      if (cancelled) return;
      if (inbox.length > 0) {
        // Drain in insertion order. Slice to avoid re-entrancy issues
        // if a dispatch synchronously enqueues more messages.
        const batch = inbox.splice(0, inbox.length);
        for (const msg of batch) {
          onMessageRef.current(msg);
        }
      }
      rafId = requestAnimationFrame(drain);
    };

    const connect = () => {
      if (cancelled) return;
      ws = new WebSocket(url);
      ws.onopen = () => onOpenRef.current?.();
      ws.onmessage = (ev) => {
        // Never dispatch inside onmessage. Enqueue for rAF drain.
        try {
          inbox.push(JSON.parse(ev.data as string) as ServerMsg);
        } catch {
          // Malformed JSON — drop silently; bridge controls the format.
        }
      };
      ws.onclose = () => {
        onCloseRef.current?.();
        if (cancelled) return;
        // Exponential-free backoff is fine here: the bridge restarts
        // fast, and a 1s retry gives humans time to notice.
        reconnectTimer = setTimeout(connect, 1_000);
      };
      ws.onerror = () => ws?.close();
    };

    connect();
    rafId = requestAnimationFrame(drain);

    return () => {
      cancelled = true;
      if (rafId !== null) cancelAnimationFrame(rafId);
      if (reconnectTimer !== null) clearTimeout(reconnectTimer);
      if (ws) {
        ws.onopen = null;
        ws.onmessage = null;
        ws.onclose = null;
        ws.onerror = null;
        ws.close();
      }
    };
  }, [url]);
}
