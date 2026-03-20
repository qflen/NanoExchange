import { useContext, useReducer, useEffect, useRef } from "react";
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

  // Socket handle kept in a ref — the provider owns sending; children
  // post via the context's `send`.
  const sockRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    const ws = new WebSocket(wsUrl);
    sockRef.current = ws;
    return () => ws.close();
  }, [wsUrl]);

  useWebSocket({
    url: wsUrl,
    onOpen: () => dispatch({ type: "connection/open" }),
    onClose: () => dispatch({ type: "connection/close" }),
    onMessage: (msg: ServerMsg) => {
      if (msg.type === "batch") dispatch({ type: "batch", batch: msg });
      // notice messages are informational; ignore in the reducer.
    },
  });

  const send = (payload: unknown) => {
    const ws = sockRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
    }
  };

  const value: ExchangeContextValue = { state, send };
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
