# Product Requirements Document (PRD)
## Next-Generation Ultra-Low Latency Java High-Frequency Trading (HFT) Platform

**Product Version:** 1.0.0-PROD  
**Author:** Product Owner (@product-owner)  
**Target Reviewer:** Technical Architect (@tech-architect)  
**Date:** 2026-11-19  
**Status:** Approved & Formally Agreed Specification (Architectural Alignment Signed Off)  

---

## 1. Product Vision & Executive Summary

### 1.1 Vision Statement
To build an institutional-grade, ultra-low latency, zero-allocation Java 21 High-Frequency Trading (HFT) and Market Making platform designed to provide tier-1 market liquidity across Equities and Futures venues. The platform delivers sub-microsecond tick-to-trade response times, uncompromising deterministic execution, and ultra-rigorous sub-500ns pre-trade risk controls meeting regulatory mandates (SEC Rule 15c3-5, MiFID II RTS 25).

### 1.2 Core Value Proposition
- **Extreme Speed with Predictability:** Sub-4µs p99 tick-to-trade latency profile without tail-latency spikes from garbage collection or lock contention.
- **Institutional Market Making:** Dynamic Avellaneda-Stoikov quoting, continuous micro-price volume weighting, order book imbalance (OBI) signals, and active toxic flow protection.
- **Fail-Safe Pre-Trade Gating:** Full in-line multi-tiered risk verification executed in under 500 nanoseconds before wire transmission.
- **Auditability & Regulatory Strictness:** Nanosecond timestamping at every stage boundary, continuous drop-copy feeds, and 100% deterministic binary event replay.

---

## 2. Service Level Objectives (SLOs) & Performance Budgets

### 2.1 Latency SLOs (Tick-to-Trade: Packet RX on NIC to Wire TX on NIC)
The critical path is defined from the moment a market data UDP packet arrives at the socket/kernel buffer to the serialization and dispatch of an outbound order over the execution socket.

| Metric | Target Objective | Maximum Acceptable Bound |
| :--- | :--- | :--- |
| **p50 (Median Latency)** | **< 1.0 µs** (1,000 ns) | < 1.5 µs |
| **p90 Latency** | **< 2.2 µs** (2,200 ns) | < 3.0 µs |
| **p99 Latency** | **< 4.0 µs** (4,000 ns) | < 5.0 µs |
| **p99.9 Latency** | **< 7.5 µs** (7,500 ns) | < 10.0 µs |
| **Maximum Jitter (p99.99)** | **< 15.0 µs** | < 25.0 µs |

### 2.2 Internal Stage-by-Stage Latency Budgets (Target Budget Breakdown)

```
[ Inbound NIC ] ──▶ [ Ingestion/Parser ] ──▶ [ Order Book ] ──▶ [ Strategy Engine ] ──▶ [ Pre-Trade Risk ] ──▶ [ Gateway Serializer ] ──▶ [ Outbound NIC ]
     (Wire)              ( < 400 ns )          ( < 600 ns )        ( < 800 ns )           ( < 500 ns )             ( < 500 ns )            (Wire)
                         ───────────────────────────────────────────────────────────────────────────────────────────
                                                    Total Pipeline Budget: < 2,800 ns (2.8 µs)
```

1. **Ingestion & Protocol Parsing (NASDAQ ITCH 5.0 / CME MDP 3.0 SBE):** $\le 400\text{ ns}$
2. **Order Book L2/L3 Update & Depth Maintenance:** $\le 600\text{ ns}$
3. **Microstructure Calculation & Quoting Strategy Decision:** $\le 800\text{ ns}$
4. **Pre-Trade Risk Gate Verification (SEC Rule 15c3-5):** $\le 500\text{ ns}$
5. **Execution Gateway Order Formatting & Socket Transmission:** $\le 500\text{ ns}$

### 2.3 Throughput & Capacity SLOs
- **Sustained Throughput:** $\ge 1,500,000\text{ msgs/second}$ per single symbol trading core pipeline without queue buildup.
- **Burst Throughput Capacity:** $\ge 5,000,000\text{ msgs/second}$ during market open/close and high-volatility macroeconomic data releases without packet dropping or unbounded buffer bloat.
- **Order Modification / Cancellation Rate:** Up to $250,000\text{ operations/second}$ outbound to exchange execution gateways.

### 2.4 Reliability, Memory & JVM Zero-Allocation Invariants
- **Steady-State Allocation Rate:** **0 bytes/second** on the Java heap during active trading hours.
- **GC Pause Tolerance:** **0 ms** during market hours. Any GC cycle (even a minor 50µs pause) is classified as a Critical Severity incident.
- **System Availability:** 99.999% uptime across exchange trading sessions.
- **Mean Time to Recovery (MTTR):** In-flight session state failover in $< 100\text{ ms}$; cold restart with snapshot replay in $< 5\text{ seconds}$.

---

## 3. Scope & Market Connectivity

### 3.1 Target Asset Classes & Instruments
1. **US Equities:**
   - Primary Venues: NASDAQ, BATS/EDGX, NYSE.
   - Protocols: NASDAQ ITCH 5.0 (MBO L3), OUCH 5.0 (Order Entry).
2. **Futures & Commodities:**
   - Primary Venue: CME Group (CME, CBOT, NYMEX, COMEX).
   - Protocols: CME MDP 3.0 (SBE market data over Multicast UDP), CME iLink 3 (SBE order entry over TCP).

### 3.2 Connectivity Topologies
- **A/B Dual Feed Multicast UDP:** Redundant diverse physical paths for market data with hardware packet deduplication and sequence arbitration.
- **TCP Direct Connect with Kernel Bypass:** Solarflare Onload / EF_VI or direct user-space sockets for OUCH and iLink 3 execution links.

---

## 4. Functional Capabilities & Product Epics

### Epic 1: Dual-Feed Market Data Ingestion & Arbitration
- **Dual Multicast Channel Handling (A/B Arbitration):**
  - Concurrently ingest Feed A and Feed B over independent network interfaces.
  - Deliver the first-arriving packet to the processing pipeline; silently drop duplicate sequence IDs in zero CPU cycles.
- **Gap Detection & Rapid Recovery:**
  - Continuous sequence tracking.
  - Instant out-of-band trigger for TCP Historical Replay upon detecting a sequence gap.
  - Seamless background synchronization via periodic multicast snapshot channels without freezing the main order book pipeline.
- **Zero-Copy Protocol Decoders:**
  - Flyweight decoders (Agrona `DirectBuffer` / Panama `MemorySegment`) for NASDAQ ITCH 5.0 raw binary structs and CME MDP 3.0 SBE schemas.
  - Zero heap object instantiations; zero string conversions in the parsing path.

### Epic 2: Real-Time Order Book Engine (L2 & L3 Reconstruction)
- **L3 Market-by-Order (MBO) Engine:**
  - Full individual order lifecycle tracking (`AddOrder`, `ModifyOrder`, `ExecuteOrder`, `CancelOrder`, `DeleteOrder`).
  - Nanosecond queue position estimation for our own quotes (tracking exact quantity ahead and queue degradation).
- **L2 Price-Aggregated Depth Ladder:**
  - Contiguous fixed-point tick arrays for top 20 bid and ask price levels.
  - $O(1)$ updates, additions, and deletions for price level modifications.
- **Deterministic Fixed-Point Representation:**
  - All prices represented as scaled 64-bit primitive `long` integers (e.g. 1 tick = $10^{-4}$ or $10^{-7}$ base units).
  - All quantities represented as primitive `long` integers. Complete elimination of `BigDecimal` and IEEE-754 floating-point inaccuracies.

### Epic 3: High-Frequency Quoting & Microstructure Strategy Engine
- **Avellaneda-Stoikov Market Making Core:**
  - Continuous calculation of reservation price $r(s, q, t) = s - q \gamma \sigma^2 (T - t)$ dynamically shifting bid/ask spread around target inventory ($q$).
  - Inventory-skewed quote placement to enforce delta neutrality.
- **Microstructure Alpha Signals:**
  - **Order Book Imbalance (OBI):** Real-time ratio $\frac{Q_b - Q_a}{Q_b + Q_a}$ computed on L2 top-of-book depth.
  - **Volume-Weighted Micro-Price:** High-frequency fair value estimation reacting ahead of published mid-price shifts.
  - **Order Flow Toxicity & Adverse Selection (VPIN / Sweep Detection):** Automatic quote fading or mass cancellation when aggressive order flow sweeps indicate an informed institutional run.
- **Quote Lifecycle Management:**
  - Mass quote update / bulk cancel-replace.
  - Cancel-on-Disconnect (CoD) heartbeat verification with exchange gateways.

### Epic 4: Ultra-Low Latency Pre-Trade Risk Engine (SEC Rule 15c3-5 Mandate)
Every generated order must pass a comprehensive hardware/software pre-trade risk filter in $\le 500\text{ ns}$ before hitting the outbound gateway socket.

1. **Fat-Finger Price Deviation Bounds:**
   - Enforce hard price collar: Reject buys if $\text{Price} > \text{Ask}_{\text{best}} + X\%$; reject sells if $\text{Price} < \text{Bid}_{\text{best}} - X\%$.
2. **Maximum Order Clip Size:**
   - Single order quantity cannot exceed configured maximum limit per symbol and strategy.
3. **Maximum Order Notional Value:**
   - Single order value ($\text{Price} \times \text{Quantity}$) cannot exceed defined dollar ceiling.
4. **Aggregate Credit & Inventory Limits:**
   - Strict tracking of $(\text{Current Inventory} + \text{Open Working Buy Orders}) \le \text{MaxLongExposure}$ and $(\text{Current Inventory} - \text{Open Working Sell Orders}) \ge -\text{MaxShortExposure}$.
5. **Short Locate & Borrow Verification:**
   - Hard gate requiring active short locate allocation before executing short-sell orders in equities.
6. **Order Churn & Rate Limiting:**
   - High-speed circular ring buffer / token bucket monitoring max messages per 1ms, 10ms, and 1000ms rolling windows to prevent exchange throttling penalties and rogue loops.
7. **Self-Cross / Wash Trade Prevention:**
   - Immediate internal matching prevention: Reject or cancel resting order if a new aggressive quote would execute against the firm's own opposing resting quote.
8. **Emergency Kill-Switch (Zero-Delay):**
   - Atomic volatile flag accessible via memory-mapped IPC / management console that instantly cuts outbound order creation and issues exchange-wide Cancel-All requests.

### Epic 5: Execution Gateway & Order State Management
- **High-Throughput Binary Protocol Gateways:**
  - NASDAQ OUCH 5.0 TCP/SoupBinTCP client.
  - CME iLink 3 SBE/TCP client.
- **Zero-Allocation Order State Machine:**
  - Pre-allocated object pool for active orders indexed by `ClientOrderID` in contiguous memory primitive map (`Long2ObjectHashMap`).
  - States: `PENDING_NEW`, `NEW`, `PENDING_REPLACE`, `REPLACED`, `PARTIALLY_FILLED`, `FILLED`, `PENDING_CANCEL`, `CANCELED`, `REJECTED`.
- **Drop-Copy Distribution:**
  - Real-time outbound broadcast of execution reports and trade fills to clearing, risk, and accounting services without impacting the hot path.

### Epic 6: Regulatory Compliance, Auditing & Deterministic Replay
- **MiFID II RTS 25 Time Synchronization:**
  - Microsecond/nanosecond timestamps captured at four discrete checkpoints:
    1. $T_1$: Network packet RX (NIC hardware or socket arrival).
    2. $T_2$: Order Book update completion.
    3. $T_3$: Strategy order generation & Risk approval.
    4. $T_4$: Outbound packet TX (socket write).
  - Maximum allowable clock drift: $< 100\text{ µs}$ relative to UTC (enforced by PTP / IEEE 1588).
- **Consolidated Audit Trail (CAT) Support:**
  - Exact order lifecycle event logging conforming to FINRA/SEC CAT technical specifications.
- **Deterministic Binary Event Journaling:**
  - Lock-free, zero-copy recording of all inbound and outbound wire events using Aeron Archive or Chronicle Queue for post-session 100% deterministic replay and slippage analysis.

---

## 5. Detailed User Stories & Acceptance Criteria

### US-1: Market Data Dual-Feed Arbitration
* **As an** Algorithmic Trader,  
* **I want** the market data feed handler to ingest dual multicast feeds (A and B) and arbitrate packets at line rate,  
* **So that** packet loss on any single physical network leg does not cause sequence gaps, latency stalls, or missing order updates.
* **Acceptance Criteria:**
  1. Given identical packets arriving on Feed A and Feed B with sequence number $S$, the handler decodes the first packet to arrive and drops the duplicate within $< 50\text{ ns}$ overhead.
  2. When a gap is detected on Feed A, the missing sequence is seamlessly consumed from Feed B if available.
  3. If both feeds drop a packet, a non-blocking TCP replay request is triggered asynchronously while the book switches to defensive quoting mode.
  4. Memory allocations on the arbitration path must be exactly 0 bytes.

### US-2: Sub-Microsecond Level 3 Order Book Reconstruction
* **As a** Quoting Strategy,  
* **I want** an ultra-low latency L3 order book maintained in contiguous memory,  
* **So that** I have instant visibility into market depth, top-of-book, and my firm's exact queue priority.
* **Acceptance Criteria:**
  1. Order additions, modifications, executions, and cancellations must be applied to the order book in $\le 600\text{ ns}$ p99.
  2. Queue position for our own working quote must be computed dynamically (tracking quantity ahead) without traversing unindexed linked lists.
  3. Book state updates must publish internal events via zero-copy ring buffers without generating heap garbage.

### US-3: Automated Avellaneda-Stoikov Market Making Strategy
* **As a** Quantitative Trader,  
* **I want** the strategy engine to compute dynamic reservation prices and optimal bid-ask spreads based on inventory skew and order flow toxicity,  
* **So that** our quotes capture maximum spread while avoiding adverse selection from toxic sweeps.
* **Acceptance Criteria:**
  1. Reservation price $r$ must shift downward when inventory $q > 0$ and upward when $q < 0$ in accordance with parameterized risk aversion $\gamma$.
  2. When Order Book Imbalance (OBI) exceeds configured threshold ($\pm 0.70$) or VPIN detects toxic flow, active quotes on the vulnerable side must be canceled or widened within $\le 1.2\text{ µs}$.
  3. Total decision-making latency from book update to order dispatch decision must not exceed $800\text{ ns}$ p99.

### US-4: Regulatory Pre-Trade Risk Gate (SEC Rule 15c3-5)
* **As a** Chief Risk Officer (CRO),  
* **I want** every outbound order to undergo strict, automated pre-trade risk validation in under $500\text{ ns}$,  
* **So that** our firm is fully compliant with SEC Rule 15c3-5 and protected against catastrophic algorithmic malfunctions.
* **Acceptance Criteria:**
  1. Pre-trade risk checks must execute all 8 mandatory checks (price collar, clip size, notional cap, max inventory, short locate, rate limits, self-cross, kill-switch).
  2. Total risk evaluation latency must be $\le 500\text{ ns}$ at p99.
  3. Any failed risk check must reject the order internally, log the rejection with nanosecond timestamp, and alert risk monitoring without throwing exceptions or generating heap objects.
  4. Triggering the Emergency Kill Switch must reject all subsequent orders instantly ($< 100\text{ ns}$) and dispatch mass-cancel messages to all open venues.

### US-5: MiFID II RTS 25 Nanosecond Audit Trail
* **As a** Compliance Officer,  
* **I want** all internal events to record high-precision nanosecond timestamps traceable to UTC,  
* **So that** we can fulfill MiFID II RTS 25 and US CAT regulatory audit requirements and conduct exact execution timing analysis.
* **Acceptance Criteria:**
  1. Every message record must capture $T_1$ (RX), $T_2$ (Book), $T_3$ (Decision/Risk), and $T_4$ (TX) using hardware or OS PTP-synchronized nanosecond clocks.
  2. Clock synchronization drift must be monitored continuously; any divergence $> 50\text{ µs}$ triggers an operator alert.
  3. All timestamps and order events must be appended to a binary audit journal in zero-copy memory-mapped storage.

---

## 6. Technical & Architecture Review Prompts for @tech-architect

To ensure that these functional requirements and stringent SLOs are fully attainable, the Technical Architect is requested to evaluate and align on the following critical architectural dimensions:

1. **Inter-Thread Pipelining & Concurrency Model:**
   - Disruptor vs. Aeron IPC for inter-core communication between Feed Handler, Order Book, Strategy, Risk, and Gateway.
   - Core pinning topology (e.g., pinning on isolated physical cores, avoiding hyperthreading/SMT interference).
2. **Zero-Allocation Data Structures & Flyweights:**
   - Practicality of Agrona `DirectBuffer` and Java 21 Foreign Function & Memory API (`MemorySegment`) for raw packet parsing and order book storage.
   - Fixed-point arithmetic representation standard for prices and sizes across the entire codebase.
3. **Pre-Trade Risk Engine In-Line Feasibility:**
   - Feasibility of completing all 8 SEC 15c3-5 risk checks within the $\le 500\text{ ns}$ budget using atomic variables, bitmasks, and primitive ring buffers.
4. **JVM Warmup & JIT Determinism:**
   - Pre-market synthetic warmup strategies to ensure C2 Tier 4 compilation of all hot paths and avoidance of deoptimization or safepoints.
   - Evaluation of Garbage Collection strategy: Epsilon GC (no-op GC) vs. ZGC generational with zero-allocation guarantees.
5. **Kernel Bypass Integration:**
   - Strategy for integrating Solarflare Onload / EF_VI or DPDK into Java via JNI/Panama without crossing JNI boundary penalties on the hot path.

---

## 7. Approval & Sign-Off Workflow

- **Product Owner Sign-Off:** Requirements formulated and approved (@product-owner).
- **Technical Architect Feasibility Sign-Off:** Architecture reviewed and signed off (@tech-architect in `docs/system_architecture_design.md`).
- **Co-Location Alignment:** Approved co-location of Order Book + Strategy + Risk on Core 3 to reduce tick-to-trade p50 to ~850–950ns and p99 to ~1.8–2.4µs.
- **Fixed-Point Arithmetic Agreement:** Strict primitive `long` representation ($10^4$ equities, $10^7$ futures).
- **JVM & GC Agreement:** Epsilon GC for live trading with 0.0ms GC pause requirement; automated pre-market C2 warmup.
- **Regulatory Sign-Off:** SEC Rule 15c3-5 8-check in-line gate (< 300ns) and MiFID II RTS 25 nanosecond timestamping ($T_1$ to $T_4$) approved.
- **Milestone Status:** Specification finalized and ready for implementation.
