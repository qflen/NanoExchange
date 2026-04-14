import { useCallback, useRef } from "react";
import { useExchange } from "../state/ExchangeContext";

export type Side = "BUY" | "SELL";
export type OrderType = "LIMIT" | "MARKET" | "IOC" | "FOK";

export interface OrderDraft {
  side: Side;
  orderType: OrderType;
  price?: number;
  quantity: number;
}

export interface SubmittedOrder {
  orderId: number;
  side: Side;
  orderType: OrderType;
  price: number;
  quantity: number;
}

/**
 * Per-session ascending order-id counter.
 *
 * The gateway accepts any non-zero client-supplied order id; the only
 * hard requirement is uniqueness within a session. We seed with the
 * current millisecond timestamp so a page reload can't recycle ids
 * from a previous load within the same session window.
 */
function nextOrderIdFactory(): () => number {
  let counter = Date.now() % 1_000_000_000;
  return () => {
    counter += 1;
    return counter;
  };
}

export function useOrderSubmit() {
  const { state, send } = useExchange();
  const nextIdRef = useRef<() => number>();
  if (!nextIdRef.current) nextIdRef.current = nextOrderIdFactory();

  const submit = useCallback(
    (draft: OrderDraft): SubmittedOrder | null => {
      if (!state.connected) return null;
      if (draft.orderType !== "MARKET" && (draft.price === undefined || draft.price <= 0))
        return null;
      if (draft.quantity <= 0) return null;
      const orderId = nextIdRef.current!();
      const payload: Record<string, unknown> = {
        type: "new_order",
        order_id: orderId,
        side: draft.side,
        order_type: draft.orderType,
        quantity: draft.quantity,
        // Stamp wall-clock so trade timestamps (engine copies the
        // taker's ts) drive the price chart's x-axis instead of 0.
        timestamp_nanos: Date.now() * 1_000_000,
      };
      if (draft.orderType !== "MARKET") {
        payload.price = draft.price;
      } else {
        payload.price = 0;
      }
      send(payload);
      return {
        orderId,
        side: draft.side,
        orderType: draft.orderType,
        price: draft.orderType === "MARKET" ? 0 : (draft.price ?? 0),
        quantity: draft.quantity,
      };
    },
    [send, state.connected],
  );

  const cancel = useCallback(
    (orderId: number) => {
      if (!state.connected) return;
      send({ type: "cancel_order", order_id: orderId });
    },
    [send, state.connected],
  );

  return { submit, cancel };
}
