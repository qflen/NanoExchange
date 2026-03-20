import { describe, it, expect, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useWebSocket } from "./useWebSocket";
import type { ServerMsg } from "../types";

// Minimal WebSocket mock. Vitest/jsdom do not ship a working WebSocket
// by default; we want to drive `onmessage` synchronously from the
// test and then advance rAF to check that the hook drains the inbox.

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  onopen: ((ev: Event) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  readyState = 1;
  url: string;
  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
    queueMicrotask(() => this.onopen?.(new Event("open")));
  }
  deliver(payload: unknown) {
    this.onmessage?.({ data: JSON.stringify(payload) } as MessageEvent);
  }
  close() {
    this.readyState = 3;
    this.onclose?.(new CloseEvent("close"));
  }
}

// @ts-expect-error - override for tests
globalThis.WebSocket = MockWebSocket;

afterEach(() => {
  MockWebSocket.instances = [];
});

describe("useWebSocket", () => {
  it("drains messages inside a rAF callback, not inside onmessage", async () => {
    const rafCallbacks: FrameRequestCallback[] = [];
    vi.stubGlobal(
      "requestAnimationFrame",
      (cb: FrameRequestCallback) => {
        rafCallbacks.push(cb);
        return rafCallbacks.length;
      },
    );
    vi.stubGlobal("cancelAnimationFrame", () => {});

    const messages: ServerMsg[] = [];
    renderHook(() =>
      useWebSocket({
        url: "ws://mock",
        onMessage: (m) => {
          messages.push(m);
        },
      }),
    );

    // Yield microtasks so onopen and the first rAF are scheduled.
    await act(async () => {
      await Promise.resolve();
    });
    const ws = MockWebSocket.instances[0];
    const msg: ServerMsg = {
      type: "batch",
      book_updates: [],
      trades: [],
      snapshots: [],
      execs: [],
    };
    ws.deliver(msg);
    ws.deliver(msg);

    // onmessage runs synchronously, but no dispatch yet:
    expect(messages).toHaveLength(0);

    // Now advance rAF once; hook should drain both messages.
    act(() => {
      const next = rafCallbacks.shift();
      next?.(performance.now());
    });
    expect(messages).toHaveLength(2);

    vi.unstubAllGlobals();
  });

  it("ignores malformed JSON without throwing", async () => {
    const rafCallbacks: FrameRequestCallback[] = [];
    vi.stubGlobal("requestAnimationFrame", (cb: FrameRequestCallback) => {
      rafCallbacks.push(cb);
      return rafCallbacks.length;
    });
    vi.stubGlobal("cancelAnimationFrame", () => {});

    const messages: ServerMsg[] = [];
    renderHook(() =>
      useWebSocket({
        url: "ws://mock",
        onMessage: (m) => {
          messages.push(m);
        },
      }),
    );
    await act(async () => {
      await Promise.resolve();
    });
    const ws = MockWebSocket.instances[0];
    ws.onmessage?.({ data: "not-json-{{" } as MessageEvent);
    act(() => {
      rafCallbacks.shift()?.(performance.now());
    });
    expect(messages).toHaveLength(0);
    vi.unstubAllGlobals();
  });
});
