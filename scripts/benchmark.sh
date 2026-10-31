#!/usr/bin/env bash
# Builds the jar and runs the benchmark with a 512 MB heap; writes docs/benchmark-results.md.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q -B package -DskipTests
java -Xmx512m -Dloader.main=com.moveinsync.roster.bench.Benchmark \
  -cp target/hr-roster-sync-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  docs/benchmark-results.md
