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

# macOS will not join a multicast group on the loopback adapter unless the
# interface is named explicitly. Linux picks a default route and is fine.
MCAST_IFACE_ARGS=()
if [[ "$OSTYPE" == darwin* ]]; then
  MCAST_IFACE_ARGS=(--multicast-interface 127.0.0.1)
fi

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

# Job control puts each '&' child into its own process group so the
# kill -TERM -- -PID trick below can take down its descendants. Must be
# set BEFORE spawning any children — otherwise they inherit our pgid
# and killing "their" group takes us down too.
set -m

PIDS=()
SHUTDOWN_DONE=0

# Recursively walk the process tree rooted at $1 and echo every pid in
# pre-order. Used as a backstop when process groups fail to contain
# grandchildren (gradle daemons, vite's esbuild worker, etc.).
descendants() {
  local parent=$1 child
  for child in $(pgrep -P "$parent" 2>/dev/null); do
    echo "$child"
    descendants "$child"
  done
}

# Anything currently listening on one of our well-known ports is ours.
# Used as a final sweep in case a JVM re-parented itself to init.
port_holders() {
  local port
  for port in "$@"; do
    lsof -ti tcp:"$port" 2>/dev/null || true
    lsof -ti udp:"$port" 2>/dev/null || true
  done
}

cleanup() {
  if [[ "${SHUTDOWN_DONE}" == "1" ]]; then return; fi
  SHUTDOWN_DONE=1
  trap '' INT TERM EXIT HUP
  echo
  echo "[run.sh] shutting down..."

  # Phase 1: gather every pid we know about — tracked children, their
  # whole process groups, every descendant we can find, and anything
  # still bound to our ports.
  local -a victims=()
  local pid
  for pid in "${PIDS[@]}"; do
    victims+=("$pid")
    # Children of this shell (wrappers) plus every grandchild.
    while IFS= read -r p; do victims+=("$p"); done < <(descendants "$pid")
  done
  while IFS= read -r p; do victims+=("$p"); done < <(port_holders \
    "${ORDER_PORT}" "${WS_PORT}" "${DASH_PORT}" "${MCAST_PORT}")

  # Phase 2: polite TERM to process groups (handles detached grandkids
  # that escaped the parent->child link but stayed in the pgroup) then
  # to individual pids.
  for pid in "${PIDS[@]}"; do
    kill -TERM -- -"$pid" 2>/dev/null || true
  done
  for pid in "${victims[@]}"; do
    kill -TERM "$pid" 2>/dev/null || true
  done
  pkill -TERM -P $$ 2>/dev/null || true

  # Phase 3: wait up to ~3 s for voluntary exit.
  local waited=0
  while (( waited < 15 )); do
    local alive=0
    for pid in "${victims[@]}"; do
      kill -0 "$pid" 2>/dev/null && { alive=1; break; }
    done
    (( alive == 0 )) && break
    sleep 0.2
    waited=$((waited + 1))
  done

  # Phase 4: escalate. Re-scan ports in case something respawned or
  # we missed it the first time.
  while IFS= read -r p; do victims+=("$p"); done < <(port_holders \
    "${ORDER_PORT}" "${WS_PORT}" "${DASH_PORT}" "${MCAST_PORT}")
  for pid in "${PIDS[@]}"; do
    kill -KILL -- -"$pid" 2>/dev/null || true
  done
  for pid in "${victims[@]}"; do
    kill -KILL "$pid" 2>/dev/null || true
  done
  pkill -KILL -P $$ 2>/dev/null || true

  # Belt-and-braces: Gradle's launcher, the build daemon (if one
  # spun up anyway), and the forked application JVM (`:network:run`
  # uses JavaExec which may reparent under launchd on macOS when its
  # parent dies first). Matching on command-line patterns catches
  # whichever leaked.
  pkill -KILL -f 'org.gradle.launcher' 2>/dev/null || true
  pkill -KILL -f 'GradleDaemon' 2>/dev/null || true
  pkill -KILL -f 'GradleWrapperMain' 2>/dev/null || true
  pkill -KILL -f 'gradle-launcher' 2>/dev/null || true
  pkill -KILL -f 'com.nanoexchange' 2>/dev/null || true
  # Vite/esbuild sometimes leaves a detached worker when npm is killed
  # before it wires up its own SIGTERM handler.
  pkill -KILL -f 'esbuild' 2>/dev/null || true
  pkill -KILL -f 'vite' 2>/dev/null || true

  echo "[run.sh] done."
  exit 0
}
trap cleanup INT TERM HUP EXIT

# Wait for a TCP port on 127.0.0.1 to accept connections, with a
# spinner so long cold-start builds don't look hung.
wait_for_port() {
  local port="$1" label="$2" timeout="${3:-180}"
  local start=$SECONDS
  local spin='|/-\'
  local i=0
  printf "[run.sh] waiting for %s on :%s " "$label" "$port"
  while ! (exec 3<>/dev/tcp/127.0.0.1/"$port") 2>/dev/null; do
    if (( SECONDS - start > timeout )); then
      printf "\n[run.sh] timeout after %ss waiting for %s\n" "$timeout" "$label"
      return 1
    fi
    printf "\b%s" "${spin:i++%${#spin}:1}"
    sleep 0.25
  done
  exec 3>&- 3<&- 2>/dev/null || true
  printf "\b✓ (%ss)\n" "$((SECONDS - start))"
}

echo "[run.sh] building + starting Java engine on :${ORDER_PORT} (multicast ${MCAST_GROUP}:${MCAST_PORT})"
echo "[run.sh] first run compiles the engine from scratch — expect ~1-2 min"
# --no-daemon: keep the JVM in the foreground of the gradle process so
#   when we kill the gradle wrapper, the engine JVM dies with it. The
#   default daemon detaches and survives Ctrl-C, leaking ~500 MB each
#   restart cycle.
# No --quiet: gradle's own progress bar is what the user watches while
#   the build runs.
./gradlew --console=plain --no-daemon :network:run --args="${ORDER_PORT} ${MCAST_GROUP} ${MCAST_PORT}" &
PIDS+=($!)

wait_for_port "${ORDER_PORT}" "engine gateway"

echo "[run.sh] starting WebSocket bridge on :${WS_PORT}"
.venv/bin/nx-bridge \
  --multicast-group "${MCAST_GROUP}" --multicast-port "${MCAST_PORT}" \
  --order-gw-host 127.0.0.1 --order-gw-port "${ORDER_PORT}" \
  --ws-port "${WS_PORT}" \
  "${MCAST_IFACE_ARGS[@]}" &
PIDS+=($!)

wait_for_port "${WS_PORT}" "ws bridge" 30

echo "[run.sh] starting dashboard on http://localhost:${DASH_PORT}"
npm --prefix dashboard run dev -- --host 127.0.0.1 --port "${DASH_PORT}" &
PIDS+=($!)

wait_for_port "${DASH_PORT}" "dashboard" 60

echo "[run.sh] all three processes up — open http://localhost:${DASH_PORT}"
wait
