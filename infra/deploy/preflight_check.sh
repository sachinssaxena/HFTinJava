#!/usr/bin/env bash
# ==============================================================================
# Script: preflight_check.sh
# Purpose: Pre-Market Infrastructure Verification & Diagnostic Health Check
# Target: Production Bare-Metal / Colocated Trading Servers
# Returns: 0 if all checks pass; non-zero if any critical parameter fails.
# ==============================================================================

set -euo pipefail

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

check_pass() {
    echo -e "  [\033[32mPASS\033[0m] $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

check_warn() {
    echo -e "  [\033[33mWARN\033[0m] $1"
    WARN_COUNT=$((WARN_COUNT + 1))
}

check_fail() {
    echo -e "  [\033[31mFAIL\033[0m] $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

echo "================================================================================"
echo "  ULTRA-LOW LATENCY INFRASTRUCTURE PRE-FLIGHT READINESS CHECKLIST"
echo "================================================================================"

# 1. CPU Governor Check
echo "[1] Checking CPU Scaling Governors..."
if [ -f "/sys/devices/system/cpu/cpu2/cpufreq/scaling_governor" ]; then
    GOV=$(cat /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor)
    if [ "$GOV" = "performance" ]; then
        check_pass "Core 2 governor is 'performance'"
    else
        check_fail "Core 2 governor is '${GOV}' (expected: 'performance')"
    fi
else
    check_warn "CPU scaling governor interface not found (virtualized/containerized)"
fi

# 2. CPU C-State Check
echo "[2] Checking CPU Idle C-States..."
if [ -f "/sys/devices/system/cpu/cpu2/cpuidle/state1/disable" ]; then
    STATE1=$(cat /sys/devices/system/cpu/cpu2/cpuidle/state1/disable)
    if [ "$STATE1" = "1" ]; then
        check_pass "CPU C-states disabled for Core 2 (C0 active lock)"
    else
        check_warn "CPU C-states not disabled (state1/disable = ${STATE1})"
    fi
else
    check_warn "CPU cpuidle sysfs not accessible"
fi

# 3. Kernel Isolated Cores Check
echo "[3] Checking Kernel Core Isolation (isolcpus)..."
if [ -f "/sys/devices/system/cpu/isolated" ]; then
    ISOL=$(cat /sys/devices/system/cpu/isolated)
    if [[ "$ISOL" == *"2-5"* || "$ISOL" == *"2,3,4,5"* ]]; then
        check_pass "Trading cores 2-5 are strictly isolated (isolated=${ISOL})"
    else
        check_warn "Trading cores 2-5 not in isolated list (isolated='${ISOL}')"
    fi
else
    check_warn "Kernel /sys/devices/system/cpu/isolated not present"
fi

# 4. HugePages Verification
echo "[4] Checking HugePages Configuration..."
TOTAL_HUGE=$(grep "HugePages_Total" /proc/meminfo | awk '{print $2}' || echo "0")
if [ "$TOTAL_HUGE" -gt 0 ]; then
    check_pass "HugePages allocated: ${TOTAL_HUGE} pages"
else
    check_fail "Zero HugePages allocated in /proc/meminfo"
fi

# 5. Transparent Huge Pages (THP) Check
echo "[5] Checking Transparent Huge Pages (THP)..."
if [ -f "/sys/kernel/mm/transparent_hugepage/enabled" ]; then
    THP=$(cat /sys/kernel/mm/transparent_hugepage/enabled)
    if [[ "$THP" == *"[never]"* ]]; then
        check_pass "Transparent Huge Pages is 'never'"
    else
        check_fail "Transparent Huge Pages is enabled (${THP})"
    fi
else
    check_warn "THP sysfs interface not available"
fi

# 6. Swap & Memory Subsystem
echo "[6] Checking Swap State..."
SWAP_TOTAL=$(grep "SwapTotal" /proc/meminfo | awk '{print $2}' || echo "0")
if [ "$SWAP_TOTAL" -eq 0 ]; then
    check_pass "Swap space completely disabled (0 kB)"
else
    SWAPPINESS=$(cat /proc/sys/vm/swappiness 2>/dev/null || echo "60")
    if [ "$SWAPPINESS" -eq 0 ]; then
        check_pass "Swap space exists but vm.swappiness is 0"
    else
        check_warn "Swap is active (${SWAP_TOTAL} kB) and vm.swappiness is ${SWAPPINESS}"
    fi
fi

# 7. Interrupt Balancing (irqbalance)
echo "[7] Checking irqbalance Service..."
if command -v systemctl &> /dev/null; then
    if systemctl is-active --quiet irqbalance; then
        check_fail "irqbalance daemon is ACTIVE (will migrate IRQs to trading cores)"
    else
        check_pass "irqbalance daemon is inactive/disabled"
    fi
else
    check_warn "systemctl not available"
fi

# 8. NIC Ring Buffer & Coalescing Check
echo "[8] Checking NIC Configuration (eth0)..."
PRIMARY_NIC="eth0"
if command -v ethtool &> /dev/null && ip link show "${PRIMARY_NIC}" &> /dev/null; then
    RX_RING=$(ethtool -g "${PRIMARY_NIC}" 2>/dev/null | awk '/Current hardware settings:/,/^$/' | grep "RX:" | awk '{print $2}' || echo "0")
    if [ "$RX_RING" -ge 4096 ]; then
        check_pass "${PRIMARY_NIC} RX ring buffer size is ${RX_RING}"
    else
        check_warn "${PRIMARY_NIC} RX ring buffer size is ${RX_RING} (< 4096)"
    fi
    
    ADAPTIVE_RX=$(ethtool -c "${PRIMARY_NIC}" 2>/dev/null | grep "Adaptive RX:" | awk '{print $3}' || echo "on")
    if [ "$ADAPTIVE_RX" = "off" ]; then
        check_pass "${PRIMARY_NIC} Adaptive RX coalescing is OFF"
    else
        check_warn "${PRIMARY_NIC} Adaptive RX coalescing is ${ADAPTIVE_RX}"
    fi
else
    check_warn "ethtool check skipped for ${PRIMARY_NIC}"
fi

# 9. Java 21 LTS Runtime
echo "[9] Checking Java Runtime..."
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -n 1)
    if [[ "$JAVA_VER" == *"21."* ]]; then
        check_pass "Java 21 LTS detected (${JAVA_VER})"
    else
        check_warn "Java version is not 21: ${JAVA_VER}"
    fi
else
    check_fail "java executable not found in PATH"
fi

echo "================================================================================"
echo "  PRE-FLIGHT SUMMARY: ${PASS_COUNT} PASSED, ${WARN_COUNT} WARNINGS, ${FAIL_COUNT} FAILURES"
echo "================================================================================"

if [ "$FAIL_COUNT" -gt 0 ]; then
    echo "[ABORT] System fails critical pre-flight checks. Do NOT start trading engine." >&2
    exit 1
else
    echo "[READY] System meets ultra-low latency infrastructure criteria. Ready for launch."
    exit 0
fi
