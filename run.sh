#!/usr/bin/env bash
# NanoExchange one-shot launcher.
# Starts the Java engine + UDP feed, the Python WebSocket bridge, and the
# React dashboard. Wires their lifecycles together: Ctrl-C tears them all down.
# Bootstraps the Python venv and dashboard node_modules on first run so a
# fresh clone is literally `git clone && ./run.sh`.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

ORDER_PORT=9000
MCAST_GROUP=239.200.6.42
MCAST_PORT=6666
WS_PORT=8765
DASH_PORT=5173

# Color helpers. Disabled when stdout is not a TTY or NO_COLOR is set so
# piped output stays clean. Using 8-color ANSI for portability.
if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
  C_RESET=$'\033[0m'
  C_BOLD=$'\033[1m'
  C_DIM=$'\033[2m'
  C_TAG=$'\033[1;36m'     # bold cyan for [run.sh]
  C_STEP=$'\033[1;35m'    # bold magenta for step headings
  C_OK=$'\033[1;32m'      # bold green for ✓
  C_WARN=$'\033[1;33m'    # bold yellow
  C_ERR=$'\033[1;31m'     # bold red
  C_INFO=$'\033[0;34m'    # blue for secondary info
  C_LINK=$'\033[4;36m'    # underlined cyan for URLs
else
  C_RESET=""; C_BOLD=""; C_DIM=""; C_TAG=""; C_STEP=""
  C_OK=""; C_WARN=""; C_ERR=""; C_INFO=""; C_LINK=""
fi

tag() {
  printf "%s[run.sh]%s " "${C_TAG}" "${C_RESET}"
}
step() {
  tag; printf "%s%s%s\n" "${C_STEP}" "$*" "${C_RESET}"
}
info() {
  tag; printf "%s%s%s\n" "${C_INFO}" "$*" "${C_RESET}"
}
warn() {
  tag; printf "%s%s%s\n" "${C_WARN}" "$*" "${C_RESET}"
}
err() {
  tag; printf "%s%s%s\n" "${C_ERR}" "$*" "${C_RESET}"
}

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

First run auto-bootstraps: the Gradle wrapper pulls JDK 21 via foojay,
the script creates .venv and pip-installs client/bridge/analytics in
editable mode, and runs npm install in dashboard/. Subsequent runs
skip these steps.

Prereqs: Python ≥ 3.11 and Node ≥ 20 on PATH.

Set NO_COLOR=1 to disable ANSI colors.

Ctrl-C tears down all three processes.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

# --- First-run bootstrap ----------------------------------------------------
# Skip silently when already set up. The guards here are minimal — the
# presence of .venv/bin/nx-bridge and dashboard/node_modules is enough.
# A broken/partial install is caught by the downstream exec failing.

# Platform-appropriate install hint for a missing prereq. Keeps the
# error message actionable on macOS (brew) and common Linux distros
# (apt/dnf) without assuming which package manager ships.
install_hint() {
  local brew_pkg="$1" linux_pkg="$2"
  if [[ "$OSTYPE" == darwin* ]]; then
    printf "  try: brew install %s\n" "$brew_pkg"
  elif command -v apt >/dev/null 2>&1; then
    printf "  try: sudo apt install %s\n" "$linux_pkg"
  elif command -v dnf >/dev/null 2>&1; then
    printf "  try: sudo dnf install %s\n" "$linux_pkg"
  fi
}

if [[ ! -x .venv/bin/nx-bridge ]]; then
  step "first-run: creating Python venv + installing packages"
  if ! command -v python3 >/dev/null 2>&1; then
    err "python3 not found on PATH — install Python ≥ 3.11 and retry"
    install_hint "python@3.12" "python3 python3-venv"
    exit 1
  fi
  # Recreate from scratch if .venv exists but is broken (missing bin, wrong
  # interpreter path, etc.) — the common failure mode when a Homebrew
  # Python version rolls.
  if [[ -d .venv && ! -x .venv/bin/python ]]; then
    warn "existing .venv looks broken — recreating"
    rm -rf .venv
  fi
  [[ -d .venv ]] || python3 -m venv .venv
  # Newer pip (26+) requires the path form `./pkg[extras]`; the bare
  # `pkg[extras]` syntax was deprecated and now errors out.
  .venv/bin/pip install --disable-pip-version-check --quiet \
    -e "./client[dev]" -e "./bridge[dev]" -e "./analytics[dev]"
  info "venv ready"
fi

if [[ ! -d dashboard/node_modules ]]; then
  step "first-run: installing dashboard npm packages"
  if ! command -v npm >/dev/null 2>&1; then
    err "npm not found on PATH — install Node ≥ 20 and retry"
    install_hint "node" "nodejs npm"
    exit 1
  fi
  npm --prefix dashboard install --no-audit --no-fund --silent
  info "dashboard ready"
fi

# --- Process lifecycle management -------------------------------------------

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
  warn "shutting down..."

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

  info "done."
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
  tag
  printf "waiting for %s%s%s on :%s%s%s " \
    "${C_BOLD}" "$label" "${C_RESET}" \
    "${C_BOLD}" "$port" "${C_RESET}"
  while ! (exec 3<>/dev/tcp/127.0.0.1/"$port") 2>/dev/null; do
    if (( SECONDS - start > timeout )); then
      printf "\n"
      err "timeout after ${timeout}s waiting for ${label}"
      return 1
    fi
    printf "\b%s%s%s" "${C_WARN}" "${spin:i++%${#spin}:1}" "${C_RESET}"
    sleep 0.25
  done
  exec 3>&- 3<&- 2>/dev/null || true
  printf "\b%s✓%s (%ss)\n" "${C_OK}" "${C_RESET}" "$((SECONDS - start))"
}

# --- Launch -----------------------------------------------------------------

step "[1/3] Java engine → :${ORDER_PORT} | multicast ${MCAST_GROUP}:${MCAST_PORT}"
info "first run compiles the engine from scratch — expect ~1-2 min"
# --no-daemon: keep the JVM in the foreground of the gradle process so
#   when we kill the gradle wrapper, the engine JVM dies with it. The
#   default daemon detaches and survives Ctrl-C, leaking ~500 MB each
#   restart cycle.
# No --quiet: gradle's own progress bar is what the user watches while
#   the build runs.
./gradlew --console=plain --no-daemon :network:run --args="${ORDER_PORT} ${MCAST_GROUP} ${MCAST_PORT}" &
PIDS+=($!)

wait_for_port "${ORDER_PORT}" "engine gateway"

step "[2/3] WebSocket bridge → :${WS_PORT}"
.venv/bin/nx-bridge \
  --multicast-group "${MCAST_GROUP}" --multicast-port "${MCAST_PORT}" \
  --order-gw-host 127.0.0.1 --order-gw-port "${ORDER_PORT}" \
  --ws-port "${WS_PORT}" \
  "${MCAST_IFACE_ARGS[@]}" &
PIDS+=($!)

wait_for_port "${WS_PORT}" "ws bridge" 30

step "[3/3] Dashboard → http://localhost:${DASH_PORT}"
npm --prefix dashboard run dev -- --host 127.0.0.1 --port "${DASH_PORT}" &
PIDS+=($!)

wait_for_port "${DASH_PORT}" "dashboard" 60

echo
tag
printf "%sall three processes up%s — open %s%s%shttp://localhost:%s%s\n" \
  "${C_OK}" "${C_RESET}" \
  "${C_LINK}" "${C_BOLD}" "" "${DASH_PORT}" "${C_RESET}"
wait
