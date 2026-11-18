# Sprint 2 QA Test Strategy & Verification Plan
## Multi-Venue Smart Order Routing, CME iLink 3 Binary Execution, Queue-Aware Quoting & Cross-Asset Statistical Arbitrage

**Author:** Performance & Functional QA Engineer (@qa)  
**Stakeholders:** Technical Architect (@tech-architect), Product Owner (@product-owner), Developer (@dev), Infrastructure (@infra)  
**Date:** 2026-11-19  
**Status:** Approved QA Verification Blueprint  

---

## 1. QA Objectives & Verification Principles (Sprint 2)

Sprint 2 expands verification from single-venue execution into a complex distributed multi-venue, multi-asset trading framework. The QA verification principles are:

1. **Deterministic Latency SLO Compliance:**
   - Multi-venue Consolidated Book update: p99 $\le 350\text{ ns}$.
   - Cost-based SOR evaluation & Liquidity Waterfall: p99 $\le 300\text{ ns}$.
   - Latency-Equalized Paced Dispatch Arrival Jitter: $\le 500\text{ ns}$ (Target: $\le 250\text{ ns}$).
   - CME iLink 3 SBE Wire Serialization: p99 $\le 350\text{ ns}$.
   - Queue Position & Depletion Rate Calculation: p99 $\le 200\text{ ns}$.
   - Cross-Asset Lead-Lag & Z-Score Evaluation: p99 $\le 350\text{ ns}$.
   - Total Multi-Venue Tick-to-Trade: p50 $< 500\text{ ns}$, p99 $< 1,800\text{ ns}$.
2. **Zero-Allocation Invariant on Sprint 2 Modules:**
   - Steady-state heap allocation is strictly **0 bytes/second** across all new components (CBBO, SOR, iLink 3 SBE, Queue Tracker, StatArb, Drop Copy ring buffer).
   - Zero GC pauses (0.0 ms) under Epsilon GC (`-XX:+UseEpsilonGC`).
3. **Rigorous Edge-Case & Regulatory Simulation:**
   - SEC Rule 611 Lit Protected Quote compliance (zero trade-throughs).
   - Inverted taker-maker vs classic maker-taker fee optimization boundaries.
   - CME iLink 3 FTI session recovery, sequence gap renegotiation, and corrupt SBE message handling.
   - Extreme queue depletion cascades ($\Delta V / \Delta t$) triggering sub-microsecond quote fades.
   - Atomic dual-leg failure handling in ES-SPY StatArb (one leg fills, other leg rejects).
   - High-load burst stress ($\ge 10,000,000\text{ msgs/s}$) on drop-copy SPSC ring buffers without critical path stalls.

---

## 2. Sprint 2 Test Matrices

### 2.1 Epic 1: Multi-Venue Consolidated Book (CBBO) & Smart Order Routing (SOR)
| Test ID | Test Scenario | Acceptance Criteria |
| :--- | :--- | :--- |
| **TS-S2-CBBO-01** | Multi-venue top-of-book aggregation | Synthesizes Best Bid ($\max$) and Best Ask ($\min$) across NASDAQ, BATS BZX/BYX, EDGA/EDGX, and NYSE in $\le 180\text{ ns}$ with 0 bytes heap allocation. |
| **TS-S2-CBBO-02** | SEC Rule 611 Trade-Through Prevention | When routing aggressive orders, verify quotes never execute through a better displayed lit quote on any protected market center. |
| **TS-S2-CBBO-03** | Economic Fee Optimization | Passive orders routed to venue with highest net rebate ($\text{Rebate} - \text{Fee}$); aggressive orders routed to lowest net cost ($\text{Price} + \text{Fee} - \text{Rebate}$). |
| **TS-S2-CBBO-04** | Paced Sweep Dispatching Jitter | Sweep across Carteret ($\Delta t = 20\text{µs}$), Secaucus ($\Delta t = 100\text{µs}$), and Mahwah ($\Delta t = 350\text{µs}$); verify packet dispatch pacing achieves arrival jitter $\le 250\text{ ns}$. |

### 2.2 Epic 2: CME iLink 3 Binary Gateway (SBE over TCP)
| Test ID | Test Scenario | Acceptance Criteria |
| :--- | :--- | :--- |
| **TS-S2-ILINK-01** | SBE Binary Wire Serialization | Pre-allocated serialization of `NewOrderSingle` (514), `OrderCancelReplace` (515), and `OrderCancel` (516) executed in $\le 250\text{ ns}$ with exact byte offset matching. |
| **TS-S2-ILINK-02** | FTI Session State Lifecycle | Seamless state machine progression: `Negotiate` (500) $\to$ `NegotiationResponse` (501) $\to$ `Establish` (503) $\to$ `EstablishmentAck` (504) $\to$ `Active`. |
| **TS-S2-ILINK-03** | Sequence Number Synchronization & Recovery | Monotonic client/server sequence increments; detects inbound gap and triggers `Sequence` reset cleanly without dropping active orders. |
| **TS-S2-ILINK-04** | Corrupt SBE Frame Rejection | Inbound packets with invalid template IDs or truncated lengths rejected safely without throwing runtime exceptions. |

### 2.3 Epic 3: Level 3 Queue-Aware Dynamic Quoting Engine
| Test ID | Test Scenario | Acceptance Criteria |
| :--- | :--- | :--- |
| **TS-S2-QUE-01** | Real-Time $V_{\text{ahead}}$ Tracking | Tracks exact volume ahead of working quote; correctly decrements on cancellations and executions at the same price level in $\le 100\text{ ns}$. |
| **TS-S2-QUE-02** | Toxic Queue Depletion Rate ($\frac{dV}{dt}$) | Rolling $100\text{ µs}$ window detects rapid volume drops; breaches threshold $\frac{\Delta V}{\Delta t} < -\text{Limit}$ in $< 150\text{ ns}$. |
| **TS-S2-QUE-03** | Tactical Quote Fading / Cancellation | Upon toxic depletion trigger, working quote is widened or cancelled within $< 250\text{ ns}$, successfully avoiding adverse fills. |
| **TS-S2-QUE-04** | Queue Advantage Tightening | When $V_{\text{ahead}} = 0$ (first in queue), quote spread tightens to capture spread while retaining top queue priority. |

### 2.4 Epic 4: Cross-Asset Statistical Arbitrage (CME ES vs SPY ETF)
| Test ID | Test Scenario | Acceptance Criteria |
| :--- | :--- | :--- |
| **TS-S2-ARB-01** | ES Lead-Lag Microstructure Alpha | Detects rapid ES price jumps and top-3 level OFI; updates predictive SPY fair value within $\le 200\text{ ns}$. |
| **TS-S2-ARB-02** | Basis Z-Score Mean Reversion | Computes Basis $= P_{\text{ES}} - (\beta \times P_{\text{SPY}})$ and running Z-score; trips when $|Z| > 2.5$. |
| **TS-S2-ARB-03** | Atomic Dual-Leg Execution & Risk Gating | Evaluates Pre-Trade Risk across both legs simultaneously; dispatches Buy SPY (OUCH) and Sell ES (iLink 3) concurrently. |
| **TS-S2-ARB-04** | Legged Execution Handling | If one leg rejects or fails risk, immediately suppresses or cancels opposing leg to prevent naked unhedged exposure. |

### 2.5 Epic 5: Real-Time Zero-Impact Drop Copy & Telemetry Engine
| Test ID | Test Scenario | Acceptance Criteria |
| :--- | :--- | :--- |
| **TS-S2-DRP-01** | Zero-Impact Ring Buffer Tap | Core 3 hands off execution report token to off-heap SPSC ring buffer in $\le 25\text{ ns}$ with **0.0 ns** overhead on trading hot path. |
| **TS-S2-DRP-02** | FIX 4.4 / Binary Drop Copy Distribution | Background Core 6 formats and dispatches drop-copy stream to clearing sessions without backpressure. |
| **TS-S2-DRP-03** | Nanosecond `HdrHistogram` Telemetry | Real-time percentiles (p50, p90, p99, p99.9, max jitter) recorded and queried with 0 heap allocations. |

---

## 3. Sprint 2 Zero-Allocation & Benchmark Harness Design

```
┌────────────────────────────────────────────────────────────────────────┐
│ SPRINT 2 VERIFICATION TEST HARNESS (Java 21, Epsilon GC)               │
├───────────────────────────────────┬────────────────────────────────────┤
│ 1. Zero-Allocation Assertions     │ 2. Nanosecond Latency Benchmarks   │
│ - ThreadAllocatedBytes delta = 0  │ - HdrHistogram 200k warmup         │
│ - Tested across 200k iterations   │ - Coordinated omission prevention  │
│ - Both happy & failure paths      │ - Tick-to-trade p50, p90, p99, p99.9│
└───────────────────────────────────┴────────────────────────────────────┘
```

Ready for implementation by @dev and verification execution by @qa.
