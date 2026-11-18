#!/usr/bin/env bash
# ==============================================================================
# Script: setup_cpu_and_numa.sh
# Purpose: Configure CPU frequency scaling, eliminate thermal/turbo jitter,
#          disable CPU idle states, and configure NUMA memory policies.
# Target: Production Low-Latency Bare-Metal / Colocation Hosts
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT INFRA] CPU Frequency Scaling & NUMA Topology Configuration"
echo "================================================================================"

if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] This script must be executed as root. Run with sudo." >&2
    exit 1
fi

TOTAL_CORES=$(nproc)
echo "[INFO] Detected CPU cores: ${TOTAL_CORES}"

# 1. Enforce 'performance' Governor Across All Logical Cores
echo "[INFO] Setting CPU scaling governor to 'performance'..."
for CPU_DIR in /sys/devices/system/cpu/cpu[0-9]*; do
    if [ -f "${CPU_DIR}/cpufreq/scaling_governor" ]; then
        echo "performance" > "${CPU_DIR}/cpufreq/scaling_governor"
    fi
done

# If cpupower is installed, apply system-wide
if command -v cpupower &> /dev/null; then
    cpupower frequency-set -g performance > /dev/null 2>&1 || true
    echo "[INFO] cpupower governor set to performance."
fi

# 2. Eliminate CPU Frequency Jitter / Lock Frequency
# Dynamic frequency scaling (DVFS) and uncontrolled Turbo Boost cause frequency transitions
# taking 10-20 microseconds, blowing our sub-microsecond latency targets.
echo "[INFO] Locking CPU frequency and eliminating power transitions..."

# Disable Intel P-State Turbo to avoid thermal throttling clock jitter
if [ -f "/sys/devices/system/cpu/intel_pstate/no_turbo" ]; then
    echo 1 > /sys/devices/system/cpu/intel_pstate/no_turbo
    echo "[INFO] Intel P-state no_turbo enabled (clock frequency locked)."
fi

# Enforce maximum performance bias
if command -v x86_energy_perf_policy &> /dev/null; then
    x86_energy_perf_policy performance > /dev/null 2>&1 || true
    echo "[INFO] x86_energy_perf_policy set to 'performance'."
fi

# 3. Disable CPU Idle C-States (Lock in C0 Active State)
# Deep C-states take tens of microseconds to exit when packets arrive.
echo "[INFO] Disabling CPU C-States (enforcing C0 state for zero-latency wakeup)..."
for CPU_DIR in /sys/devices/system/cpu/cpu[0-9]*; do
    # Disable all non-C0 states (state1, state2, state3, etc.)
    for STATE_DIR in "${CPU_DIR}/cpuidle/state"[1-9]*; do
        if [ -f "${STATE_DIR}/disable" ]; then
            echo 1 > "${STATE_DIR}/disable"
        fi
    done
done

# Set PM QoS resume latency to 0 (tells kernel never to enter sleep)
if [ -e "/dev/cpu_dma_latency" ]; then
    # Keep file open with 0 to prevent CPU entering sleep states
    exec 3<> /dev/cpu_dma_latency
    perl -e 'syswrite(STDOUT, pack("i", 0));' >&3
    echo "[INFO] /dev/cpu_dma_latency set to 0us (DMA latency pinned)."
fi

# 4. NUMA Node Alignment & Topology Discovery
echo "[INFO] Verifying NUMA Topology..."
if command -v numactl &> /dev/null; then
    NUMA_NODES=$(numactl --show | grep "cpubind" | awk '{print $2}')
    echo "[INFO] Available NUMA Nodes: ${NUMA_NODES}"
    echo "[INFO] Generating NUMA execution recommendation..."
    
    # Identify PCIe NUMA node for Primary Network Interface (e.g., eth0 / onload0)
    PRIMARY_NIC="eth0"
    if [ -d "/sys/class/net/${PRIMARY_NIC}/device" ]; then
        if [ -f "/sys/class/net/${PRIMARY_NIC}/device/numa_node" ]; then
            NIC_NUMA=$(cat "/sys/class/net/${PRIMARY_NIC}/device/numa_node")
            if [ "${NIC_NUMA}" -lt 0 ]; then
                NIC_NUMA=0
            fi
            echo "[INFO] Primary NIC (${PRIMARY_NIC}) is physically attached to NUMA Node: ${NIC_NUMA}"
            echo "[INFO] Recommended Launcher Command:"
            echo "       numactl --cpunodebind=${NIC_NUMA} --membind=${NIC_NUMA} <hft-launcher>"
        fi
    fi
else
    echo "[WARN] 'numactl' utility not found. Please install with: apt-get install -y numactl"
fi

# 5. Core Isolation Verification
echo "[INFO] Checking kernel isolated cores from /sys/devices/system/cpu/isolated..."
if [ -f "/sys/devices/system/cpu/isolated" ]; then
    ISOLATED_CORES=$(cat /sys/devices/system/cpu/isolated)
    echo "[INFO] Currently isolated cores: ${ISOLATED_CORES:-none}"
    if [[ "${ISOLATED_CORES}" != *"2-5"* && "${ISOLATED_CORES}" != *"2,3,4,5"* ]]; then
        echo "[WARN] Trading cores 2-5 are not reported in /sys/devices/system/cpu/isolated."
        echo "       Ensure boot grub parameters 'isolcpus=managed_irq,domain,nohz,2-5' are applied."
    else
        echo "[SUCCESS] Trading cores 2-5 are properly isolated from the OS scheduler."
    fi
fi

echo "================================================================================"
echo "  [HFT INFRA] CPU & NUMA Optimization Complete."
echo "================================================================================"
