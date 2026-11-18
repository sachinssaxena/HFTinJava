#!/usr/bin/env bash
# ==============================================================================
# Script: entrypoint.sh
# Purpose: Container initialization entrypoint with privilege verification
# ==============================================================================

set -euo pipefail

echo "================================================================================"
echo "  [HFT CONTAINER] Starting Ultra-Low Latency Java HFT Platform..."
echo "================================================================================"

# Verify Real-Time Privileges
if capsh --print 2>/dev/null | grep -q "cap_sys_nice"; then
    echo "[SUCCESS] CAP_SYS_NICE capability granted (thread priority & core pinning enabled)."
else
    echo "[WARN] CAP_SYS_NICE missing! Thread affinity and RT scheduling may fail."
fi

if capsh --print 2>/dev/null | grep -q "cap_net_raw"; then
    echo "[SUCCESS] CAP_NET_RAW capability granted (kernel-bypass / raw socket access enabled)."
else
    echo "[WARN] CAP_NET_RAW missing! Packet interception or Onload may fail."
fi

# Route command
MODE="${1:-production}"

case "${MODE}" in
    production)
        echo "[INFO] Starting Production Engine with Epsilon GC..."
        exec /app/infra/jvm/launch_production.sh /app/hft-platform.jar
        ;;
    zgc)
        echo "[INFO] Starting Production Engine with Generational ZGC..."
        exec /app/infra/jvm/launch_production_zgc.sh /app/hft-platform.jar
        ;;
    warmup)
        echo "[INFO] Executing Pre-Market Warmup..."
        exec /app/infra/jvm/launch_warmup.sh
        ;;
    *)
        exec "$@"
        ;;
esac
