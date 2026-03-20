import { render, type RenderResult } from "@testing-library/react";
import {
  exchangeReducer,
  initialExchangeState,
  type ExchangeState,
  type ExchangeAction,
} from "./state/exchangeReducer";
import { ExchangeContext } from "./state/exchangeContextInternal";
import type { ReactNode } from "react";

// Test harness that skips the real WS and drives the reducer
// directly. Each test builds an `ExchangeState` via a list of
// batches it wants applied, then hands it to `renderWithState`.

export function buildState(actions: ExchangeAction[]): ExchangeState {
  return actions.reduce(exchangeReducer, initialExchangeState);
}

export function renderWithState(
  state: ExchangeState,
  ui: ReactNode,
  send: (payload: unknown) => void = () => {},
): RenderResult {
  return render(
    <ExchangeContext.Provider value={{ state, send }}>
      {ui}
    </ExchangeContext.Provider>,
  );
}
