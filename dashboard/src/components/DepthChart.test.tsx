import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { DepthChart } from "./DepthChart";
import { buildState, renderWithState } from "../test-utils";

describe("DepthChart", () => {
  it("renders two path elements when both sides are populated", () => {
    const state = buildState([
      {
        type: "batch",
        batch: {
          type: "batch",
          book_updates: [
            { type: "book_update", seq: 1, side: "BUY", price: 99.5, quantity: 10, order_count: 1 },
            { type: "book_update", seq: 2, side: "BUY", price: 99.0, quantity: 5, order_count: 1 },
            { type: "book_update", seq: 3, side: "SELL", price: 100.5, quantity: 8, order_count: 1 },
            { type: "book_update", seq: 4, side: "SELL", price: 101.0, quantity: 4, order_count: 1 },
          ],
          trades: [],
          snapshots: [],
          execs: [],
        },
      },
    ]);
    const { container } = renderWithState(state, <DepthChart />);
    expect(screen.getByText(/mid 100\.0000/)).toBeInTheDocument();
    const paths = container.querySelectorAll("svg path");
    // Two areas (bid + ask). Mid line is a <line>, not <path>.
    expect(paths.length).toBe(2);
  });

  it("shows an em-dash when the book is empty", () => {
    renderWithState(buildState([]), <DepthChart />);
    // Heading + placeholder in the top bar.
    expect(screen.getByText(/Depth/i)).toBeInTheDocument();
    expect(screen.getAllByText(/—/).length).toBeGreaterThan(0);
  });
});
