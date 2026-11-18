#!/usr/bin/env bash
# ==============================================================================
# Script: tune_nic.sh
# Purpose: Tune Network Interface Cards (NICs) for zero-jitter, minimum-latency packet delivery.
# Target: Solarflare Flareon / XtremeScale (SFC9220/X2522) or Intel/Mellanox 10/25/40/100GbE NICs
# Usage: sudo ./tune_nic.sh [interface]
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT INFRA] Physical Network Interface Card (NIC) Ultra-Low Latency Tuning"
echo "================================================================================"

if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] This script must be executed as root. Run with sudo." >&2
    exit 1
fi

NIC="${1:-eth0}"
echo "[INFO] Target Network Interface: ${NIC}"

if ! ip link show "${NIC}" > /dev/null 2>&1; then
    echo "[ERROR] Network interface '${NIC}' does not exist." >&2
    exit 1
fi

# 1. Maximize Hardware Descriptor Ring Buffers
# Sets hardware RX and TX descriptor rings to 4096 (or maximum supported)
# to absorb market open microbursts without dropping packets.
echo "[INFO] Configuring maximum ring buffer sizes..."
MAX_RX=$(ethtool -g "${NIC}" 2>/dev/null | awk '/Pre-set maximums:/,/Current hardware settings:/' | grep "RX:" | head -1 | awk '{print $2}' || echo "4096")
MAX_TX=$(ethtool -g "${NIC}" 2>/dev/null | awk '/Pre-set maximums:/,/Current hardware settings:/' | grep "TX:" | head -1 | awk '{print $2}' || echo "4096")

TARGET_RX="${MAX_RX:-4096}"
TARGET_TX="${MAX_TX:-4096}"

echo "[INFO] Setting ring buffer size on ${NIC}: RX=${TARGET_RX}, TX=${TARGET_TX}..."
ethtool -G "${NIC}" rx "${TARGET_RX}" tx "${TARGET_TX}" || true

# 2. Disable Interrupt Coalescing (Zero Interrupt Delay)
# Interrupt coalescing delays delivering interrupts to batch packets.
# In HFT, this introduces up to 100 microseconds of latency.
# We must disable adaptive coalescing and set all timer delays to 0.
echo "[INFO] Disabling interrupt coalescing (rx-usecs=0, adaptive-rx=off)..."
ethtool -C "${NIC}" \
    adaptive-rx off \
    adaptive-tx off \
    rx-usecs 0 \
    rx-frames 0 \
    rx-usecs-irq 0 \
    rx-frames-irq 0 \
    tx-usecs 0 \
    tx-frames 0 \
    tx-usecs-irq 0 \
    tx-frames-irq 0 || true

# 3. Disable Packet Aggregation & Checksum Offloads
# Large Receive Offload (LRO) and Generic Receive Offload (GRO) aggregate consecutive
# packets into larger buffers. This introduces unacceptable jitter for market data feeds.
echo "[INFO] Disabling packet aggregation offloads (gro, lro, tso, gso)..."
ethtool -K "${NIC}" \
    gro off \
    lro off \
    tso off \
    gso off \
    ufo off || true

# 4. Enable Hardware Timestamping Capabilities (IEEE 1588 PTP / MiFID II RTS 25)
echo "[INFO] Checking Hardware Timestamping capabilities on ${NIC}..."
if ethtool -T "${NIC}" 2>/dev/null | grep -q "hardware-transmit"; then
    echo "[SUCCESS] Hardware PTP timestamping supported on ${NIC}."
else
    echo "[WARN] Interface ${NIC} may not support hardware timestamping (software fallback active)."
fi

# 5. Disable Ethernet Flow Control (Pause Frames)
# Pause frames stop transmission when downstream buffers fill up, introducing unpredictable stalls.
echo "[INFO] Disabling Ethernet Pause Frames (Flow Control)..."
ethtool -A "${NIC}" autoneg off rx off tx off || true

# 6. Display Verification Summary
echo "================================================================================"
echo "  [HFT INFRA] Current Settings for ${NIC}:"
echo "================================================================================"
ethtool -g "${NIC}" 2>/dev/null || true
ethtool -c "${NIC}" 2>/dev/null || true
echo "[SUCCESS] NIC ${NIC} successfully configured for Ultra-Low Latency execution."
