# Sprint 2 Domain & Advanced Capabilities Research

## Executive Summary
This document delivers the advanced domain and product engineering research for **Sprint 2** of the Ultra-Low Latency Java High-Frequency Trading (HFT) Platform. Building on the core Sprint 1 foundation (co-located L3 book, Avellaneda-Stoikov quoting, SEC 15c3-5 risk gate, and OUCH 5.0 execution), Sprint 2 expands the platform into multi-venue smart routing, CME iLink 3 binary order entry, advanced microstructure queue-position quoting, cross-asset statistical arbitrage, and zero-impact telemetry.

---

## 1. Multi-Venue Smart Order Routing (SOR)

### 1.1 Multi-Venue Market Dynamics & Consolidated Book (NBBO)
* **National Best Bid and Offer (NBBO) & SEC Rule 611 (Order Protection Rule):**
  - Trading systems executing aggressive orders must not trade-through protected quotes displayed on lit exchanges.
  - Requires maintaining a lock-free, zero-copy **Consolidated Best Bid and Offer (CBBO/NBBO)** synthesized from simultaneous L2/L3 feeds across multiple exchange venues (e.g., NASDAQ Carteret, BATS/Cboe EDGA/EDGX Secaucus, NYSE Mahwah).
* **Cross-Venue Book Synthesis:**
  - Fast, primitive arrays mapping `[venue_id][side][price_tick] -> volume`.
  - Global Best Bid = $\max_{v \in \text{Venues}} (P_{\text{bid}, v})$, Global Best Ask = $\min_{v \in \text{Venues}} (P_{\text{ask}, v})$.

### 1.2 Exchange Fee Models & Economic Optimization
* **Maker-Taker vs. Taker-Maker (Inverted) Pricing:**
  - **Maker-Taker (e.g., NASDAQ, BZX):** Exchanges pay a rebate (e.g., 20–30 cents per 100 shares) to liquidity adders (makers) and charge a fee (e.g., 30 cents per 100 shares) to liquidity removers (takers).
  - **Taker-Maker / Inverted (e.g., EDGA, BYX):** Exchanges pay a rebate to takers and charge makers.
* **Smart Order Routing Cost Function:**
  - Net Execution Cost = $\text{Fill Price} + \text{Exchange Fee} - \text{Rebate} + \text{Routing Latency Cost}$.
  - When posting passive liquidity: Prefer venues with highest maker rebates or shorter queues.
  - When taking liquidity aggressively: Route to inverted venues first if fee savings exceed price impact risk.

### 1.3 Latency-Equalized Sweeping & Anti-Adverse Selection
* **The "Information Leakage" Problem:**
  - If a sweep order is sent simultaneously to Carteret (NASDAQ, 0µs fiber) and Mahwah (NYSE, ~10µs fiber away), Carteret executes first. High-frequency market makers in Carteret observe the execution and cancel their resting quotes in Mahwah before our order arrives.
* **Latency Pacing / Paced Dispatching:**
  - Measure one-way transit delays ($\Delta t_v$) between the trading engine and each venue $v$.
  - Schedule outbound packet transmission times $T_v = T_{\text{target}} - \Delta t_v$ so that sweep packets hit the matching engines across all venues at the exact same microsecond, eliminating quote cancellation by rival HFTs.

---

## 2. CME iLink 3 Binary Order Entry Protocol

### 2.1 Architecture & Session Management
* **CME iLink 3 over Simple Binary Encoding (SBE) / TCP:**
  - Modern binary order routing protocol replacing legacy FIX 4.2 / FAST session layers at CME Group.
  - Employs **Financial Transfer Interface (FTI)** with SBE schema templates over TCP.
* **Session Lifecycle:**
  1. `Negotiate` (template ID 500): Authenticate credentials (HMAC-SHA256 signature, UUID).
  2. `NegotiationResponse` (template ID 501): Confirm session key.
  3. `Establish` (template ID 503): Exchange `KeepAliveInterval`, `NextSeqNo`.
  4. `EstablishmentAck` (template ID 504): Session active for order submission.
  5. `Terminate` (template ID 505): Graceful session shutdown.
* **Dual Market Segment Gateways (MSGW):**
  - CME runs segregated Market Segment Gateways per product family (e.g., MSGW 1 for E-mini S&P, MSGW 2 for Eurodollar).
  - The platform must maintain persistent, pre-established iLink 3 sessions per MSGW with automatic TCP failover.

### 2.2 Core Trading Messages & Wire Schemas
* **NewOrderSingle (Template 514):**
  - Pre-allocated 128-byte SBE layout with fixed offsets:
    - `PartyDetailsListReqID` (uint64)
    - `OrderRequestID` (uint64)
    - `ClOrdID` (uint64)
    - `SecurityID` (int32)
    - `Price` (PRICENULL9 - int64 scaled $10^9$)
    - `OrderQty` (uint32)
    - `Side` (uint8: 1=Buy, 2=Sell)
    - `OrderType` (uint8: 2=Limit)
    - `TimeInForce` (uint8: 0=Day, 3=IOC, 4=FOK)
* **OrderCancelReplaceRequest (Template 515) & OrderCancelRequest (Template 516):**
  - Single-microsecond in-flight quote modification preserving queue priority when size decreases.
* **ExecutionReport (Template 522):**
  - Instantaneous parse of fills, rejections, and cancels for monotonic position updates.

---

## 3. Advanced Microstructure Queue-Position & Spread Dynamics

### 3.1 FIFO Queue Position Tracking & Microstructure Dynamics
* **Queue Position ($q_{\text{pos}}$):**
  - In a FIFO matching engine, when an order is placed at price level $P$, the volume ahead is $V_{\text{ahead}} = \sum \text{Size of orders before ours}$.
  - Tracking through L3 MBO events:
    - Subtract $V_{\text{cancel}}$ when orders ahead are cancelled.
    - Subtract $V_{\text{exec}}$ when trades occur at price $P$.
    - When $V_{\text{ahead}} = 0$, our order is at the head of the queue.
* **Queue Depletion & Adverse Selection Probability:**
  - As $V_{\text{ahead}} \to 0$, probability of being filled $P(\text{fill})$ approaches 1.0.
  - However, in fast markets, rapid queue depletion is often driven by aggressive informed traders sweeping the book.
  - **Adverse Selection Formula:**
    $$P(\text{Adverse Move} \mid \text{Fill}) \propto \frac{dV_{\text{ahead}}}{dt}$$
  - High negative rate of change in volume ahead ($\frac{dV_{\text{ahead}}}{dt} \ll 0$) signals an aggressive toxic sweep.

### 3.2 Dynamic Spread Skewing Model
* **Queue-Aware Reservation Price:**
  $$r(s, q, t, q_{\text{pos}}) = s - q \gamma \sigma^2 (T - t) - \alpha_{\text{queue}} \cdot \left(1 - \frac{V_{\text{ahead}}}{V_{\text{total}}}\right) \cdot \text{Sign}(q)$$
* **Quote Shading & Tactical Cancellation:**
  - When inventory is high and $V_{\text{ahead}}$ at opposite side is large, aggressively tighten the spread to improve queue priority.
  - When $V_{\text{ahead}}$ collapses rapidly due to a toxic sweep, immediately cancel or fade the quote back by 1–2 ticks before execution occurs.

---

## 4. Cross-Asset Statistical Arbitrage & Microstructure Alpha

### 4.1 Index Futures vs. ETF Arbitrage (ES vs. SPY)
* **Microstructure Lead-Lag Dynamic:**
  - CME E-mini S&P 500 futures (`ES`) trade with higher liquidity and lower latency than individual equities, often leading SPY ETF and basket stocks by 5–50 milliseconds.
  - Detecting tick changes, order book imbalance, and aggressive sweeps in `ES` provides predictive alpha for SPY quoting:
    $$\Delta P_{\text{SPY}}(t + \tau) = \beta \cdot \Delta P_{\text{ES}}(t) + \theta \cdot \text{OBI}_{\text{ES}}(t)$$
* **Cointegration & Spread Mean Reversion:**
  - Calculate fixed-point basis:
    $$\text{Basis}_t = P_{\text{ES}, t} - (\text{Ratio} \times P_{\text{SPY}, t})$$
  - Z-score normalized against running microsecond EMA:
    $$Z_t = \frac{\text{Basis}_t - \mu_{\text{Basis}}}{\sigma_{\text{Basis}}}$$
  - Trigger cross-asset pairs execution when $|Z_t| > 2.5$.

### 4.2 Order Flow Imbalance (OFI) & Multi-Level Alpha
* **Order Flow Imbalance (OFI):**
  $$\text{OFI}_t = I_{\{P_{b, t} \ge P_{b, t-1}\}} Q_{b, t} - I_{\{P_{b, t} \le P_{b, t-1}\}} Q_{b, t-1} - I_{\{P_{a, t} \le P_{a, t-1}\}} Q_{a, t} + I_{\{P_{a, t} \ge P_{a, t-1}\}} Q_{a, t-1}$$
* OFI captures net change in buying vs. selling pressure across price levels, offering superior short-term directional prediction compared to pure mid-price returns.

---

## 5. Real-Time Zero-Impact Drop Copy & Telemetry

### 5.1 Drop Copy Architecture
* **Purpose:** Independent outbound stream reporting all order entries, modifications, cancels, and executions to middle-office clearing, real-time risk managers, and compliance.
* **Separation of Concerns:**
  - Trading Core (Core 3) must **never** perform blocking I/O or serialization for middle-office consumers.
  - Offloaded via lock-free SPSC ring buffer or Aeron IPC shared memory to Housekeeping Cores (Cores 6-7).
  - Middle-office gateways encode execution reports into standard FIX 4.4 Drop Copy or SBE formats over dedicated TCP sessions.

### 5.2 Zero-Allocation High-Precision Telemetry
* **Metrics Invariants:**
  - No `ConcurrentHashMap`, no atomic increments on hot path, no strings.
  - Circular telemetry ring buffer recording fixed-size binary records:
    - `nanosecond_timestamp` (uint64)
    - `event_type` (uint8: INGEST, BOOK_UPDATE, STRATEGY_QUOTE, RISK_PASS, WIRE_TX)
    - `latency_nanos` (uint32)
    - `metadata_id` (uint32)
* **Off-Path Aggregation:**
  - Dedicated telemetry reader thread collects ring buffer events, maintains `HdrHistogram` percentiles (p50, p90, p99, p99.9), and exposes lightweight metrics without touching L1/L2 caches of the trading core.

---

## 6. Recommendations for Sprint 2 Product Requirements

1. **Epic 1: Multi-Venue Consolidated Book & SOR:**
   - Consolidated Best Bid and Offer (CBBO) synthesis.
   - Paced / latency-equalized multi-exchange sweep.
   - Maker-taker fee & rebate optimization engine.
2. **Epic 2: CME iLink 3 Order Entry Gateway:**
   - Full SBE wire serialization for NewOrderSingle, OrderCancelReplace, OrderCancel.
   - CME FTI session state machine (Negotiate, Establish, Sequence tracking).
3. **Epic 3: L3 Queue-Aware Dynamic Quoting Engine:**
   - Live queue position tracking ($V_{\text{ahead}}$ tracking).
   - Toxic queue depletion detection and dynamic quote shading.
4. **Epic 4: Cross-Asset Lead-Lag Statistical Arbitrage:**
   - ES-SPY lead-lag alpha signal generator.
   - Real-time Order Flow Imbalance (OFI) calculations.
5. **Epic 5: Real-Time Zero-Impact Drop Copy & Telemetry Engine:**
   - Aeron IPC / SPSC tap to non-trading core for FIX 4.4 / binary Drop Copy.
   - Nanosecond latency telemetry engine with off-path HdrHistogram recording.
