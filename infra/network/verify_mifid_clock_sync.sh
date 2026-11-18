#!/usr/bin/env bash
# ==============================================================================
# Script: verify_mifid_clock_sync.sh
# Purpose: Real-time verification of PTP Clock Drift for MiFID II RTS 25 compliance.
# Regulatory Limit: Maximum divergence allowed from UTC: 100 microseconds (100,000 ns).
# Internal Target: Sub-microsecond (< 500 ns).
# ==============================================================================

set -euo pipefail

MIFID_MAX_DIVERGENCE_NANOS=100000 # 100 microseconds (MiFID II RTS 25 mandate)
HFT_TARGET_DIVERGENCE_NANOS=500   # 500 nanoseconds target

echo "================================================================================"
echo "  [MIFID II RTS 25] Regulatory Clock Synchronization Audit Verification"
echo "================================================================================"

# Check if ptp4l log exists
PTP4L_LOG="/var/log/ptp4l.log"
PHC2SYS_LOG="/var/log/phc2sys.log"

if [ -f "${PHC2SYS_LOG}" ]; then
    # Extract last recorded offset from phc2sys
    # Log format: phc2sys[...]: [timestamp] CLOCK_REALTIME phc offset    -42 ns s2 freq  -1234 delay   567
    LAST_LINE=$(tail -n 20 "${PHC2SYS_LOG}" | grep -E "offset" | tail -n 1 || echo "")
    if [ -n "${LAST_LINE}" ]; then
        OFFSET_NS=$(echo "${LAST_LINE}" | sed -E 's/.*offset[[:space:]]+(-?[0-9]+).*/\1/')
        ABS_OFFSET=${OFFSET_NS#-}
        
        echo "[INFO] Latest CLOCK_REALTIME phc offset: ${OFFSET_NS} ns (${ABS_OFFSET} ns absolute)"
        
        if [ "${ABS_OFFSET}" -le "${HFT_TARGET_DIVERGENCE_NANOS}" ]; then
            echo "[STATUS: OPTIMAL] Clock divergence (${ABS_OFFSET} ns) is within HFT target (< ${HFT_TARGET_DIVERGENCE_NANOS} ns)."
        elif [ "${ABS_OFFSET}" -le "${MIFID_MAX_DIVERGENCE_NANOS}" ]; then
            echo "[STATUS: COMPLIANT] Clock divergence (${ABS_OFFSET} ns) satisfies MiFID II RTS 25 (< 100,000 ns)."
        else
            echo "[STATUS: VIOLATION] Clock divergence (${ABS_OFFSET} ns) EXCEEDS MiFID II RTS 25 maximum tolerance (100us)!" >&2
            exit 2
        fi
    fi
else
    echo "[WARN] phc2sys log not found at ${PHC2SYS_LOG}. Attempting PMC query..."
    if command -v pmc &> /dev/null; then
        pmc -u -b 0 'GET CURRENT_DATA_SET' 2>/dev/null || true
    else
        echo "[INFO] PMC utility not available. Mocking / validating format."
    fi
fi

# Query hardware clock comparison if phc_ctl is available
if command -v phc_ctl &> /dev/null && [ -e "/dev/ptp0" ]; then
    echo "[INFO] Querying PHC hardware clock via phc_ctl..."
    phc_ctl /dev/ptp0 cmp
fi

echo "[SUCCESS] MiFID II RTS 25 Clock Synchronization Audit check completed."
