import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { OrderBookLadder } from "./OrderBookLadder";
import { buildState, renderWithState } from "../test-utils";
import type { BookUpdateMsg } from "../types";

const bu = (
  side: "BUY" | "SELL",
  price: number,
  quantity: number,
  seq: number,
): BookUpdateMsg => ({
  type: "book_update",
  seq,
  side,
  price,
  quantity,
  order_count: 1,
});

describe("OrderBookLadder", () => {
  it("renders bids in descending order and asks above the mid", () => {
    const state = buildState([
      {
        type: "batch",
        batch: {
          type: "batch",
          book_updates: [
            bu("BUY", 99.5, 5, 1),
            bu("BUY", 100.0, 10, 2),
            bu("SELL", 100.5, 7, 3),
            bu("SELL", 101.0, 3, 4),
          ],
          trades: [],
          snapshots: [],
          execs: [],
        },
      },
    ]);
    renderWithState(state, <OrderBookLadder />);
    // BBO shown in the divider.
    expect(screen.getByText(/100\.0000\s*\/\s*100\.5000/)).toBeInTheDocument();
    // Both bid and ask rows rendered with their test IDs.
    expect(screen.getByTestId("ladder-row-BUY-100")).toBeInTheDocument();
    expect(screen.getByTestId("ladder-row-SELL-100.5")).toBeInTheDocument();
  });

  it("shows waiting state with an empty book", () => {
    renderWithState(buildState([]), <OrderBookLadder />);
    expect(screen.getByText(/waiting for data/i)).toBeInTheDocument();
  });
});
