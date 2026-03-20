import { describe, it, expect, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useOrderSubmit } from "./useOrderSubmit";
import { ExchangeContext } from "../state/exchangeContextInternal";
import type { ExchangeState } from "../state/exchangeReducer";
import { initialExchangeState } from "../state/exchangeReducer";

function wrap(state: ExchangeState, send: (x: unknown) => void) {
  return ({ children }: { children: React.ReactNode }) => (
    <ExchangeContext.Provider value={{ state, send }}>
      {children}
    </ExchangeContext.Provider>
  );
}

describe("useOrderSubmit", () => {
  it("submits a limit order when connected", () => {
    const send = vi.fn();
    const state: ExchangeState = { ...initialExchangeState, connected: true };
    const { result } = renderHook(() => useOrderSubmit(), { wrapper: wrap(state, send) });
    let out: ReturnType<typeof result.current.submit> = null;
    act(() => {
      out = result.current.submit({
        side: "BUY",
        orderType: "LIMIT",
        price: 100.5,
        quantity: 10,
      });
    });
    expect(out).not.toBeNull();
    expect(send).toHaveBeenCalledTimes(1);
    const sent = send.mock.calls[0][0] as Record<string, unknown>;
    expect(sent.type).toBe("new_order");
    expect(sent.side).toBe("BUY");
    expect(sent.order_type).toBe("LIMIT");
    expect(sent.price).toBe(100.5);
    expect(sent.quantity).toBe(10);
    expect(typeof sent.order_id).toBe("number");
  });

  it("refuses to submit when disconnected", () => {
    const send = vi.fn();
    const state: ExchangeState = { ...initialExchangeState, connected: false };
    const { result } = renderHook(() => useOrderSubmit(), { wrapper: wrap(state, send) });
    let out: ReturnType<typeof result.current.submit> = null;
    act(() => {
      out = result.current.submit({
        side: "BUY",
        orderType: "LIMIT",
        price: 100.0,
        quantity: 1,
      });
    });
    expect(out).toBeNull();
    expect(send).not.toHaveBeenCalled();
  });

  it("market order omits a real price (bridge expects price=0)", () => {
    const send = vi.fn();
    const state: ExchangeState = { ...initialExchangeState, connected: true };
    const { result } = renderHook(() => useOrderSubmit(), { wrapper: wrap(state, send) });
    act(() => {
      result.current.submit({
        side: "SELL",
        orderType: "MARKET",
        quantity: 5,
      });
    });
    const sent = send.mock.calls[0][0] as Record<string, unknown>;
    expect(sent.order_type).toBe("MARKET");
    expect(sent.price).toBe(0);
  });

  it("rejects a limit with missing or negative price", () => {
    const send = vi.fn();
    const state: ExchangeState = { ...initialExchangeState, connected: true };
    const { result } = renderHook(() => useOrderSubmit(), { wrapper: wrap(state, send) });
    act(() => {
      expect(
        result.current.submit({
          side: "BUY",
          orderType: "LIMIT",
          quantity: 1,
        }),
      ).toBeNull();
      expect(
        result.current.submit({
          side: "BUY",
          orderType: "LIMIT",
          price: -1,
          quantity: 1,
        }),
      ).toBeNull();
    });
    expect(send).not.toHaveBeenCalled();
  });

  it("rejects zero or negative quantity", () => {
    const send = vi.fn();
    const state: ExchangeState = { ...initialExchangeState, connected: true };
    const { result } = renderHook(() => useOrderSubmit(), { wrapper: wrap(state, send) });
    act(() => {
      expect(
        result.current.submit({
          side: "BUY",
          orderType: "LIMIT",
          price: 100,
          quantity: 0,
        }),
      ).toBeNull();
    });
    expect(send).not.toHaveBeenCalled();
  });

  it("cancel sends cancel_order", () => {
    const send = vi.fn();
    const state: ExchangeState = { ...initialExchangeState, connected: true };
    const { result } = renderHook(() => useOrderSubmit(), { wrapper: wrap(state, send) });
    act(() => {
      result.current.cancel(123);
    });
    expect(send).toHaveBeenCalledWith({ type: "cancel_order", order_id: 123 });
  });
});
