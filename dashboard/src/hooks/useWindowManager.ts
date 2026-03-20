import { useCallback, useRef, useState } from "react";

/**
 * Tracks per-window z-index. The most recently focused window gets the
 * highest z. Windows that have never been focused keep their seed order.
 */
export function useWindowManager(ids: readonly string[]) {
  const counterRef = useRef(ids.length);
  const [zMap, setZMap] = useState<Record<string, number>>(() => {
    const m: Record<string, number> = {};
    ids.forEach((id, i) => {
      m[id] = i + 1;
    });
    return m;
  });

  const focus = useCallback((id: string) => {
    setZMap((prev) => {
      const top = Math.max(...Object.values(prev));
      // Already on top — no state churn.
      if (prev[id] === top) return prev;
      counterRef.current = top + 1;
      return { ...prev, [id]: counterRef.current };
    });
  }, []);

  return { zMap, focus };
}
