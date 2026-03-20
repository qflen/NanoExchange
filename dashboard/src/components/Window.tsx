import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

export interface WindowGeom {
  x: number;
  y: number;
  w: number;
  h: number;
}

interface WindowProps {
  title: string;
  initial: WindowGeom;
  z: number;
  onFocus: () => void;
  children: ReactNode;
}

const HEADER_PX = 26;
const MIN_W = 180;
const MIN_H = 120;

export function Window({ title, initial, z, onFocus, children }: WindowProps) {
  const [pos, setPos] = useState({ x: initial.x, y: initial.y });
  const rootRef = useRef<HTMLDivElement>(null);
  const dragState = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    origX: number;
    origY: number;
  } | null>(null);

  // Set initial size imperatively. After mount, CSS `resize: both`
  // owns width/height: the user drags the bottom-right corner and the
  // browser updates the DOM directly. React never re-applies size,
  // so the resize state survives re-renders.
  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    el.style.width = `${initial.w}px`;
    el.style.height = `${initial.h}px`;
  }, []);

  const onHeaderPointerDown = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      // Ignore right-click and middle-click — those have other meanings.
      if (e.button !== 0) return;
      onFocus();
      const target = e.currentTarget;
      target.setPointerCapture(e.pointerId);
      dragState.current = {
        pointerId: e.pointerId,
        startX: e.clientX,
        startY: e.clientY,
        origX: pos.x,
        origY: pos.y,
      };
    },
    [onFocus, pos.x, pos.y],
  );

  const onHeaderPointerMove = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const s = dragState.current;
      if (!s || s.pointerId !== e.pointerId) return;
      const dx = e.clientX - s.startX;
      const dy = e.clientY - s.startY;
      // Clamp so the drag handle stays reachable.
      const root = rootRef.current;
      const w = root?.offsetWidth ?? initial.w;
      const parent = root?.parentElement;
      const maxX = (parent?.clientWidth ?? window.innerWidth) - 40;
      const maxY = (parent?.clientHeight ?? window.innerHeight) - HEADER_PX;
      const nx = Math.max(-(w - 80), Math.min(maxX, s.origX + dx));
      const ny = Math.max(0, Math.min(maxY, s.origY + dy));
      setPos({ x: nx, y: ny });
    },
    [initial.w],
  );

  const onHeaderPointerUp = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const s = dragState.current;
      if (s && s.pointerId === e.pointerId) {
        e.currentTarget.releasePointerCapture(e.pointerId);
        dragState.current = null;
      }
    },
    [],
  );

  // Bring-to-front on any pointer-down inside the window body, not just
  // the header. Matches OS window-manager intuition.
  const onBodyPointerDown = useCallback(() => {
    onFocus();
  }, [onFocus]);

  return (
    <div
      ref={rootRef}
      onPointerDown={onBodyPointerDown}
      style={{
        position: "absolute",
        left: pos.x,
        top: pos.y,
        zIndex: z,
        minWidth: MIN_W,
        minHeight: MIN_H,
        resize: "both",
        overflow: "hidden",
      }}
      className="bg-panel-bg border border-panel-border rounded shadow-lg flex flex-col"
    >
      <div
        onPointerDown={onHeaderPointerDown}
        onPointerMove={onHeaderPointerMove}
        onPointerUp={onHeaderPointerUp}
        onPointerCancel={onHeaderPointerUp}
        style={{ height: HEADER_PX, cursor: "move", touchAction: "none" }}
        className="flex items-center px-2 text-[11px] font-bold uppercase tracking-wide bg-black/40 border-b border-panel-border select-none flex-shrink-0"
      >
        {title}
      </div>
      <div className="flex-1 min-h-0 overflow-auto">{children}</div>
    </div>
  );
}
