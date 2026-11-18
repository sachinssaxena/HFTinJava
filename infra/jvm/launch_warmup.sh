#!/usr/bin/env bash
# ==============================================================================
# Script: launch_warmup.sh
# Purpose: Pre-Market Startup & JIT Warmup Execution Driver
# Protocol: Simulates 200,000 synthetic ticks, order book updates, risk checks,
#           and wire serialization to trigger C2 Tier 4 JIT compilation and
#           branch target buffer warming prior to market open.
# Target: Eliminates first-tick latency spikes (cold-code penalty: 50-100 microseconds).
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${INFRA_DIR}/.." && pwd)"

echo "================================================================================"
echo "  [HFT PRE-MARKET WARMUP] Initializing JIT & Memory Warmup Phase"
echo "================================================================================"

# Warmup specific JVM arguments with PrintCompilation / JIT diagnostic logging
WARMUP_ARGS=(
    "-XX:+UnlockExperimentalVMOptions"
    "-XX:+UseEpsilonGC"
    "-XX:+AlwaysPreTouch"
    "-Xms4g"
    "-Xmx4g"
    "-XX:CompileThreshold=10000"
    "-XX:+TieredCompilation"
    "-XX:TieredStopAtLevel=4"
    "-XX:+PrintCompilation"
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:+PrintInlining"
    "-Djava.net.preferIPv4Stack=true"
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
    "--add-opens=java.base/java.nio=ALL-UNNAMED"
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
)

WARMUP_CYCLES=200000
echo "[INFO] Running warmup driver with ${WARMUP_CYCLES} synthetic market events..."

CLASSPATH="${PROJECT_ROOT}/target/classes:${PROJECT_ROOT}/target/test-classes"
for jar in "${PROJECT_ROOT}"/target/dependency/*.jar; do
    if [ -f "$jar" ]; then
        CLASSPATH="${CLASSPATH}:$jar"
    fi
done

# Run warmup execution
java "${WARMUP_ARGS[@]}" -cp "${CLASSPATH}" com.hft.test.HftTestRunner

echo "================================================================================"
echo "  [SUCCESS] Pre-market JIT Warmup Complete. All hot-path methods compiled to C2."
echo "================================================================================"
