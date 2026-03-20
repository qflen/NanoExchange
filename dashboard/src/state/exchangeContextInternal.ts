import { createContext, type MutableRefObject } from "react";
import type { ExchangeState } from "./exchangeReducer";

// Exported separately so tests can render a subtree with a synthetic
// context value without spinning up a WebSocket. Production code uses
// the provider/hook in `ExchangeContext.tsx`.
export interface ExchangeContextValue {
  state: ExchangeState;
  send: (payload: unknown) => void;
  // Live handle for code paths (the simulator) that need to inspect
  // socket back-pressure (`bufferedAmount`) before flooding sends.
  // Optional so tests can build a synthetic context without a socket.
  sockRef?: MutableRefObject<WebSocket | null>;
}

export const ExchangeContext = createContext<ExchangeContextValue | null>(null);
