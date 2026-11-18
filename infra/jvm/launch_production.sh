#!/usr/bin/env bash
# ==============================================================================
# Script: launch_production.sh
# Purpose: Production execution launcher for Ultra-Low Latency Java HFT Platform
# Target: Java 21 LTS + Epsilon GC + Solarflare Onload / EF_VI + NUMA Node 0
# Usage: ./launch_production.sh [path-to-jar-or-target]
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${INFRA_DIR}/.." && pwd)"

echo "================================================================================"
echo "  [HFT PRODUCTION LAUNCHER] Initializing Production Environment"
echo "================================================================================"

# 1. Source Solarflare OpenOnload Kernel-Bypass Environment
if [ -f "${INFRA_DIR}/network/onload_env.sh" ]; then
    # shellcheck disable=SC1091
    source "${INFRA_DIR}/network/onload_env.sh"
fi

# 2. Verify HugePages
if [ -d "/dev/hugepages1G" ] || [ -d "/dev/hugepages2M" ]; then
    echo "[INFO] HugePages filesystem detected."
else
    echo "[WARN] HugePages mount not found. Consider running infra/os-tuning/setup_hugepages.sh"
fi

# 3. Locate Target Executable / JAR
APP_JAR="${1:-${PROJECT_ROOT}/target/hft-platform-1.0.0-SNAPSHOT.jar}"
MAIN_CLASS="com.hft.test.HftTestRunner"

# Prepare Log and Flight Recorder directory
LOG_DIR="/var/log/hft"
mkdir -p "${LOG_DIR}" 2>/dev/null || LOG_DIR="${PROJECT_ROOT}/logs"
mkdir -p "${LOG_DIR}"

# 4. Construct JVM Options from Configuration File
JVM_OPTS_FILE="${SCRIPT_DIR}/hft-jvm-opts.conf"
JVM_ARGS=()

while IFS= read -r line || [[ -n "$line" ]]; do
    # Strip comments and empty lines
    trimmed_line="$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if [[ -n "$trimmed_line" && ! "$trimmed_line" =~ ^# ]]; then
        JVM_ARGS+=("$trimmed_line")
    fi
done < "${JVM_OPTS_FILE}"

echo "[INFO] Loaded ${#JVM_ARGS[@]} production JVM arguments from ${JVM_OPTS_FILE}."

# 5. Determine NUMA & CPU Pinning Command
# NUMA Node 0 houses trading cores (2-5) and primary Solarflare PCIe NIC.
LAUNCH_PREFIX=()

if command -v numactl &> /dev/null; then
    echo "[INFO] Pinned to NUMA Node 0 via numactl (--cpunodebind=0 --membind=0)..."
    LAUNCH_PREFIX+=(numactl --cpunodebind=0 --membind=0)
fi

# Apply taskset across cores 0-7 so housekeeping threads run on 0-1/6-7 and engine threads pin to 2-5
if command -v taskset &> /dev/null; then
    echo "[INFO] Taskset core mask: 0-7..."
    LAUNCH_PREFIX+=(taskset -c 0-7)
fi

# If Solarflare onload binary is available, wrap with onload
if command -v onload &> /dev/null; then
    echo "[INFO] Solarflare Onload detected. Wrapping execution with onload..."
    LAUNCH_PREFIX+=(onload --profile=latency)
fi

echo "================================================================================"
echo "  Starting HFT Engine with Epsilon GC (Zero-Allocation Mode)..."
echo "================================================================================"

# Execute Java
if [ -f "${APP_JAR}" ]; then
    echo "[INFO] Executing Application JAR: ${APP_JAR}"
    exec "${LAUNCH_PREFIX[@]}" java "${JVM_ARGS[@]}" -jar "${APP_JAR}"
else
    echo "[INFO] JAR not found at ${APP_JAR}. Executing with classpath from ${PROJECT_ROOT}/target/classes..."
    CLASSPATH="${PROJECT_ROOT}/target/classes:${PROJECT_ROOT}/target/test-classes"
    for jar in "${PROJECT_ROOT}"/target/dependency/*.jar; do
        if [ -f "$jar" ]; then
            CLASSPATH="${CLASSPATH}:$jar"
        fi
    done
    exec "${LAUNCH_PREFIX[@]}" java "${JVM_ARGS[@]}" -cp "${CLASSPATH}" "${MAIN_CLASS}"
fi
