# Top-level developer targets. CI and docker-compose wire in their own
# invocations of the same underlying tools; this Makefile is just the
# human entry point.

.PHONY: help build test java-test python-test bench analytics load-test \
        dashboard-install dashboard-dev dashboard-build dashboard-test clean

help:
	@echo "Targets:"
	@echo "  build        — gradle build (engine, network, bench)"
	@echo "  test         — run all Java + Python tests"
	@echo "  java-test    — JUnit only"
	@echo "  python-test  — client + analytics pytest"
	@echo "  bench        — run JMH benchmarks (see docs/PERFORMANCE.md)"
	@echo "  analytics    — produce latency_histogram.html, depth_heatmap.png, simulator_pnl.html"
	@echo "  load-test    — run bench/load_test.py against a live engine (expects --port)"
	@echo "  dashboard-install — npm install for dashboard/"
	@echo "  dashboard-dev     — vite dev server on :5173 (expects bridge on :8765)"
	@echo "  dashboard-build   — tsc + vite build"
	@echo "  dashboard-test    — vitest run"
	@echo "  clean        — remove build outputs, venv, node_modules"

build:
	./gradlew build

java-test:
	./gradlew test

python-test:
	PYTHONPATH=bridge/src:analytics/src:client/src .venv/bin/pytest client/tests analytics/tests bridge/tests

test: java-test python-test dashboard-test

bench:
	./gradlew :bench:jmh

analytics:
	PYTHONPATH=analytics/src:client/src .venv/bin/python -m nanoexchange_analytics.cli

load-test:
	PYTHONPATH=client/src .venv/bin/python bench/load_test.py --port $${PORT:-9000}

dashboard-install:
	cd dashboard && npm install --no-audit --no-fund

dashboard-dev:
	cd dashboard && npm run dev

dashboard-build:
	cd dashboard && npm run build

dashboard-test:
	cd dashboard && npm test

clean:
	./gradlew clean
	rm -rf .venv analytics/outputs dashboard/node_modules dashboard/dist
