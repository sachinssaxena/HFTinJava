#!/usr/bin/env bash
# ==============================================================================
# Script: onload_env.sh
# Purpose: Environment variable configuration for Solarflare OpenOnload & EF_VI
# Target: Kernel bypass, zero-copy socket acceleration, hardware timestamping
# Usage: source infra/network/onload_env.sh
#        onload --profile=latency java ...
# ==============================================================================

echo "[HFT INFRA] Exporting Solarflare OpenOnload & EF_VI Ultra-Low Latency Environment Profile..."

# 1. Ring Buffer Sizes
# Maximize hardware RX and TX descriptor ring sizes to 4096 to prevent packet drops
# during microbursts while running in kernel-bypass mode.
export EF_VI_RX_RING_SIZE=4096
export EF_VI_TX_RING_SIZE=4096

# 2. Polling & Spinning vs Interrupts (Zero-Interrupt Mode)
# - EF_POLL_USEC=-1: Never fall back to interrupts. Keep thread spinning in user space
#   polling the hardware completion queue.
# - EF_SPIN_USEC=-1: Busy-spin indefinitely on transmit queue space and packet arrival.
# - EF_INT_DRIVEN=0: Disable interrupt-driven notification entirely.
export EF_POLL_USEC=-1
export EF_SPIN_USEC=-1
export EF_INT_DRIVEN=0

# 3. Stack Isolation & Memory Sizing
# - EF_STACK_PER_THREAD=1: Dedicated Onload network stack per pinned application thread.
#   Eliminates all cross-thread locking on the network stack!
# - EF_MAX_PACKETS=65536: Allocates generous packet descriptor pool.
# - EF_PACKET_BUFFER_MODE=1: Optimized packet buffer layout for low latency.
export EF_STACK_PER_THREAD=1
export EF_MAX_PACKETS=65536
export EF_PACKET_BUFFER_MODE=1

# 4. Hardware Nanosecond Timestamping (MiFID II RTS 25)
# Enable Solarflare hardware RX & TX timestamping directly into packet headers.
export EF_RX_TIMESTAMPING=1
export EF_TX_TIMESTAMPING=1

# 5. Socket Acceleration & Kernel Bypass Behavior
# - EF_FORCE_SEND_NOW=1: Bypasses packet batching, immediately pushes packet out to the wire.
# - EF_HIGH_OUTPUT_PRIORITY=1: Prioritizes transmission on PCIe bus.
# - EF_TCP_SHARED_LOCAL_PORTS=1: Reduces TCP handshake latency.
# - EF_NO_FAIL=1: Prevents fallback to kernel if onload resource exhaustion occurs (ensures deterministic failure).
export EF_FORCE_SEND_NOW=1
export EF_HIGH_OUTPUT_PRIORITY=1
export EF_NO_FAIL=1
export EF_TCP_SHARED_LOCAL_PORTS=1

# 6. Multicast Ingestion Tuning (Market Data Feeds A & B)
# Allows simultaneous hardware filtering and zero-copy packet delivery for ITCH/SBE multicast.
export EF_MULTICAST_RECV=1
export EF_UDP_RECV_SPIN=1

# 7. CPU Affinity Alignment for Onload Stacks
# Pins Onload network processing directly to trading cores (Node 0)
export EF_CPUS="2,3,4,5"

echo "[HFT INFRA] Solarflare OpenOnload Environment Configured: EF_POLL_USEC=-1, EF_STACK_PER_THREAD=1, EF_RX_TIMESTAMPING=1"
