import { useExchange } from "../state/ExchangeContext";

export function ConnectionStatus() {
  const { state } = useExchange();
  const label = state.connected
    ? state.degraded
      ? "DEGRADED"
      : "LIVE"
    : "OFFLINE";
  const cls = state.connected
    ? state.degraded
      ? "bg-highlight text-black"
      : "bg-bid-green text-black"
    : "bg-ask-red text-white";
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className={`px-2 py-0.5 font-bold rounded ${cls}`}>{label}</span>
      <span className="text-neutral-fg/70">seq {state.lastSeq}</span>
    </div>
  );
}
