# Top-level developer targets. CI and docker-compose wire in their own
# invocations of the same underlying tools; this Makefile is just the
# human entry point.

.PHONY: help build test java-test python-test bench analytics load-test clean

help:
	@echo "Targets:"
	@echo "  build        — gradle build (engine, network, bench)"
	@echo "  test         — run all Java + Python tests"
	@echo "  java-test    — JUnit only"
	@echo "  python-test  — client + analytics pytest"
	@echo "  bench        — run JMH benchmarks (see docs/PERFORMANCE.md)"
	@echo "  analytics    — produce latency_histogram.html, depth_heatmap.png, simulator_pnl.html"
	@echo "  load-test    — run bench/load_test.py against a live engine (expects --port)"
	@echo "  clean        — remove build outputs and venv"

build:
	./gradlew build

java-test:
	./gradlew test

python-test:
	PYTHONPATH=analytics/src:client/src .venv/bin/pytest client/tests analytics/tests

test: java-test python-test

bench:
	./gradlew :bench:jmh

analytics:
	PYTHONPATH=analytics/src:client/src .venv/bin/python -m nanoexchange_analytics.cli

load-test:
	PYTHONPATH=client/src .venv/bin/python bench/load_test.py --port $${PORT:-9000}

clean:
	./gradlew clean
	rm -rf .venv analytics/outputs
