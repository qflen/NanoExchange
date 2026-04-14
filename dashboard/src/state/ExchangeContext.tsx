import { useCallback, useContext, useMemo, useReducer, useRef } from "react";
import type { ReactNode } from "react";
import {
  exchangeReducer,
  initialExchangeState,
} from "./exchangeReducer";
import { ExchangeContext, type ExchangeContextValue } from "./exchangeContextInternal";
import { useWebSocket } from "../hooks/useWebSocket";
import type { ServerMsg } from "../types";

interface ProviderProps {
  wsUrl: string;
  children: ReactNode;
}

export function ExchangeProvider({ wsUrl, children }: ProviderProps) {
  const [state, dispatch] = useReducer(exchangeReducer, initialExchangeState);

  // useWebSocket owns connection lifecycle and reconnect; it publishes
  // the live socket here so `send` and the simulator's back-pressure
  // check can use the same connection. Earlier this provider opened a
  // second WebSocket of its own — order entry sent on socket A while
  // book updates arrived on socket B, which worked only because the
  // bridge happened to broadcast. One socket per provider, period.
  const sockRef = useRef<WebSocket | null>(null);

  useWebSocket({
    url: wsUrl,
    sockRef,
    onOpen: () => dispatch({ type: "connection/open" }),
    onClose: () => dispatch({ type: "connection/close" }),
    onMessage: (msg: ServerMsg) => {
      if (msg.type === "batch") dispatch({ type: "batch", batch: msg });
      // notice messages are informational; ignore in the reducer.
    },
  });

  const send = useCallback((payload: unknown) => {
    const ws = sockRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
    }
  }, []);

  // Memoise the context value so consumers only re-render when `state`
  // actually changes. Without this, every provider render (including
  // ones triggered by unrelated parent work) produces a new value
  // reference and re-renders every consumer — a silent source of
  // janky input latency under the 60 Hz batch traffic.
  const value: ExchangeContextValue = useMemo(
    () => ({ state, send, sockRef }),
    [state, send],
  );
  return (
    <ExchangeContext.Provider value={value}>
      {children}
    </ExchangeContext.Provider>
  );
}

export function useExchange(): ExchangeContextValue {
  const ctx = useContext(ExchangeContext);
  if (!ctx) throw new Error("useExchange must be used inside ExchangeProvider");
  return ctx;
}
