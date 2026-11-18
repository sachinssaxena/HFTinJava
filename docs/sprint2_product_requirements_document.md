# Sprint 2 Product Requirements Document (PRD)
## Next-Generation Multi-Venue Smart Order Routing, CME iLink 3 Binary Execution, Advanced Queue-Aware Quoting & Cross-Asset Statistical Arbitrage

**Document Version:** 2.0.0-PROD  
**Author:** Product Owner (@product-owner)  
**Target Reviewer:** Technical Architect (@tech-architect)  
**Stakeholders:** @coordinator, @tech-architect, @dev, @qa, @infra, @researcher  
**Date:** 2026-11-19  
**Status:** Approved & Formally Agreed Specification (Architectural Feasibility Signed Off)  

---

## 1. Executive Summary & Sprint 2 Vision

### 1.1 Vision Statement
Building upon Sprint 1's ultra-low latency single-venue market making engine (which achieved sub-100ns tick-to-trade, zero heap allocations, and strict SEC Rule 15c3-5 risk gating), **Sprint 2 scales the platform into a multi-venue, cross-asset trading powerhouse**.

Sprint 2 delivers:
1. **Multi-Venue Consolidated Book (CBBO) & Smart Order Routing (SOR)** across NASDAQ, BATS/Cboe, and NYSE with economic maker-taker fee optimization and latency-equalized anti-leakage sweep dispatching.
2. **CME iLink 3 Binary Order Entry Gateway** utilizing Simple Binary Encoding (SBE) over TCP with full FTI session lifecycle management for high-speed futures execution.
3. **Queue-Aware Dynamic Spread Engine** leveraging Level 3 Market-by-Order (MBO) queue position tracking ($V_{\text{ahead}}$) and toxic queue depletion detection to tactically shade or cancel quotes ahead of adverse informed sweeps.
4. **Cross-Asset Statistical Arbitrage (CME ES Futures vs SPY ETF)** exploiting microstructure lead-lag relationships, multi-level Order Flow Imbalance (OFI), and cointegrated basis Z-scores.
5. **Real-Time Zero-Impact Drop Copy & Telemetry Engine** streaming continuous post-trade execution reports to clearing and recording nanosecond `HdrHistogram` latency metrics via off-path ring buffers with 0ns impact on the trading critical path.

---

## 2. Sprint 2 Service Level Objectives (SLOs) & Performance Budgets

### 2.1 Latency SLOs

| Pipeline Operation | Target Objective (p50) | Target Objective (p99) | Maximum Bound (p99.9) |
| :--- | :--- | :--- | :--- |
| **Consolidated Book (CBBO) Update** | **< 150 ns** | **< 350 ns** | < 600 ns |
| **Smart Order Routing (SOR) Decision & Fee Calc** | **< 120 ns** | **< 300 ns** | < 500 ns |
| **CME iLink 3 SBE Wire Serialization & Dispatch** | **< 180 ns** | **< 350 ns** | < 600 ns |
| **Queue Position Tracking & Toxic Depletion Calc** | **< 80 ns** | **< 200 ns** | < 400 ns |
| **Cross-Asset Lead-Lag Alpha & Z-Score Calc** | **< 150 ns** | **< 350 ns** | < 600 ns |
| **Paced Dispatching Jitter (Target Arrival Delta)** | **< 250 ns** | **< 500 ns** | < 1,000 ns |
| **Total Multi-Venue Tick-to-Trade** | **< 500 ns** | **< 1,800 ns** | < 3,500 ns |
| **Drop Copy & Telemetry Overhead on Core 3** | **0.0 ns** (0 overhead) | **0.0 ns** | **0.0 ns** |

### 2.2 System Invariants & Non-Functional Requirements
1. **Zero Heap Allocation Invariant:** **0 bytes/second** on the Java heap during steady-state multi-venue trading, SBE order serialization, SOR routing, and queue tracking.
2. **GC Pause Invariant:** **0.0 ms** GC pause under Epsilon GC (`-XX:+UseEpsilonGC`).
3. **Throughput Scaling:** Sustained throughput $\ge 2,500,000\text{ msgs/s}$ across aggregated multi-venue market feeds; burst capacity $\ge 10,000,000\text{ msgs/s}$.
4. **Deterministic Auditability:** MiFID II RTS 25 nanosecond timestamps recorded across multi-venue dispatch points and drop-copy streams.

---

## 3. Product Epics & Functional Requirements

### Epic 1: Multi-Venue Consolidated Book & Smart Order Routing (SOR)
- **Consolidated Best Bid & Offer (CBBO / NBBO) Engine:**
  - Ingest simultaneous L2/L3 market data feeds across multiple venues (NASDAQ Carteret, BATS/Cboe EDGA/EDGX Secaucus, NYSE Mahwah).
  - Maintain a zero-copy, flat primitive multi-venue array:
    $$\text{CBBO}_{\text{Bid}} = \max_{v \in \text{Venues}} (P_{\text{bid}, v}), \quad \text{CBBO}_{\text{Ask}} = \min_{v \in \text{Venues}} (P_{\text{ask}, v})$$
  - Enforce SEC Rule 611 compliance (prevention of trade-throughs on lit protected quotes).
- **Economic Maker-Taker & Fee Optimization:**
  - Dynamic cost function evaluated in $O(1)$:
    $$\text{NetCost} = \text{Price} + \text{ExchangeFee} - \text{Rebate} + \text{CostOfLatency}$$
  - For passive liquidity: Route to venues offering highest maker rebates (e.g. BZX/NASDAQ: $+0.0025\$/\text{share}$) or lowest queue competition.
  - For aggressive liquidity taking: Route to inverted taker-maker venues (e.g. EDGA/BYX: rebate to takers) when net economics exceed price impact.
- **Latency-Equalized Paced Dispatching (Anti-Information Leakage):**
  - High-frequency market makers observe executions at fast venues and immediately cancel resting quotes at distant venues before our sweep arrives.
  - The SOR must calculate venue one-way transit delays ($\Delta t_v$) and pace outbound packets:
    $$T_{\text{dispatch}, v} = T_{\text{target}} - \Delta t_v$$
  - Ensures sweep orders arrive at all exchange matching engines simultaneously ($\pm 500\text{ ns}$ precision), capturing full displayed depth across all venues.

### Epic 2: CME iLink 3 Binary Order Entry Gateway
- **CME Financial Transfer Interface (FTI) & SBE Architecture:**
  - Modern binary protocol replacing legacy FIX order entry at CME Group.
  - Fixed-offset pre-allocated SBE byte layouts:
    - `NewOrderSingle` (Template ID 514, 128 bytes).
    - `OrderCancelReplaceRequest` (Template ID 515).
    - `OrderCancelRequest` (Template ID 516).
    - `ExecutionReport` (Template ID 522).
- **Session Lifecycle & State Machine:**
  - State machine executing `Negotiate` (500) $\to$ `NegotiationResponse` (501) $\to$ `Establish` (503) $\to$ `EstablishmentAck` (504) $\to$ `Terminate` (505).
  - Sequence number synchronization with CME Market Segment Gateways (MSGW) and heartbeat timers.
  - Fast-path TCP socket transmission using Solarflare Onload zero-copy send (`onload_zc_send`).

### Epic 3: Level 3 Queue Position-Aware Dynamic Spread Engine
- **MBO Queue Priority & Volume Ahead Tracking:**
  - For every working firm quote at price level $P$, track exact volume ahead $V_{\text{ahead}}$ in real-time.
  - Decrement $V_{\text{ahead}}$ upon observing cancellations ($V_{\text{cancel}}$) and trade executions ($V_{\text{exec}}$) from other market participants ahead of us in the FIFO queue.
- **Toxic Queue Depletion Rate ($\frac{dV_{\text{ahead}}}{dt}$) & Adverse Selection Detection:**
  - Compute queue depletion velocity over sliding microsecond windows:
    $$\Delta V_{\text{depletion}} = V_{\text{ahead}}(t) - V_{\text{ahead}}(t - \Delta t)$$
  - Rapid depletion ($\frac{dV_{\text{ahead}}}{dt} \ll 0$) signals an aggressive institutional sweep.
- **Queue-Aware Dynamic Quote Shading:**
  - Dynamically modify Avellaneda-Stoikov reservation price:
    $$r(s, q, t, q_{\text{pos}}) = s - q \gamma \sigma^2 (T - t) - \alpha_{\text{queue}} \cdot \left(1 - \frac{V_{\text{ahead}}}{V_{\text{total}}}\right) \cdot \text{Sign}(q)$$
  - When $V_{\text{ahead}}$ collapses under toxic flow, issue an immediate sub-microsecond quote fade or cancel to avoid adverse fills.

### Epic 4: Cross-Asset Statistical Arbitrage (CME ES Futures vs SPY ETF)
- **Microstructure Lead-Lag Alpha Signal:**
  - CME E-mini S&P 500 futures (`ES`) lead SPY ETF and equity baskets by 5 to 50 milliseconds due to concentrated futures liquidity.
  - Detect high-speed price changes, order book imbalance, and aggressive sweeps in `ES` to predict near-term SPY fair-value direction:
    $$\Delta \hat{P}_{\text{SPY}}(t + \tau) = \beta \cdot \Delta P_{\text{ES}}(t) + \theta \cdot \text{OBI}_{\text{ES}}(t) + \lambda \cdot \text{OFI}_{\text{ES}}(t)$$
- **Multi-Level Order Flow Imbalance (OFI):**
  - Compute OFI across top 3 book levels using integer arithmetic:
    $$\text{OFI}_t = I_{\{P_{b, t} \ge P_{b, t-1}\}} Q_{b, t} - I_{\{P_{b, t} \le P_{b, t-1}\}} Q_{b, t-1} - I_{\{P_{a, t} \le P_{a, t-1}\}} Q_{a, t} + I_{\{P_{a, t} \ge P_{a, t-1}\}} Q_{a, t-1}$$
- **Cointegrated Basis & Z-Score Mean Reversion Engine:**
  - Continuous microsecond tracking of basis: $\text{Basis}_t = P_{\text{ES}, t} - (\text{HedgeRatio} \times P_{\text{SPY}, t})$.
  - Running microsecond exponential moving average $\mu_{\text{Basis}}$ and variance $\sigma_{\text{Basis}}$.
  - When $|Z_t| = \left| \frac{\text{Basis}_t - \mu}{\sigma} \right| > 2.5$, trigger atomic dual-leg execution (buy undervalued leg, sell overvalued leg) through the SOR and CME iLink 3 gateways simultaneously.

### Epic 5: Real-Time Zero-Impact Drop Copy & Telemetry Engine
- **Middle-Office & Clearing Stream (FIX 4.4 / Binary Drop Copy):**
  - Complete operational decoupling: Outbound order fills and lifecycle states are copied to an off-heap lock-free SPSC ring buffer in < 25ns.
  - Dedicated background consumers on Housekeeping Cores 6-7 serialize and distribute execution reports to external risk, clearing, and regulatory systems via FIX 4.4 Drop Copy sessions.
- **Nanosecond Telemetry & Latency Profiling:**
  - Record stage-by-stage latencies ($T_1 \to T_4$, SOR decision, wire TX) into fixed 32-byte off-heap circular telemetry buffers.
  - Background metrics thread aggregates percentiles (p50, p90, p99, p99.9, max jitter) using `HdrHistogram` and exposes zero-allocation telemetry for real-time monitoring.

---

## 4. User Stories & Acceptance Criteria

### US-S2-1: Consolidated Best Bid & Offer (CBBO) Synthesis
* **As an** Algorithmic Trader,  
* **I want** the platform to synthesize quotes from NASDAQ, BATS, and NYSE into a real-time Consolidated Book (CBBO),  
* **So that** trading decisions have complete top-of-book visibility across lit venues and comply with SEC Rule 611.
* **Acceptance Criteria:**
  1. Updates to venue books update the CBBO in $\le 350\text{ ns}$ at p99.
  2. The CBBO maintains independent venue tags, Best Bid, Best Ask, aggregate sizes, and venue price levels in contiguous primitive arrays.
  3. No heap memory is allocated during CBBO reconstruction (0 bytes).

### US-S2-2: Latency-Equalized Smart Order Routing Sweeps
* **As an** Execution Trader,  
* **I want** the SOR to pace multi-venue sweep dispatches based on network transit latencies,  
* **So that** sweep packets arrive at disparate exchange data centers simultaneously and eliminate adverse quote cancellations.
* **Acceptance Criteria:**
  1. The SOR determines target arrival timestamp $T_{\text{target}}$ and schedules each leg with calibrated delay $\Delta t_v$.
  2. Sweep orders arrive at Carteret, Secaucus, and Mahwah matching engines within $\le 500\text{ ns}$ arrival jitter.
  3. Cost-based routing selects maker/taker fee vs rebate tiers in $\le 300\text{ ns}$.

### US-S2-3: CME iLink 3 Binary Session & Order Execution
* **As a** Futures Market Maker,  
* **I want** a dedicated CME iLink 3 binary gateway implementing SBE over TCP with FTI session management,  
* **So that** we can place, modify, and cancel orders in CME futures markets with sub-microsecond wire serialization.
* **Acceptance Criteria:**
  1. `NewOrderSingle`, `OrderCancelReplace`, and `OrderCancel` SBE binary templates serialize and push to socket buffer in $\le 350\text{ ns}$ p99.
  2. Session layer successfully completes `Negotiate` and `Establish` handshake sequences.
  3. All sequence numbers and execution reports are processed monotonically with zero heap allocations.

### US-S2-4: Queue-Aware Dynamic Spread Skewing & Toxic Sweep Protection
* **As a** Quantitative Researcher,  
* **I want** the quoting engine to track our exact FIFO queue position and queue depletion rate,  
* **So that** our quotes tighten when queue priority is advantageous and fade/cancel when aggressive toxic sweeps threaten adverse selection.
* **Acceptance Criteria:**
  1. For every working quote, $V_{\text{ahead}}$ is accurately updated upon every L3 cancellation and execution event in $\le 200\text{ ns}$.
  2. When queue depletion rate breaches configured threshold ($\frac{dV_{\text{ahead}}}{dt} < -\text{Threshold}$), an immediate quote fade or cancel is generated within $< 300\text{ ns}$.
  3. Zero heap allocations during queue tracking and dynamic skew calculations.

### US-S2-5: ES-SPY Lead-Lag Statistical Arbitrage Execution
* **As a** Statistical Arbitrage Trader,  
* **I want** the platform to detect microstructure lead-lag signals in CME ES futures and execute cointegrated basis trades in SPY ETF,  
* **So that** we capture high-frequency mean-reverting basis profits before the cash equity market catches up.
* **Acceptance Criteria:**
  1. ES microstructure price and OFI changes update the predictive SPY fair value in $\le 350\text{ ns}$.
  2. When basis Z-score $|Z_t| > 2.5$, the engine triggers atomic dual-leg order generation through the Pre-Trade Risk Gate.
  3. Risk controls verify net exposure, clip sizes, and short locates across both legs simultaneously before dispatch.

### US-S2-6: Zero-Impact Drop Copy & Telemetry Stream
* **As a** Chief Risk Officer & Head of Clearing,  
* **I want** all execution reports and latency metrics to stream to clearing and monitoring systems without adding latency to the trading engine,  
* **So that** middle-office operations run continuously without causing jitter on the critical path.
* **Acceptance Criteria:**
  1. Trading Core 3 delegates drop-copy events to off-heap ring buffer in $\le 25\text{ ns}$ overhead.
  2. Dedicated background thread serializes FIX 4.4 / binary drop copy execution reports to clearing sessions.
  3. Nanosecond latency histograms (p50, p90, p99, p99.9) are recorded continuously in `HdrHistogram` with 0 bytes heap allocation on the hot path.

---

## 5. Technical Review Prompts for @tech-architect

The Technical Architect is requested to review the Sprint 2 PRD and provide architectural evaluation on:
1. **Core Topology Scaling:** Core assignments for multi-venue gateways (adding BATS and CME iLink 3 gateway threads without starving Core 3 Trading Engine).
2. **Paced Dispatching Mechanics:** High-resolution scheduling mechanisms for latency-equalized sweeps (e.g. busy-spin timer using `System.nanoTime()` vs hardware NIC timers).
3. **Cross-Core Data Flow for Cross-Asset Arbitrage:** Inter-thread communication topology when CME market data (Core 2) feeds the ES-SPY strategy on Core 3 and dispatches across both OUCH (Core 4) and iLink 3 (Core 5) gateways.
4. **Drop Copy SPSC Ring Buffer Sizing:** Off-heap ring buffer buffer capacity to absorb extreme trading bursts without backpressuring the critical path.
