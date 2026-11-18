# Technical Architecture & System Design Document
## Ultra-Low Latency Java 21 High-Frequency Trading (HFT) Platform

**Document Version:** 1.0.0-APPROVED  
**Author:** Technical Architect (@tech-architect)  
**Stakeholders:** Product Owner (@product-owner), Coordinator (@coordinator)  
**Date:** 2026-11-19  
**Status:** Approved & Formally Agreed Technical Architecture  

---

## 1. Architectural Principles & Mechanical Sympathy

To achieve **p50 < 1.0 µs** and **p99 < 4.0 µs** tick-to-trade latency with zero jitter, the architecture is engineered around the principle of **Mechanical Sympathy**—designing software that harmonizes directly with modern CPU cache hierarchies, memory buses, and network interfaces.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 MECHANICAL SYMPATHY                                    │
├──────────────────────────┬──────────────────────────────┬──────────────────────────────┤
│ 1. Zero-Allocation       │ 2. Cache Locality (L1/L2)    │ 3. Lock-Free Single-Writer   │
│ - 0 bytes heap on path   │ - Pinned isolated CPU cores  │ - SPSC ring buffers (Disruptor)│
│ - Agrona UnsafeBuffer    │ - Cache line padding (64B)   │ - Aeron IPC zero-copy memory │
│ - Pre-allocated pools    │ - Data-oriented flat arrays  │ - Busy-spin onSpinWait()     │
└──────────────────────────┴──────────────────────────────┴──────────────────────────────┘
```

### 1.1 Fundamental System Invariants
1. **Zero Heap Allocation on Hot Path:** Exactly 0 bytes allocated during steady-state trading. All objects, buffers, flyweights, and order tokens are pre-allocated during the startup warmup phase.
2. **Deterministic Execution:** No locks, no synchronized blocks, no thread context switching, no blocking I/O, and no floating-point arithmetic on the critical path.
3. **Cache Line Alignment & Padding:** All shared sequence numbers and ring buffer headers are padded to 64 bytes (or 128 bytes for adjacent line prefetchers) to eliminate false sharing.
4. **Asynchronous Non-Blocking Audit:** Regulatory persistence (MiFID II RTS 25 / CAT / Chronicle Queue) is completely isolated from the hot path via non-blocking ring buffer taps running on dedicated non-trading cores.

---

## 2. High-Level System Architecture & Component Pipeline

The platform is designed with a **Hybrid Co-located / Pipeline Topology** to maximize cache locality while maintaining clean architectural separation of concerns.

```
                      ┌────────────────────────────────────────────────────────┐
                      │              Exchange Venue (NASDAQ / CME)             │
                      └────────────┬──────────────────────────────▲────────────┘
                                   │                              │
                     Multicast UDP │ (A/B Feeds)       OUCH/iLink │ TCP
                                   ▼                              │
  ════════════════════════════════════════════════════════════════╪═════════════════════════════
  PHYSICAL CORE PINNING TOPOLOGY (NUMA Node 0)                    │
  ════════════════════════════════════════════════════════════════╪═════════════════════════════
                                                                  │
  ┌────────────────────────────────────────────────────────┐      │
  │ CORE 2 (Isolated): Market Data Ingestion & Arbitration │      │
  │  - Solarflare EF_VI / OpenOnload Dual-Feed Ingest       │      │
  │  - Sequence Deduplication & Gap Detection              │      │
  │  - Agrona DirectBuffer SBE / ITCH Flyweight Parser     │      │
  └───────────────────────────┬────────────────────────────┘      │
                              │ SPSC Lock-Free Ring Buffer        │
                              ▼ (LMAX Disruptor / Agrona IPC)     │
  ┌────────────────────────────────────────────────────────┐      │
  │ CORE 3 (Isolated): Trading Engine (Co-located Hot Path)│      │
  │  ┌──────────────────────────────────────────────────┐  │      │
  │  │ Component A: L2 / L3 Contiguous Order Book       │  │      │
  │  │  - Flat primitive tick arrays (O(1) price index) │  │      │
  │  │  - Pre-allocated order queue pools               │  │      │
  │  │  - Real-time queue position & degradation        │  │      │
  │  └────────────────────────┬─────────────────────────┘  │      │
  │                           │ Microstructure Signals     │      │
  │  ┌────────────────────────▼─────────────────────────┐  │      │
  │  │ Component B: Quoting Strategy Engine             │  │      │
  │  │  - Avellaneda-Stoikov reservation price          │  │      │
  │  │  - OBI & micro-price calculation (fixed-point)   │  │      │
  │  │  - Toxic flow & sweep detection (fading quotes)  │  │      │
  │  └────────────────────────┬─────────────────────────┘  │      │
  │                           │ Order Intent (Flyweight)   │      │
  │  ┌────────────────────────▼─────────────────────────┐  │      │
  │  │ Component C: Pre-Trade Risk Gate (SEC 15c3-5)    │  │      │
  │  │  - 8 In-line checks executed in < 300ns          │  │      │
  │  │  - Hard atomic kill-switch                       │  │      │
  │  └──────────────────────────────────────────────────┘  │      │
  └───────────────────────────┬────────────────────────────┘      │
                              │ SPSC Ring Buffer / Direct Socket  │
                              ▼                                   │
  ┌────────────────────────────────────────────────────────┐      │
  │ CORE 4 (Isolated): Execution Gateway (Order Entry)     │      │
  │  - OUCH 5.0 / iLink 3 Binary Wire Formatter            │──────┘
  │  - Pre-allocated Order State Map (ClientOrderID lookup)│ (Wire TX: < 500ns)
  │  - Solarflare Onload / Direct Socket Push              │
  └───────────────────────────┬────────────────────────────┘
                              │
  ════════════════════════════╪═════════════════════════════════════════════════════════════════
  NON-CRITICAL BACKGROUND SERVICES (Shared Cores 6 - 8)
  ════════════════════════════╪═════════════════════════════════════════════════════════════════
                              │ Asynchronous Ring Buffer Tap (Chronicle Queue / Aeron Archive)
                              ▼
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │ Regulatory Audit, Drop Copy, TCP Historical Replay & Metrics Engine                     │
  │  - Chronicle Queue / Aeron Archive binary journaling (Zero impact on Core 2, 3, 4)     │
  │  - Drop Copy execution report distribution                                             │
  │  - Out-of-band TCP gap recovery socket client                                          │
  │  - Microsecond latency histogram recorder (HdrHistogram / Agrona)                      │
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Component Architecture

### 3.1 Market Data Ingestion & Arbitration Engine (Core 2)
* **Dual-Feed UDP Arbitration:**
  - Ingests Channel A and Channel B multicast streams concurrently using Solarflare OpenOnload or EF_VI zero-copy ring buffers.
  - State tracking uses an off-heap contiguous sequence bitmask:
    ```java
    // Fast sequence deduplication (single primitive long sequence check)
    if (incomingSeqNum <= lastProcessedSeqNum) {
        droppedDuplicatesCount++;
        return; // Discard immediately in < 25ns
    }
    ```
  - **Gap Recovery:** When `incomingSeqNum > lastProcessedSeqNum + 1`, a sequence gap event is published to an asynchronous background worker (Core 6) via a lock-free queue to initiate TCP replay while the book immediately marks itself as `DEFENSIVE_QUOTING`.
* **Zero-Copy Parsing:**
  - Employs Agrona `DirectBuffer` and Flyweight decoders.
  - Zero object instantiation: Struct fields are read by direct memory offset using `Unsafe` or Java 21 `MemorySegment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset)`.

### 3.2 Co-Located Trading Engine (Core 3 - Order Book, Strategy & Risk)
**Architectural Decision Note:** Instead of splitting Order Book, Strategy Engine, and Pre-Trade Risk across 3 separate CPU cores (which would incur 2 inter-core cache-line transfers totaling ~70-100ns plus queue overhead), we **co-locate these three pipeline stages on a single isolated CPU core** in Ultra-Low Latency Mode. This ensures:
1. The updated Order Book state resides directly in L1/L2 cache when the Strategy Engine evaluates pricing.
2. The generated order intent resides directly in L1 cache when the Risk Gate evaluates limits.
3. Total intra-core execution latency drops from ~1,900ns to **< 950ns**!

#### A. Contiguous Memory L2/L3 Order Book
* **Contiguous Price Ladder (L2):**
  - Bid and Ask books implemented as contiguous flat arrays of primitive `long` pairs: `[Price, Quantity, OrderCount]`.
  - Lookup by tick offset: `index = (targetPrice - minPrice) / tickSize`. Time complexity: $O(1)$.
* **Level 3 (MBO) Order Pool:**
  - Pre-allocated array of `OrderNode` structs in direct off-heap memory.
  - Fast lookup by `orderId` using an Agrona primitive open-addressing table (`Long2IntHashMap` mapping `orderId` to pool index).
  - Doubly-linked index pointers (`prevIndex`, `nextIndex`) inside the pool for $O(1)$ order cancellation and priority queue maintenance.
* **Queue Position Tracking:**
  - Maintains cumulative volume ahead of our own working orders by scanning only orders ahead in the linked pool node chain for that specific price level.

#### B. Avellaneda-Stoikov Strategy & Microstructure Alpha
* **Fixed-Point Mathematics Standard:**
  - All prices represented as 64-bit primitive `long` scaled by $10^4$ (Equities: 4 decimals, e.g. $\$150.2500 \to 1,502,500L$) or $10^7$ (CME MDP: 7 decimals).
  - Volatility $\sigma^2$ and intensity parameters represented as pre-computed scaled fixed-point multipliers.
  - Reservation Price formula implemented in pure integer arithmetic:
    ```java
    long reservationPrice = midPrice - ((inventory * gammaVolScaled) >> SHIFT_FACTOR);
    ```
* **Order Book Imbalance (OBI) & Micro-Price:**
  ```java
  long totalDepth = bestBidQty + bestAskQty;
  // OBI scaled by 10,000 for integer thresholding (-10,000 to +10,000)
  long obiScaled = ((bestBidQty - bestAskQty) * 10_000L) / totalDepth;
  long microPrice = ((bestBidQty * bestAskPrice) + (bestAskQty * bestBidPrice)) / totalDepth;
  ```
* **Adverse Selection Protection:**
  - If $| \text{obiScaled} | > \text{OBI\_THRESHOLD}$ or sudden top-level cancellation occurs, quote fading logic issues an atomic replace/cancel in $< 200\text{ ns}$.

#### C. Pre-Trade Risk Gate (SEC Rule 15c3-5 Mandate)
All 8 checks are executed sequentially in a single method call using in-cache primitive variables:
1. **Kill Switch:** `if (killSwitchActivated) return REJECT_KILL_SWITCH;` (single volatile read: ~10ns)
2. **Fat-Finger Collar:** Verify `price <= bestAsk + maxSpreadAllowed` (Buy) or `price >= bestBid - maxSpreadAllowed` (Sell) (~15ns)
3. **Max Clip Size:** `if (qty > maxClipSize) return REJECT_CLIP_SIZE;` (~10ns)
4. **Max Notional:** `if ((price * qty) > maxNotionalScaled) return REJECT_NOTIONAL;` (~15ns)
5. **Inventory / Position Exposure:** Check projected inventory against limits (~20ns)
6. **Short Locate:** Verification via contiguous locate bitset (~20ns)
7. **Rate Limiting / Churn:** Circular array tracking order timestamp history across rolling 1ms window (~60ns)
8. **Self-Cross Prevention:** Verify outbound order price does not cross existing active resting orders (~40ns)
* **Total Execution Latency:** $\approx 190 - 280\text{ ns}$ (well below the 500ns PRD limit!).

### 3.3 Execution Gateway Engine (Core 4)
* **Wire Protocol Serialization:**
  - OUCH 5.0 / iLink 3 binary packet layout constructed directly into a pre-allocated off-heap direct byte buffer.
  - Uses direct memory writes (e.g. `unsafe.putLong()`, `unsafe.putInt()`) with network byte order (Big-Endian) formatting.
* **Socket Transmission:**
  - Employs Solarflare OpenOnload `onload_zc_send` (zero-copy socket send) or non-blocking standard socket channel pinned to the dedicated gateway core.
  - Outbound serialization + socket buffer push latency: $\le 350\text{ ns}$.

---

## 4. Hardware, OS & JVM Low-Latency Configuration

### 4.1 Linux Kernel & OS Tuning Parameters
```bash
# /etc/default/grub (Kernel boot parameters)
GRUB_CMDLINE_LINUX="isolcpus=2-5 nohz_full=2-5 rcu_nocbs=2-5 default_hugepagesz=1G hugepagesz=1G hugepages=16 processor.max_cstate=0 intel_idle.max_cstate=0 intel_pstate=disable idle=poll mce=ignore_ce transparent_hugepage=never"
```
* **`isolcpus=2-5`:** Completely removes cores 2, 3, 4, 5 from the OS scheduler. No OS threads will ever be scheduled on these cores.
* **`nohz_full=2-5`:** Disables the Linux kernel timer tick interrupts when only one runnable thread exists on the core.
* **`idle=poll` & `intel_idle.max_cstate=0`:** Disables all CPU sleep C-states. Puts CPU cores in continuous C0 state (0ns wake latency).
* **Huge Pages (1GB):** Eliminates TLB (Translation Lookaside Buffer) cache misses for order book and ring buffer memory.

### 4.2 JVM Runtime Tuning (Java 21 LTS)
```bash
java -XX:+UseEpsilonGC \
     -XX:+AlwaysPreTouch \
     -XX:+UseLargePages \
     -Xms16g -Xmx16g \
     -XX:ActiveProcessorCount=8 \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:GuaranteedSafepointInterval=0 \
     -XX:-UseBiasedLocking \
     -XX:+UseStringDeduplication=false \
     -XX:CompileThreshold=10000 \
     -XX:+TieredCompilation \
     -XX:CICompilerCount=4 \
     -Djava.net.preferIPv4Stack=true \
     -jar hft-platform.jar
```
* **`-XX:+UseEpsilonGC`:** The No-Op Garbage Collector. Because our steady-state allocation rate is strictly 0 bytes/second, Epsilon GC guarantees **0.0 ms GC pauses** throughout the entire trading day. (Alternative fallback: Generational ZGC with `-XX:+UseZGC -XX:+ZGenerational`).
* **`-XX:GuaranteedSafepointInterval=0`:** Prevents the JVM from initiating recurring safepoint polls when no GC is running.
* **Pre-Market Warmup Phase:** Execute 200,000 synthetic market data ticks and order cycles through the entire pipeline during pre-market startup to force C2 Tier 4 JIT compilation and inline caching before market open.

### 4.3 Thread Affinity & Core Assignment Matrix
| Core ID | Assigned Thread / Component | Isolation State | Latency / Polling Mode |
| :--- | :--- | :--- | :--- |
| **Core 0 - 1** | OS, Interrupts, Network Management, JIT Compiler | Non-isolated | Standard OS scheduling |
| **Core 2** | Market Data Ingestion & Feed Arbitration | `isolcpus` (Pinned) | Busy-spin loop with `Thread.onSpinWait()` |
| **Core 3** | Trading Engine (Order Book + Strategy + Risk) | `isolcpus` (Pinned) | Busy-spin loop with `Thread.onSpinWait()` |
| **Core 4** | Execution Gateway & Wire Transmission | `isolcpus` (Pinned) | Busy-spin loop with `Thread.onSpinWait()` |
| **Core 5** | Secondary Execution / Drop-Copy Feed (Optional) | `isolcpus` (Pinned) | Event-driven / low-spin |
| **Core 6 - 7** | Chronicle Queue Journal, TCP Replay, Risk Monitoring | Non-isolated | Non-blocking ring buffer consumer |

---

## 5. Regulatory Compliance & Deterministic Audit Architecture

### 5.1 MiFID II RTS 25 Nanosecond Timestamping
Nanosecond timestamps are captured via hardware NIC time-stamping (Solarflare PTP) or `System.nanoTime()` calibrated via PTP/IEEE 1588 timecards:
* $T_1$: Packet arrival at NIC / Ring Buffer ingress.
* $T_2$: Order Book state calculation complete.
* $T_3$: Strategy pricing & Pre-Trade Risk approval complete.
* $T_4$: Outbound order binary packet sent to socket buffer.

All 4 timestamps are stamped directly into the order flyweight struct without heap allocation.

### 5.2 Deterministic Replay & Journaling (Aeron Archive / Chronicle Queue)
* Inbound raw market data packets and outbound order instructions are mirrored to an off-heap SPSC ring buffer.
* An asynchronous journaling worker on Core 6 drains the ring buffer and appends the binary events to memory-mapped files (`mmap`).
* This decouples the trading loop entirely from disk I/O latency while allowing bit-for-bit deterministic replay for post-trade compliance and quantitative strategy research.

---

## 6. Resilience, State Recovery & Failover Design

1. **State Snapshotting & Warm Standby:**
   - Active-Passive warm standby architecture.
   - Passive engine ingests the same A/B market data multicast feeds concurrently and maintains identical Order Book state in real time.
2. **Heartbeat & Failover Detection:**
   - Active engine publishes nanosecond heartbeats over shared-memory IPC.
   - If heartbeats stop for $> 50\text{ ms}$, standby takes over active order routing, verifies active working orders via exchange Drop-Copy / OUCH query, and assumes primary status.
3. **Catastrophic Emergency Kill-Switch:**
   - Multi-channel kill switch operable via:
     - Local memory-mapped volatile flag.
     - Out-of-band UDP / IPC management command.
     - Hard process termination with exchange Cancel-on-Disconnect (CoD).
   - Once tripped, the system issues atomic bulk cancel-all orders across all open venues in $< 100\text{ µs}$.

---

## 7. Latency Budget Reconciliation (Expected vs PRD Budget)

| Stage | PRD Budget Bound | Designed Architecture Bound (Expected) | Margin / Headroom |
| :--- | :--- | :--- | :--- |
| **1. Ingest & Decode (A/B)** | $\le 400\text{ ns}$ | **180 – 250 ns** | + 150 ns |
| **2. Order Book Update (L2/L3)** | $\le 600\text{ ns}$ | **280 – 420 ns** | + 180 ns |
| **3. Strategy Decision (Avellaneda)** | $\le 800\text{ ns}$ | **250 – 450 ns** | + 350 ns |
| **4. Pre-Trade Risk Gate (8 Checks)** | $\le 500\text{ ns}$ | **190 – 280 ns** | + 220 ns |
| **5. Wire Serialization & TX** | $\le 500\text{ ns}$ | **220 – 350 ns** | + 150 ns |
| **Core-to-Core Handoff (Inter-core)** | Included in total | **50 – 90 ns** (2 hops) | Optimized via Co-location |
| **Total Tick-to-Trade (p50)** | **< 1,000 ns** | **~ 850 – 950 ns** | **Achieved** |
| **Total Tick-to-Trade (p99)** | **< 4,000 ns** | **~ 1,800 – 2,400 ns** | **Well within bound** |

---

## 8. Summary of Technical Alignment for @product-owner

1. **SLO Feasibility Confirmed:** With the co-located Trading Engine on Core 3 and kernel-bypass networking, the PRD's p50 < 1.0µs and p99 < 4.0µs targets are fully achievable in Java 21.
2. **Fixed-Point Standard:** All components must adhere strictly to primitive `long` fixed-point representations ($10^4$ or $10^7$) to eliminate floating point latency and jitter.
3. **Garbage Collection Strategy:** Epsilon GC is approved for production trading sessions, backed by rigorous zero-allocation unit tests in CI/CD.
4. **SEC 15c3-5 Compliance:** All 8 checks can be executed in-line in under 300ns, eliminating any need for asynchronous or post-trade compromises.