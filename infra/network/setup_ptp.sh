#!/usr/bin/env bash
# ==============================================================================
# Script: setup_ptp.sh
# Purpose: Initialize IEEE 1588 PTP hardware clock synchronization for MiFID II RTS 25.
# Components:
#   1. ptp4l: Synchronizes Solarflare NIC PHC (Physical Hardware Clock) to PTP Grandmaster (GPS).
#   2. phc2sys: Disciplines Linux kernel CLOCK_REALTIME from the synchronized NIC PHC.
# Regulatory Mandate: MiFID II RTS 25 Table 2 (Algorithmic & HFT latency tolerance <= 100 microseconds).
# Target Latency Divergence: < 500 nanoseconds steady-state.
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT INFRA] IEEE 1588 PTP Hardware Clock Synchronization Setup"
echo "================================================================================"

if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] This script must be executed as root. Run with sudo." >&2
    exit 1
fi

NIC="${1:-eth0}"
CONFIG_FILE="$(dirname "$0")/ptp4l.conf"

# Verify linuxptp package installation
if ! command -v ptp4l &> /dev/null || ! command -v phc2sys &> /dev/null; then
    echo "[ERROR] 'linuxptp' utilities (ptp4l, phc2sys) not found."
    echo "        Install with: apt-get install -y linuxptp  or  yum install -y linuxptp" >&2
    exit 1
fi

# Locate Hardware Clock device associated with NIC
PTP_DEV=$(ethtool -T "${NIC}" 2>/dev/null | grep "PTP Hardware Clock:" | awk '{print $4}' || echo "")
if [ -z "${PTP_DEV}" ]; then
    echo "[WARN] No dedicated PHC found via ethtool for ${NIC}. Checking /dev/ptp0..."
    if [ -e "/dev/ptp0" ]; then
        PTP_DEV="0"
    else
        echo "[ERROR] No hardware PTP clock device detected on this host." >&2
        exit 1
    fi
fi
echo "[INFO] Using PTP Hardware Clock device: /dev/ptp${PTP_DEV} for interface ${NIC}"

# Terminate any previously running ptp daemons
echo "[INFO] Cleaning up existing PTP processes..."
pkill -9 ptp4l 2>/dev/null || true
pkill -9 phc2sys 2>/dev/null || true
sleep 1

# 1. Start ptp4l in background (Slave mode, Hardware timestamping)
echo "[INFO] Launching ptp4l daemon on interface ${NIC}..."
ptp4l -f "${CONFIG_FILE}" -i "${NIC}" -m -l 6 > /var/log/ptp4l.log 2>&1 &
PTP4L_PID=$!
echo "[SUCCESS] ptp4l running with PID: ${PTP4L_PID} (Logs: /var/log/ptp4l.log)"

# Wait for ptp4l to acquire lock / begin synchronization
sleep 3

# 2. Start phc2sys (Disciplining System CLOCK_REALTIME from /dev/ptpX)
# -s /dev/ptp${PTP_DEV}: Source clock is NIC PHC
# -c CLOCK_REALTIME: Target clock is Linux system clock
# -O 0: Offset 0 (UTC to TAI adjustment managed automatically)
# -w: Wait for ptp4l to synchronize first
# -m: Print log messages to stdout/file
echo "[INFO] Launching phc2sys daemon (/dev/ptp${PTP_DEV} -> CLOCK_REALTIME)..."
phc2sys -s "/dev/ptp${PTP_DEV}" -c CLOCK_REALTIME -w -m -l 6 -O 0 > /var/log/phc2sys.log 2>&1 &
PHC2SYS_PID=$!
echo "[SUCCESS] phc2sys running with PID: ${PHC2SYS_PID} (Logs: /var/log/phc2sys.log)"

echo "================================================================================"
echo "  [HFT INFRA] PTP Synchronization Active. Verifying MiFID II compliance..."
echo "================================================================================"
"$(dirname "$0")/verify_mifid_clock_sync.sh" "${NIC}"
