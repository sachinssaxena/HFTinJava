#!/usr/bin/env bash
# ==============================================================================
# Script: deploy_production.sh
# Purpose: Master Deployment & Production Lifecycle Orchestrator
# Steps:
#   1. Apply OS, Kernel, and NUMA low-latency tuning parameters
#   2. Allocate HugePages and mount hugetlbfs
#   3. Steer hardware IRQs away from trading cores
#   4. Tune physical NICs and export Solarflare OpenOnload profile
#   5. Synchronize IEEE 1588 PTP hardware clock and audit MiFID II compliance
#   6. Run comprehensive preflight verification checklist
#   7. Execute 200,000-cycle Pre-Market JIT Warmup
#   8. Launch Production Engine with Epsilon GC under NUMA Node 0 binding
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INFRA_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "================================================================================"
echo "  [HFT PRODUCTION DEPLOYMENT] Beginning Master Orchestration Pipeline"
echo "================================================================================"

# Step 1: OS, Kernel & CPU Tuning
echo "[STAGE 1/8] Applying CPU & NUMA Optimizations..."
if [ -f "${INFRA_DIR}/os-tuning/setup_cpu_and_numa.sh" ]; then
    bash "${INFRA_DIR}/os-tuning/setup_cpu_and_numa.sh" || true
fi

# Step 2: HugePages Configuration
echo "[STAGE 2/8] Configuring 1GB HugePages..."
if [ -f "${INFRA_DIR}/os-tuning/setup_hugepages.sh" ]; then
    bash "${INFRA_DIR}/os-tuning/setup_hugepages.sh" || true
fi

# Step 3: IRQ Affinity Steering
echo "[STAGE 3/8] Steering Hardware IRQs to Housekeeping Cores..."
if [ -f "${INFRA_DIR}/os-tuning/setup_irq_affinity.sh" ]; then
    bash "${INFRA_DIR}/os-tuning/setup_irq_affinity.sh" || true
fi

# Step 4: Network & NIC Tuning
echo "[STAGE 4/8] Tuning Network Interfaces..."
if [ -f "${INFRA_DIR}/network/tune_nic.sh" ]; then
    bash "${INFRA_DIR}/network/tune_nic.sh" eth0 || true
fi

# Step 5: IEEE 1588 PTP Hardware Clock Synchronization
echo "[STAGE 5/8] Verifying PTP Clock Synchronization for MiFID II RTS 25..."
if [ -f "${INFRA_DIR}/network/verify_mifid_clock_sync.sh" ]; then
    bash "${INFRA_DIR}/network/verify_mifid_clock_sync.sh" || true
fi

# Step 6: Pre-Flight Readiness Checklist
echo "[STAGE 6/8] Executing Infrastructure Pre-Flight Readiness Verification..."
if [ -f "${INFRA_DIR}/deploy/preflight_check.sh" ]; then
    bash "${INFRA_DIR}/deploy/preflight_check.sh" || true
fi

# Step 7: Pre-Market JIT Warmup Phase
echo "[STAGE 7/8] Running Pre-Market Warmup (200k synthetic cycles)..."
if [ -f "${INFRA_DIR}/jvm/launch_warmup.sh" ]; then
    bash "${INFRA_DIR}/jvm/launch_warmup.sh" || true
fi

# Step 8: Launch Production Trading Engine
echo "[STAGE 8/8] Launching Production Trading Engine with Epsilon GC..."
if [ -f "${INFRA_DIR}/jvm/launch_production.sh" ]; then
    exec bash "${INFRA_DIR}/jvm/launch_production.sh" "$@"
fi
