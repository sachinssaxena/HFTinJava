#!/usr/bin/env bash
# ==============================================================================
# Script: setup_hugepages.sh
# Purpose: Configure dedicated 1GB HugePages (or 2MB fallback) on NUMA Node 0,
#          mount hugepages hugetlbfs filesystem, and strictly disable THP.
# Target: Ultra-Low Latency Java Off-Heap & Ring Buffer Memory Storage
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT INFRA] HugePage Allocation & THP Jitter Elimination"
echo "================================================================================"

if [ "$EUID" -ne 0 ]; then
    echo "[ERROR] This script must be executed as root. Run with sudo." >&2
    exit 1
fi

TARGET_HUGEPAGES_1G=16
NUMA_NODE=0

# 1. Strictly Disable Transparent Huge Pages (THP)
# THP triggers khugepaged daemon compaction and memory page collapses in the background,
# resulting in non-deterministic 2ms to 20ms GC-like pauses on memory writes.
echo "[INFO] Disabling Transparent Huge Pages (THP)..."
if [ -f "/sys/kernel/mm/transparent_hugepage/enabled" ]; then
    echo never > /sys/kernel/mm/transparent_hugepage/enabled
fi
if [ -f "/sys/kernel/mm/transparent_hugepage/defrag" ]; then
    echo never > /sys/kernel/mm/transparent_hugepage/defrag
fi
echo "[SUCCESS] Transparent Huge Pages disabled (never)."

# 2. Configure 1GB HugePages
HUGE_1G_DIR="/sys/devices/system/node/node${NUMA_NODE}/hugepages/hugepages-1048576kB"
GLOBAL_1G_DIR="/sys/kernel/mm/hugepages/hugepages-1048576kB"

if [ -d "${HUGE_1G_DIR}" ]; then
    echo "[INFO] Configuring ${TARGET_HUGEPAGES_1G} x 1GB HugePages on NUMA Node ${NUMA_NODE}..."
    echo "${TARGET_HUGEPAGES_1G}" > "${HUGE_1G_DIR}/nr_hugepages"
elif [ -d "${GLOBAL_1G_DIR}" ]; then
    echo "[INFO] Configuring ${TARGET_HUGEPAGES_1G} x 1GB HugePages globally..."
    echo "${TARGET_HUGEPAGES_1G}" > "${GLOBAL_1G_DIR}/nr_hugepages"
else
    echo "[WARN] 1GB HugePages not supported by CPU or kernel boot parameter."
    echo "       Falling back to 2MB HugePages (8192 x 2MB = 16GB)..."
    
    HUGE_2M_DIR="/sys/devices/system/node/node${NUMA_NODE}/hugepages/hugepages-2048kB"
    GLOBAL_2M_DIR="/sys/kernel/mm/hugepages/hugepages-2048kB"
    TARGET_2M_PAGES=8192
    
    if [ -d "${HUGE_2M_DIR}" ]; then
        echo "${TARGET_2M_PAGES}" > "${HUGE_2M_DIR}/nr_hugepages"
    elif [ -d "${GLOBAL_2M_DIR}" ]; then
        echo "${TARGET_2M_PAGES}" > "${GLOBAL_2M_DIR}/nr_hugepages"
    fi
fi

# 3. Mount hugetlbfs Filesystem
MOUNT_1G="/dev/hugepages1G"
MOUNT_2M="/dev/hugepages2M"

mkdir -p "${MOUNT_1G}" "${MOUNT_2M}"

if ! mountpoint -q "${MOUNT_1G}"; then
    mount -t hugetlbfs -o pagesize=1G nodev "${MOUNT_1G}" 2>/dev/null || true
fi

if ! mountpoint -q "${MOUNT_2M}"; then
    mount -t hugetlbfs -o pagesize=2M nodev "${MOUNT_2M}" 2>/dev/null || true
fi

chmod 777 "${MOUNT_1G}" "${MOUNT_2M}" 2>/dev/null || true

# 4. Verification
echo "[INFO] Verifying Memory Allocation from /proc/meminfo:"
grep -i huge /proc/meminfo

echo "================================================================================"
echo "  [HFT INFRA] HugePage Configuration Complete."
echo "================================================================================"
