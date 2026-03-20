import { describe, it, expect, vi } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { OrderEntry } from "./OrderEntry";
import { buildState, renderWithState } from "../test-utils";

describe("OrderEntry", () => {
  it("submit is disabled until price and quantity are valid", () => {
    const state = { ...buildState([]), connected: true };
    renderWithState(state, <OrderEntry />);
    const submit = screen.getByRole("button", { name: /submit/i });
    expect(submit).toBeDisabled();

    const priceInput = screen.getByPlaceholderText(/0\.00/i);
    fireEvent.change(priceInput, { target: { value: "100.25" } });
    expect(submit).toBeDisabled();

    const qty = screen.getByPlaceholderText(/^0$/);
    fireEvent.change(qty, { target: { value: "10" } });
    expect(submit).not.toBeDisabled();
  });

  it("MARKET disables the price input", () => {
    const state = { ...buildState([]), connected: true };
    renderWithState(state, <OrderEntry />);
    const marketBtn = screen.getByRole("button", { name: /^MARKET$/ });
    fireEvent.click(marketBtn);
    const priceInput = screen.getByPlaceholderText(/market/i);
    expect(priceInput).toBeDisabled();
  });

  it("submits a new_order payload without price for MARKET", () => {
    const send = vi.fn();
    const state = { ...buildState([]), connected: true };
    renderWithState(state, <OrderEntry />, send);
    fireEvent.click(screen.getByRole("button", { name: /^MARKET$/ }));
    fireEvent.change(screen.getByPlaceholderText(/^0$/), {
      target: { value: "5" },
    });
    fireEvent.click(screen.getByRole("button", { name: /submit/i }));
    expect(send).toHaveBeenCalledTimes(1);
    const sent = send.mock.calls[0][0] as Record<string, unknown>;
    expect(sent.type).toBe("new_order");
    expect(sent.order_type).toBe("MARKET");
    expect(sent.price).toBe(0);
    expect(sent.quantity).toBe(5);
  });

  it("does not submit while disconnected", () => {
    const send = vi.fn();
    const state = { ...buildState([]), connected: false };
    renderWithState(state, <OrderEntry />, send);
    const submit = screen.getByRole("button", { name: /submit/i });
    expect(submit).toBeDisabled();
    fireEvent.click(submit);
    expect(send).not.toHaveBeenCalled();
  });
});
