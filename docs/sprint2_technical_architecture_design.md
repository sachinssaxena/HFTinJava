# Sprint 2 Technical Architecture & System Design Document
## Multi-Venue Smart Order Routing, CME iLink 3 Binary Execution, Queue-Aware Quoting & Cross-Asset Statistical Arbitrage

**Document Version:** 2.0.0-APPROVED  
**Author:** Technical Architect (@tech-architect)  
**Stakeholders:** Product Owner (@product-owner), Coordinator (@coordinator), Developer (@dev), QA (@qa), Infrastructure (@infra)  
**Date:** 2026-11-19  
**Status:** Approved Technical Architecture  

---

## 1. Architectural Philosophy & Mechanical Sympathy Scaling

Sprint 2 scales the platform from single-venue market making into a **multi-venue, multi-asset trading engine** while maintaining strict zero-allocation, zero-GC invariants:
- **Tick-to-trade SLO:** $< 500\text{ ns}$ (p50), $< 1,800\text{ ns}$ (p99).
- **Steady-state allocations:** **Strictly 0 bytes/second** across all new modules.
- **Microsecond Clock Synchronization:** MiFID II RTS 25 compliant hardware PTP timestamping.

---

## 2. Pinned Multi-Core Topology (NUMA Node 0)

To accommodate simultaneous NASDAQ OUCH and CME iLink 3 execution, multi-venue market data, and background telemetry without creating core contention, the core pinning topology expands as follows:

```
  ═════════════════════════════════════════════════════════════════════════════════════════════
  NUMA NODE 0: PHYSICAL CORE ALLOCATION MATRIX
  ═════════════════════════════════════════════════════════════════════════════════════════════

  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │ CORES 0 - 1 (Shared / Non-isolated): OS Housekeeping, Interrupts, JIT C2 Compilation   │
  └────────────────────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │ CORE 2 (Isolated, isolcpus): Multi-Venue Market Data Ingestion & Arbitration           │
  │  - UDP Multicast Ingestion: NASDAQ ITCH 5.0 (Carteret) & CME MDP 3.0 (Aurora)           │
  │  - Dual-feed A/B arbitration & fast sequence deduplication (< 25ns)                    │
  │  - Publishes zero-copy flyweight events to Core 3 via SPSC Ring Buffer                 │
  └───────────────────────────┬────────────────────────────────────────────────────────────┘
                              │ SPSC Lock-Free Ring Buffer (RingBufferMD2Core)
                              ▼
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │ CORE 3 (Isolated, isolcpus): Consolidated Trading Engine (Co-Located Hot Path)         │
  │  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
  │  │ Component A: Multi-Venue Consolidated Book (CBBO / NBBO) (< 350ns)               │  │
  │  │  - Top-of-book aggregation across NASDAQ, BATS/Cboe, NYSE (SEC Rule 611 compliant)│  │
  │  ├──────────────────────────────────────────────────────────────────────────────────┤  │
  │  │ Component B: Queue-Aware Quoting Engine (< 200ns)                                │  │
  │  │  - Real-time V_ahead tracking & toxic depletion rate dV/dt detection             │  │
  │  │  - Avellaneda-Stoikov spread skewing & tactical quote fading                      │  │
  │  ├──────────────────────────────────────────────────────────────────────────────────┤  │
  │  │ Component C: Cross-Asset Statistical Arbitrage Engine (ES vs SPY) (< 350ns)      │  │
  │  │  - CME ES lead-lag alpha, multi-level OFI, cointegrated basis Z-score (> 2.5)    │  │
  │  ├──────────────────────────────────────────────────────────────────────────────────┤  │
  │  │ Component D: Smart Order Routing (SOR) & Cost Engine (< 300ns)                   │  │
  │  │  - Net economic cost evaluation (fees vs rebates), liquidity waterfall routing   │  │
  │  │  - Latency-equalized paced sweep dispatching (arrival jitter < 500ns)            │  │
  │  ├──────────────────────────────────────────────────────────────────────────────────┤  │
  │  │ Component E: Multi-Leg Pre-Trade Risk Gate (SEC Rule 15c3-5) (< 280ns)           │  │
  │  │  - Atomic dual-leg risk verification, cross-venue capital limits, kill-switch    │  │
  │  └──────────────────────────────────────────────────────────────────────────────────┘  │
  └───────────────┬──────────────────────────────────────────┬─────────────────────────────┘
                  │ SPSC Ring Buffer                         │ SPSC Ring Buffer
                  ▼ (RingBufferOUCH)                         ▼ (RingBufferILink)
  ┌──────────────────────────────────┐       ┌─────────────────────────────────────────────┐
  │ CORE 4 (Isolated): OUCH Gateway  │       │ CORE 5 (Isolated): CME iLink 3 Gateway      │
  │  - NASDAQ OUCH 5.0 TCP           │       │  - CME iLink 3 SBE over TCP with FTI        │
  │  - Wire formatting < 250ns       │       │  - SBE NewOrderSingle (514) / Cancel (516)  │
  │  - Solarflare onload_zc_send     │       │  - MSGW session state machine & TCP send    │
  └───────────────┬──────────────────┘       └───────────────────────┬─────────────────────┘
                  │                                                  │
                  └──────────────────────────┬───────────────────────┘
                                             │ Off-heap Ring Buffer Tap (0ns hot-path impact)
                                             ▼
  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │ CORE 6 (Isolated): Real-Time Drop Copy & Middle-Office Telemetry Engine                │
  │  - FIX 4.4 Drop Copy session distribution to clearing & prime brokers                  │
  │  - Nanosecond HdrHistogram latency percentiles (p50, p90, p99, p99.9, max jitter)      │
  └────────────────────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────────────────────────────────────────────────────────────────────────┐
  │ CORE 7 (Isolated): Deterministic Audit Journal & Out-of-Band TCP Replay                │
  │  - MiFID II RTS 25 nanosecond binary event journaling (Chronicle Queue / Aeron)        │
  │  - Out-of-band TCP gap recovery socket client (defensive book resynchronization)       │
  └────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Component Deep Dives & Algorithmic Design

### 3.1 Multi-Venue Consolidated Book (CBBO / NBBO)
* **Contiguous Multi-Venue Ladder:**
  - Maintains individual venue best bid/ask arrays in flat primitive `long` memory:
    ```java
    // Index: 0=NASDAQ, 1=BATS_BZX, 2=BATS_BYX, 3=EDGA, 4=EDGX, 5=NYSE
    long[] venueBestBidPrice = new long[MAX_VENUES];
    long[] venueBestBidQty   = new long[MAX_VENUES];
    long[] venueBestAskPrice = new long[MAX_VENUES];
    long[] venueBestAskQty   = new long[MAX_VENUES];
    ```
  - Upon any venue update, CBBO Best Bid ($\max$) and Best Ask ($\min$) are recomputed in $O(V)$ operations ($V \le 8$, unrolled loop taking $< 35\text{ ns}$).
  - Enforces SEC Rule 611 Lit Protected Quote compliance to guarantee zero trade-throughs.

### 3.2 Smart Order Routing (SOR) & Latency-Equalized Paced Dispatching
* **Economic Cost-Based Routing:**
  - Evaluates Net Execution Cost per share for venue $v$:
    $$\text{NetCost}_v = P_v + \text{Fee}_v - \text{Rebate}_v$$
  - For passive liquidity: Ranks venues by $(\text{Rebate}_v - \text{Fee}_v)$ descending.
  - For aggressive liquidity sweeps: Ranks venues by $(\text{Price}_v + \text{Fee}_v - \text{Rebate}_v)$ ascending.
* **Latency-Equalized Paced Dispatching Algorithm:**
  - To prevent fast venues (e.g. Carteret) from telegraphing sweeps to distant venues (e.g. Secaucus, Mahwah):
    1. Identify maximum one-way transit delay among targeted venues: $D_{\max} = \max_{v} (\Delta t_v)$.
    2. Set target arrival timestamp: $T_{\text{target}} = T_{\text{now}} + D_{\max} + \text{SafetyBuffer}$.
    3. Schedule dispatch time for venue $v$: $T_{\text{dispatch}, v} = T_{\text{target}} - \Delta t_v$.
    4. Fast venues with low $\Delta t_v$ are held in a micro-delay spin loop (`Thread.onSpinWait()`) until their scheduled dispatch instant.
    5. All packets hit remote matching engines within $\pm 250\text{ ns}$ jitter, eliminating predatory quote cancellations.

### 3.3 CME iLink 3 Binary Gateway (SBE over TCP)
* **SBE Protocol Engine:**
  - Zero-copy SBE flyweight encoder utilizing pre-allocated direct buffers.
  - Generates binary templates:
    - `NewOrderSingle` (Template ID 514, 128 bytes, fixed field offsets).
    - `OrderCancelReplaceRequest` (Template ID 515).
    - `OrderCancelRequest` (Template ID 516).
* **Fault Tolerant Interface (FTI) Session Layer:**
  - Manages session states: `DISCONNECTED` $\to$ `NEGOTIATING` (Template 500) $\to$ `ESTABLISHING` (Template 503) $\to$ `ACTIVE`.
  - Heartbeat generator and monotonic sequence manager ensuring zero-gap communication with CME Market Segment Gateways (MSGW).

### 3.4 Queue-Aware Dynamic Quoting Engine
* **Level 3 MBO Queue Priority:**
  - For each active working quote, track cumulative volume ahead $V_{\text{ahead}}$.
  - Decrement $V_{\text{ahead}}$ upon receiving `OrderCancel` and `OrderExecute` messages at our price level.
* **Toxic Queue Depletion Rate ($\frac{dV_{\text{ahead}}}{dt}$):**
  - Track queue volume depletion over a rolling $100\text{ µs}$ sliding window.
  - If $\frac{\Delta V_{\text{ahead}}}{\Delta t} < -\text{DepletionThreshold}$, aggressive sweeps are consuming orders ahead:
    - Automatically widen quote spread or issue sub-microsecond bulk cancel to avoid adverse selection fills.

### 3.5 Cross-Asset Statistical Arbitrage (CME ES Futures vs SPY ETF)
* **Lead-Lag Microstructure Alpha:**
  - CME ES futures price updates and top-3 level Order Flow Imbalance (OFI) lead SPY cash equity moves by 5–50ms.
  - Predictive fair value $\Delta \hat{P}_{\text{SPY}}$ computed via fixed-point integer linear model.
* **Cointegrated Basis & Z-Score Mean Reversion:**
  - Real-time Basis: $\text{Basis}_t = P_{\text{ES}, t} - (\beta \times P_{\text{SPY}, t})$.
  - Exponential moving average $\mu_t$ and variance $\sigma^2_t$ updated in-line without transcendental functions.
  - When $|Z_t| = \left| \frac{\text{Basis}_t - \mu_t}{\sigma_t} \right| > 2.5$, generate atomic dual-leg buy/sell signals routed simultaneously to OUCH Gateway (Core 4) and iLink 3 Gateway (Core 5).

### 3.6 Real-Time Zero-Impact Drop Copy & Telemetry Engine
* **Decoupled Architecture:**
  - Core 3 copies completed trade tokens to a dedicated 65,536-slot off-heap SPSC ring buffer in $< 25\text{ ns}$.
  - Core 6 acts as consumer: formats FIX 4.4 execution reports for clearing houses, and records nanosecond latencies into an `HdrHistogram`.
  - **Zero impact on Core 3 tick-to-trade latency.**

---

## 4. Latency Budget Reconciliation (Sprint 2)

| Pipeline Operation | PRD Bound | Architectural Estimate | Margin |
| :--- | :--- | :--- | :--- |
| **CBBO Update** | $\le 350\text{ ns}$ | **120 – 180 ns** | + 170 ns |
| **SOR Routing & Fee Selection** | $\le 300\text{ ns}$ | **90 – 160 ns** | + 140 ns |
| **CME iLink 3 SBE Serialization** | $\le 350\text{ ns}$ | **140 – 220 ns** | + 130 ns |
| **Queue Position Tracking & Skew** | $\le 200\text{ ns}$ | **60 – 110 ns** | + 90 ns |
| **ES-SPY Alpha & Z-Score Calc** | $\le 350\text{ ns}$ | **110 – 190 ns** | + 160 ns |
| **Paced Sweep Jitter Target** | $\le 500\text{ ns}$ | **< 250 ns** | + 250 ns |
| **Total Multi-Venue Tick-to-Trade** | $\le 1,800\text{ ns}$ | **~ 450 – 850 ns** | **Well within bound** |

---

## 5. Architectural Alignment & Sign-Off

1. **Topology Scalability:** Core 2 (Market Data) $\to$ Core 3 (Trading Engine) $\to$ Cores 4 & 5 (Gateways) $\to$ Core 6 (Drop Copy) $\to$ Core 7 (Journal & Recovery) ensures zero CPU contention across all active trading threads.
2. **Zero Allocation Invariant:** Validated for all new SBE templates, CBBO ladder arrays, and SOR routing tables.
3. **Regulatory Strictness:** Dual-leg atomic risk evaluation satisfies SEC Rule 15c3-5 and MiFID II RTS 25 clock synchronization.