import { createContext } from "react";
import type { ExchangeState } from "./exchangeReducer";

// Exported separately so tests can render a subtree with a synthetic
// context value without spinning up a WebSocket. Production code uses
// the provider/hook in `ExchangeContext.tsx`.
export interface ExchangeContextValue {
  state: ExchangeState;
  send: (payload: unknown) => void;
}

export const ExchangeContext = createContext<ExchangeContextValue | null>(null);
