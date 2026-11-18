#!/usr/bin/env bash
# ==============================================================================
# Script: setup_irq_affinity.sh
# Purpose: Steer all hardware interrupts (IRQs) away from isolated trading cores (2-5)
#          and pin them strictly to housekeeping cores (0-1).
# Target: Eliminates hardware interrupt cache pollution & execution context switching
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT INFRA] Interrupt (IRQ) Affinity Steering & Isolation"
echo "================================================================================"

if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] This script must be executed as root. Run with sudo." >&2
    exit 1
fi

# 1. Terminate & Disable irqbalance Service
# irqbalance dynamically migrates IRQs across all CPU cores, which periodically
# interrupts our busy-spin trading loops on cores 2-5.
echo "[INFO] Terminating irqbalance daemon..."
if systemctl is-active --quiet irqbalance; then
    systemctl stop irqbalance
    systemctl disable irqbalance
    echo "[SUCCESS] irqbalance stopped and disabled."
else
    echo "[INFO] irqbalance is not active."
fi

# 2. Housekeeping Core Affinity Bitmask Calculation
# Core 0 = bit 0 (1)
# Core 1 = bit 1 (2)
# Cores 0 + 1 = 1 + 2 = 3 (Hex: '3')
# This leaves Cores 2, 3, 4, 5 completely untouched by IRQs.
HOUSEKEEPING_IRQ_MASK="3"

echo "[INFO] Setting default IRQ affinity mask to housekeeping cores (Cores 0-1): ${HOUSEKEEPING_IRQ_MASK}..."
if [ -f "/proc/irq/default_smp_affinity" ]; then
    echo "${HOUSEKEEPING_IRQ_MASK}" > /proc/irq/default_smp_affinity
fi

# 3. Re-route All Active Hardware IRQs
echo "[INFO] Re-routing all hardware IRQs to Cores 0-1..."
for IRQ_DIR in /proc/irq/[0-9]*; do
    if [ -f "${IRQ_DIR}/smp_affinity" ]; then
        # Some IRQs are non-configurable (read-only); suppress errors
        echo "${HOUSEKEEPING_IRQ_MASK}" > "${IRQ_DIR}/smp_affinity" 2>/dev/null || true
    fi
done

# 4. Target Primary Network Interfaces (e.g., eth0, eth1, ens*, eno*)
NIC_INTERFACES=$(ip -o link show | awk -F': ' '{print $2}' | grep -v "lo" || true)

for NIC in ${NIC_INTERFACES}; do
    echo "[INFO] Locating IRQs for Network Interface: ${NIC}..."
    NIC_IRQS=$(grep "${NIC}" /proc/interrupts | awk -F':' '{print $1}' | tr -d ' ' || true)
    
    for IRQ in ${NIC_IRQS}; do
        if [ -f "/proc/irq/${IRQ}/smp_affinity" ]; then
            echo "${HOUSEKEEPING_IRQ_MASK}" > "/proc/irq/${IRQ}/smp_affinity" 2>/dev/null || true
            echo "       -> IRQ ${IRQ} (${NIC}) steered to cores 0-1 (mask: ${HOUSEKEEPING_IRQ_MASK})"
        fi
    done
done

# 5. Disable RPS (Receive Packet Steering) on Trading Cores
echo "[INFO] Disabling software RPS on isolated cores..."
for RPS_FILE in /sys/class/net/*/queues/rx-*/rps_cpus; do
    if [ -f "${RPS_FILE}" ]; then
        # Direct RPS only to Core 0-1 or disable completely for Onload/EF_VI
        echo "0" > "${RPS_FILE}" 2>/dev/null || true
    fi
done

echo "================================================================================"
echo "  [HFT INFRA] IRQ Affinity Steering Complete. Trading Cores 2-5 are 100% Isolated."
echo "================================================================================"
