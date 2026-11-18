# Ultra-Low Latency Infrastructure & Systems Engineering Guide
## High-Frequency Trading (HFT) Platform (Java 21 LTS)

**Author:** Ultra-Low Latency Infrastructure & Systems Engineer (`@infra`)  
**Stakeholders:** Technical Architect (`@tech-architect`), Developer (`@dev`), QA (`@qa`), Product Owner (`@product-owner`)  
**Platform Target:** Sub-microsecond tick-to-trade, zero-allocation steady state, deterministic kernel-bypass execution.

---

## 1. Directory Structure

```
infra/
├── README.md                                 # Comprehensive guide & architecture documentation
├── os-tuning/
│   ├── grub.conf                             # Linux kernel boot parameters (isolcpus, nohz_full, idle=poll, hugepages)
│   ├── 99-hft-sysctl.conf                    # Sysctl network, memory, and scheduler optimizations
│   ├── setup_cpu_and_numa.sh                 # CPU governor, C-states, turbo jitter elimination, NUMA topology
│   ├── setup_hugepages.sh                    # 1GB HugePages allocation and Transparent Huge Pages (THP) disabling
│   └── setup_irq_affinity.sh                 # Hardware interrupt steering away from isolated trading cores (2-5)
├── network/
│   ├── onload_env.sh                         # Solarflare OpenOnload & EF_VI kernel-bypass environment variables
│   ├── tune_nic.sh                           # Physical NIC tuning (4096 ring buffers, coalescing off, offloads off)
│   ├── ptp4l.conf                            # IEEE 1588-2008 PTP configuration for Solarflare hardware clock (PHC)
│   ├── setup_ptp.sh                          # PTP daemon & phc2sys launcher for MiFID II RTS 25 compliance
│   └── verify_mifid_clock_sync.sh            # Live PTP clock drift monitor & MiFID II tolerance auditor
├── jvm/
│   ├── hft-jvm-opts.conf                     # Production Java 21 LTS flags (Epsilon GC, pre-touch, inlining, JFR)
│   ├── launch_production.sh                  # Production launch wrapper (Epsilon GC + NUMA Node 0 + taskset)
│   ├── launch_production_zgc.sh              # Fallback production launcher (Java 21 Generational ZGC)
│   └── launch_warmup.sh                      # Pre-market JIT warmup driver (200k synthetic market cycles)
├── docker/
│   ├── Dockerfile                            # Multi-stage production container with realtime privileges
│   ├── docker-compose.yml                    # Docker Compose spec (host networking, CAP_SYS_NICE, CAP_NET_RAW)
│   └── entrypoint.sh                         # Container startup entrypoint with privilege assertions
├── qa/
│   ├── setup_qa_benchmark_env.sh             # Reproducible isolated benchmarking environment for @qa
│   └── mock_multicast_feed.sh                # High-speed synthetic dual-feed UDP multicast generator
└── deploy/
    ├── preflight_check.sh                    # Pre-market hardware, kernel, PTP, and NIC readiness verification
    ├── deploy_production.sh                  # Master orchestration script
    └── hft-tune.service                      # Systemd service unit for boot-time OS tuning
```

---

## 2. Hardware Topology & Physical Core Pinning (NUMA Node 0)

To achieve median tick-to-trade latency under 100ns and p99 latency under 200ns, hardware resources are statically partitioned. In Sprint 2, the core pinning topology scales to accommodate multi-venue gateways (NASDAQ OUCH and CME iLink 3), real-time drop copy, and deterministic audit journaling on **NUMA Node 0**:

| Logical Core | Component / Workload | Isolation Mode | Execution Model |
| :--- | :--- | :--- | :--- |
| **Cores 0 - 1** | OS, Hardware IRQs, Network Drivers, JIT Compiler | Housekeeping (non-isolated) | Standard Linux CFS Scheduler |
| **Core 2** | Multi-Venue Market Data Ingestion & Dual-Feed Arbitration (ITCH/MDP) | `isolcpus`, `nohz_full`, `rcu_nocbs` | Pinned busy-spin (`Thread.onSpinWait()`) |
| **Core 3** | Consolidated Trading Engine (CBBO + Queue-Aware Quoting + StatArb + SOR + Risk) | `isolcpus`, `nohz_full`, `rcu_nocbs` | Pinned busy-spin (`Thread.onSpinWait()`) |
| **Core 4** | NASDAQ OUCH 5.0 TCP Gateway & Wire Serialization | `isolcpus`, `nohz_full`, `rcu_nocbs` | Pinned busy-spin (`Thread.onSpinWait()`) |
| **Core 5** | CME iLink 3 Binary Gateway (SBE over TCP with FTI) | `isolcpus`, `nohz_full`, `rcu_nocbs` | Pinned busy-spin (`Thread.onSpinWait()`) |
| **Core 6** | Real-Time Drop Copy (FIX 4.4 / binary) & Telemetry (`HdrHistogram`) | `isolcpus`, `nohz_full`, `rcu_nocbs` | Off-path SPSC tap consumer |
| **Core 7** | MiFID II RTS 25 Deterministic Audit Journaling & TCP Gap Recovery | `isolcpus`, `nohz_full`, `rcu_nocbs` | Off-path SPSC tap consumer |

---

## 3. Linux Kernel & Operating System Tuning

### 3.1 Kernel Boot Parameters (`infra/os-tuning/grub.conf`)
- `isolcpus=managed_irq,domain,nohz,2-5`: Completely removes trading cores from the Linux scheduler domain and managed interrupts.
- `nohz_full=2-5`: Operates isolated cores in adaptive-ticks mode; eliminates the 1000Hz kernel timer interrupt when 1 thread is active.
- `rcu_nocbs=2-5`: Offloads RCU callback processing from trading cores to housekeeping cores (0-1).
- `idle=poll`: Prevents CPU cores from entering sleep states; forces a continuous busy-polling idle loop (0ns wake latency).
- `intel_idle.max_cstate=0` & `processor.max_cstate=0`: Restricts processor states strictly to C0 active execution.
- `default_hugepagesz=1G hugepagesz=1G hugepages=16`: Pre-allocates 16x 1GB HugePages at boot time, eliminating TLB misses.
- `transparent_hugepage=never`: Strictly disables Transparent Huge Pages (THP) and khugepaged compaction stalls.
- `tsc=reliable clocksource=tsc`: Sets invariant Time Stamp Counter as primary system clocksource.

### 3.2 Network Stack & Virtual Memory (`infra/os-tuning/99-hft-sysctl.conf`)
- `vm.swappiness = 0`: Disables memory paging to disk.
- `net.core.rmem_max = 67108864` & `net.core.wmem_max = 67108864`: Sockets allocated 64MB buffers to absorb microbursts.
- `net.core.busy_read = 50` & `net.core.busy_poll = 50`: Enables low-latency socket busy polling.
- `net.ipv4.tcp_low_latency = 1`: Instructs Linux TCP stack to give priority to low latency over throughput.
- `kernel.sched_rt_runtime_us = -1`: Removes throttling on real-time SCHED_FIFO tasks.

---

## 4. Network Acceleration & Kernel Bypass

### 4.1 Solarflare OpenOnload & EF_VI (`infra/network/onload_env.sh`)
- `EF_VI_RX_RING_SIZE=4096` / `EF_VI_TX_RING_SIZE=4096`: Maximum descriptor ring sizes.
- `EF_POLL_USEC=-1` & `EF_SPIN_USEC=-1`: Infinite user-space busy-spin polling; zero interrupt overhead.
- `EF_STACK_PER_THREAD=1`: Dedicated Onload network stack per thread; eliminates cross-thread network locking.
- `EF_INT_DRIVEN=0`: Disables interrupt-driven notification entirely.
- `EF_FORCE_SEND_NOW=1`: Disables packet batching; forces immediate PCIe wire transmission.

### 4.2 NIC Hardware Tuning (`infra/network/tune_nic.sh`)
- `ethtool -G eth0 rx 4096 tx 4096`: Expands hardware FIFO queues.
- `ethtool -C eth0 adaptive-rx off rx-usecs 0 rx-frames 0`: Disables all interrupt coalescing.
- `ethtool -K eth0 gro off lro off tso off gso off`: Disables packet aggregation to eliminate jitter.

### 4.3 MiFID II RTS 25 PTP Clock Synchronization (`infra/network/setup_ptp.sh`)
- Complies with **MiFID II RTS 25 Table 2** (maximum divergence of 100 microseconds from UTC for algorithmic trading).
- Disciplines the Solarflare Physical Hardware Clock (PHC, `/dev/ptp0`) via `ptp4l` connected to GPS Grandmasters.
- Disciplines Linux `CLOCK_REALTIME` via `phc2sys` with sub-microsecond steady-state divergence (< 500ns).
- Live verification via `infra/network/verify_mifid_clock_sync.sh`.

---

## 5. JVM Production Configuration (Java 21 LTS)

### 5.1 Zero-Allocation Steady State (`-XX:+UseEpsilonGC`)
Because the trading engine maintains **strictly 0 bytes heap allocation per second** in steady state, the platform is launched with **Epsilon GC (The No-Op Garbage Collector)**:
- Eliminates GC pause times completely (**0.0 ms pause time**).
- Eliminates memory write barriers.
- Eliminates safepoint bias revocation pauses (`-XX:GuaranteedSafepointInterval=0 -XX:-UseBiasedLocking`).

### 5.2 Pre-Market JIT Compilation Warmup (`infra/jvm/launch_warmup.sh`)
Prior to market open, 200,000 synthetic market ticks and order cycles are driven through the engine:
- Forces C2 Tier 4 JIT compilation of all critical path methods (`-XX:CompileThreshold=10000`).
- Warms branch target buffers (BTB) and CPU instruction caches (L1i).
- Pre-touches all heap and off-heap memory pages (`-XX:+AlwaysPreTouch`).

---

## 6. Docker & Container Deployment

The trading platform can be packaged and deployed in high-performance containerized environments using `infra/docker/Dockerfile` and `docker-compose.yml`:
- **Host Networking (`network_mode: "host"`)**: Bypasses Docker virtual network bridges and iptables overhead.
- **Capabilities**:
  - `CAP_SYS_NICE`: Core pinning and real-time FIFO thread scheduling.
  - `CAP_NET_RAW` & `CAP_NET_ADMIN`: Kernel-bypass and raw socket access.
  - `CAP_IPC_LOCK`: Memory page locking (`mlockall`).
- **Resource Limits**:
  - `memlock: -1` (unlimited memory locking).
  - `rtprio: 99` (real-time priority).
- **Device Passthrough**: `/dev/ptp0`, `/dev/hugepages1G`, `/dev/cpu_dma_latency`.

---

## 7. Operational Workflow

### Step 1: Pre-Flight Readiness Verification
```bash
sudo bash infra/deploy/preflight_check.sh
```

### Step 2: Automated Production Deployment
```bash
sudo bash infra/deploy/deploy_production.sh
```

### Step 3: MiFID II Clock Drift Audit
```bash
sudo bash infra/network/verify_mifid_clock_sync.sh eth0
```
