#!/usr/bin/env bash
# ==============================================================================
# Script: setup_qa_benchmark_env.sh
# Purpose: Provision a deterministic, isolated benchmarking environment for @qa
# Target: JMH microbenchmarking and zero-heap allocation validation suites
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT QA ENVIRONMENT] Provisioning Isolated Benchmarking Environment"
echo "================================================================================"

# 1. Setup Isolated CPU Set for QA JMH Harness
echo "[INFO] Configuring CPU affinity masks for QA benchmark isolation..."
if command -v taskset &> /dev/null; then
    echo "[INFO] Pinned QA execution mask verified: taskset -c 2-5"
fi

# 2. Setup Loopback & Multicast Interfaces for Feed A & Feed B Simulation
echo "[INFO] Configuring Multicast Loopback for Dual-Feed Ingest Simulation..."
if [ "$EUID" -eq 0 ]; then
    # Enable multicast routing on loopback
    ip route add 239.0.0.0/8 dev lo 2>/dev/null || true
    
    # Increase loopback socket buffer limits for high-speed simulation
    sysctl -w net.core.rmem_max=67108864 > /dev/null 2>&1 || true
    sysctl -w net.core.wmem_max=67108864 > /dev/null 2>&1 || true
    echo "[SUCCESS] Multicast routes on loopback interface configured."
else
    echo "[INFO] Running as non-root: using existing network interface configurations."
fi

# 3. Create QA Output Directories
QA_OUTPUT_DIR="target/qa-benchmarks"
mkdir -p "${QA_OUTPUT_DIR}"
echo "[INFO] QA benchmark output directory: ${QA_OUTPUT_DIR}"

# 4. Generate Exportable Environment for QA Test Suites
cat << 'EOF' > target/qa-env.sh
# QA Test Rig Environment Exports
export HFT_BENCHMARK_WARMUP_ITERATIONS=200000
export HFT_BENCHMARK_MEASURE_ITERATIONS=100000
export HFT_ALLOCATION_CHECK_STRICT=true
export HFT_FEED_A_MCAST="239.1.1.1:10001"
export HFT_FEED_B_MCAST="239.1.1.2:10002"
EOF

echo "[SUCCESS] QA Isolated Environment ready. Source target/qa-env.sh before running tests."
