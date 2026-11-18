#!/usr/bin/env bash
# ==============================================================================
# Script: launch_production_zgc.sh
# Purpose: Production fallback execution launcher with Java 21 Generational ZGC.
# Latency Profile: Ultra-low pause (< 1ms max GC pause) for environments requiring GC.
# Usage: ./launch_production_zgc.sh [path-to-jar-or-target]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${INFRA_DIR}/.." && pwd)"

echo "================================================================================"
echo "  [HFT PRODUCTION LAUNCHER - GENERATIONAL ZGC FALLBACK]"
echo "================================================================================"

# Source OpenOnload Environment
if [ -f "${INFRA_DIR}/network/onload_env.sh" ]; then
    # shellcheck disable=SC1091
    source "${INFRA_DIR}/network/onload_env.sh"
fi

APP_JAR="${1:-${PROJECT_ROOT}/target/hft-platform-1.0.0-SNAPSHOT.jar}"
MAIN_CLASS="com.hft.test.HftTestRunner"

ZGC_ARGS=(
    # Java 21 Generational ZGC Configuration
    "-XX:+UseZGC"
    "-XX:+ZGenerational"
    "-XX:ZAllocationSpikeTolerance=5"
    "-XX:+AlwaysPreTouch"
    "-XX:+UseLargePages"
    "-XX:LargePageSizeInBytes=1G"
    "-Xms16g"
    "-Xmx16g"
    "-XX:MaxDirectMemorySize=16g"
    "-XX:ActiveProcessorCount=8"
    "-XX:+UnlockDiagnosticVMOptions"
    "-XX:GuaranteedSafepointInterval=0"
    "-XX:-UseBiasedLocking"
    "-XX:CompileThreshold=10000"
    "-XX:+TieredCompilation"
    "-XX:TieredStopAtLevel=4"
    "-XX:CICompilerCount=4"
    "-XX:+Inline"
    "-XX:MaxInlineLevel=15"
    "-XX:MaxInlineSize=250"
    "-XX:FreqInlineSize=325"
    "-Djava.net.preferIPv4Stack=true"
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
    "--add-opens=java.base/java.nio=ALL-UNNAMED"
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
)

LAUNCH_PREFIX=()
if command -v numactl &> /dev/null; then
    LAUNCH_PREFIX+=(numactl --cpunodebind=0 --membind=0)
fi
if command -v taskset &> /dev/null; then
    LAUNCH_PREFIX+=(taskset -c 0-7)
fi
if command -v onload &> /dev/null; then
    LAUNCH_PREFIX+=(onload --profile=latency)
fi

echo "[INFO] Launching with Java 21 Generational ZGC..."
if [ -f "${APP_JAR}" ]; then
    exec "${LAUNCH_PREFIX[@]}" java "${ZGC_ARGS[@]}" -jar "${APP_JAR}"
else
    CLASSPATH="${PROJECT_ROOT}/target/classes:${PROJECT_ROOT}/target/test-classes"
    exec "${LAUNCH_PREFIX[@]}" java "${ZGC_ARGS[@]}" -cp "${CLASSPATH}" "${MAIN_CLASS}"
fi
