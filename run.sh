#!/usr/bin/env bash
# NanoExchange one-shot launcher.
# Starts the Java engine + UDP feed, the Python WebSocket bridge, and the
# React dashboard. Wires their lifecycles together: Ctrl-C tears them all down.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

ORDER_PORT=9000
MCAST_GROUP=239.200.6.42
MCAST_PORT=6666
WS_PORT=8765
DASH_PORT=5173

usage() {
  cat <<EOF
NanoExchange — one-command local stack.

Usage: ./run.sh [--help]

Brings up the full dashboard demo in one terminal:

  1. Java matching engine + TCP order gateway (:${ORDER_PORT})
     and UDP multicast market-data publisher (${MCAST_GROUP}:${MCAST_PORT}).
     Built and launched via ./gradlew :network:run.

  2. Python WebSocket bridge (:${WS_PORT}) that subscribes to the multicast
     feed, holds a TCP order session to the gateway, and translates both
     to JSON over WebSocket for the browser. Launched via .venv/bin/nx-bridge.

  3. React dashboard at http://localhost:${DASH_PORT} that connects to the
     bridge and renders an order book, depth chart, trade tape, and order
     entry form. Launched via npm --prefix dashboard run dev.

Prereqs (one-time): JDK 21 (auto-provisioned by Gradle), Python ≥ 3.11
with .venv set up via 'python3 -m venv .venv && .venv/bin/pip install -e
"client[dev]" -e "bridge[dev]" -e "analytics[dev]"', and Node ≥ 20 with
'npm --prefix dashboard install'.

Ctrl-C tears down all three processes.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

PIDS=()
cleanup() {
  echo
  echo "[run.sh] shutting down..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup INT TERM EXIT

echo "[run.sh] starting Java engine on :${ORDER_PORT} (multicast ${MCAST_GROUP}:${MCAST_PORT})"
./gradlew --quiet :network:run --args="${ORDER_PORT} ${MCAST_GROUP} ${MCAST_PORT}" &
PIDS+=($!)

sleep 4

echo "[run.sh] starting WebSocket bridge on :${WS_PORT}"
.venv/bin/nx-bridge \
  --multicast-group "${MCAST_GROUP}" --multicast-port "${MCAST_PORT}" \
  --order-gw-host 127.0.0.1 --order-gw-port "${ORDER_PORT}" \
  --ws-port "${WS_PORT}" &
PIDS+=($!)

sleep 1

echo "[run.sh] starting dashboard on http://localhost:${DASH_PORT}"
npm --prefix dashboard run dev -- --host 127.0.0.1 --port "${DASH_PORT}" &
PIDS+=($!)

echo "[run.sh] all three processes up — open http://localhost:${DASH_PORT}"
wait
