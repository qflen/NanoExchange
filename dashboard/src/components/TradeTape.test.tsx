import { describe, it, expect } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { TradeTape } from "./TradeTape";
import { buildState, renderWithState } from "../test-utils";
import type { TradeMsg } from "../types";

function trades(n: number, side: "BUY" | "SELL" = "BUY"): TradeMsg[] {
  return Array.from({ length: n }, (_, i) => ({
    type: "trade",
    seq: i,
    taker_order_id: i,
    maker_order_id: 0,
    taker_side: side,
    price: 100 + i * 0.01,
    quantity: 1 + i,
    ts_ns: 1_700_000_000_000 + i * 1,
  }));
}

describe("TradeTape", () => {
  it("shows a count and renders visible rows newest-first", () => {
    const state = buildState([
      {
        type: "batch",
        batch: {
          type: "batch",
          book_updates: [],
          trades: trades(3, "BUY"),
          snapshots: [],
          execs: [],
        },
      },
    ]);
    renderWithState(state, <TradeTape />);
    expect(screen.getByText(/3 total/i)).toBeInTheDocument();
    // First rendered data row is the newest price.
    expect(screen.getByText("100.0200")).toBeInTheDocument();
  });

  it("scroll-lock: unpins when user scrolls down; jump-to-latest button reappears", () => {
    const state = buildState([
      {
        type: "batch",
        batch: {
          type: "batch",
          book_updates: [],
          trades: trades(10, "SELL"),
          snapshots: [],
          execs: [],
        },
      },
    ]);
    renderWithState(state, <TradeTape />);
    // Initially pinned — no jump-to-latest button.
    expect(screen.queryByText(/jump to latest/i)).not.toBeInTheDocument();
    const scroller = document.querySelector("div.overflow-y-auto") as HTMLDivElement;
    // Simulate the user scrolling away from the top.
    Object.defineProperty(scroller, "scrollTop", { value: 50, writable: true });
    fireEvent.scroll(scroller);
    expect(screen.getByText(/jump to latest/i)).toBeInTheDocument();
  });
});
