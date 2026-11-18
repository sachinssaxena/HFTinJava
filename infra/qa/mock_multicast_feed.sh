#!/usr/bin/env bash
# ==============================================================================
# Script: mock_multicast_feed.sh
# Purpose: High-speed synthetic dual-feed UDP multicast generator for @qa
# Feeds:
#   Feed A: 239.1.1.1:10001
#   Feed B: 239.1.1.2:10002
# Features: Simulates synchronized arrival, packet drops on Feed A, and burst bursts.
# ==============================================================================

set -euo pipefail

FEED_A_IP="239.1.1.1"
FEED_A_PORT="10001"
FEED_B_IP="239.1.1.2"
FEED_B_PORT="10002"

PACKET_COUNT="${1:-10000}"
DROP_EVERY="${2:-500}" # Drop every 500th packet on Feed A to test gap fallback

echo "================================================================================"
echo "  [HFT QA] Starting Synthetic Dual-Feed Multicast Packet Generator"
echo "  Packets: ${PACKET_COUNT} | Drop on Feed A every ${DROP_EVERY} packets"
echo "================================================================================"

# Verify python3 is available for packet generation
if ! command -v python3 &> /dev/null; then
    echo "[ERROR] python3 is required to generate binary ITCH packets." >&2
    exit 1
fi

python3 - << EOF
import socket
import struct
import time
import sys

feed_a_ip = "${FEED_A_IP}"
feed_a_port = int("${FEED_A_PORT}")
feed_b_ip = "${FEED_B_IP}"
feed_b_port = int("${FEED_B_PORT}")
total_packets = int("${PACKET_COUNT}")
drop_interval = int("${DROP_EVERY}")

sock_a = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock_a.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)

sock_b = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock_b.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)

print(f"[INFO] Streaming {total_packets} ITCH 'Add Order' packets to Feed A & B...")

# Construct 36-byte NASDAQ ITCH 'A' Add Order packet:
# struct format: >c H H 6s Q c I 8s I
# Type: 'A' (1 byte)
# Locate: 1 (2 bytes)
# Tracking: 0 (2 bytes)
# Timestamp: 6 bytes (48-bit nanoseconds)
# OrderReferenceNumber: 8 bytes
# Side: 'B' (1 byte)
# Shares: 4 bytes
# Stock: 8 bytes
# Price: 4 bytes (scaled 10^4)

stock_symbol = b"AAPL    "
price_scaled = 1500000 # $150.0000

for seq in range(1, total_packets + 1):
    now_nanos = time.time_ns()
    ts_bytes = (now_nanos & 0xFFFFFFFFFFFF).to_bytes(6, byteorder='big')
    
    # 36 bytes payload
    payload = struct.pack(
        ">cHH6sQcI8sI",
        b'A',
        1,
        0,
        ts_bytes,
        seq + 100000,
        b'B',
        100,
        stock_symbol,
        price_scaled
    )
    
    # Prepend 8-byte sequence header (MoldUDP64 style)
    header = struct.pack(">Q", seq)
    packet = header + payload

    # Send Feed B always
    sock_b.sendto(packet, (feed_b_ip, feed_b_port))

    # Send Feed A unless dropped to simulate single-feed packet drop
    if seq % drop_interval != 0:
        sock_a.sendto(packet, (feed_a_ip, feed_a_port))

print(f"[SUCCESS] Broadcasted {total_packets} packets across Feed A and Feed B.")
EOF

echo "================================================================================"
echo "  [HFT QA] Mock Multicast Stream Completed."
echo "================================================================================"
