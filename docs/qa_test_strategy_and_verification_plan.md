# Performance & Functional QA Verification Plan & Test Strategy
## Ultra-Low Latency Java 21 High-Frequency Trading (HFT) Platform

**Author:** Performance & Functional QA Engineer (@qa)  
**Stakeholders:** Technical Architect (@tech-architect), Product Owner (@product-owner), Developer (@dev)  
**Date:** 2026-11-19  
**Status:** Baseline QA Plan  

---

## 1. QA Objectives & Verification Philosophy

As QA, our mandate is to guarantee zero functional defects, uncompromising regulatory compliance (SEC Rule 15c3-5, MiFID II RTS 25), and empirical proof that the platform satisfies all latency, throughput, and zero-allocation SLOs.

### Core Testing Pillars:
1. **Zero-Allocation Invariant Enforcement (0 bytes/s on Hot Path):**
   - Automated allocation detection via JMH `gc` profiler and custom byte-allocation interceptors.
   - Running under Epsilon GC (`-XX:+UseEpsilonGC`) with fixed heap sizes; any steady-state allocation triggers immediate failure.
2. **Nanosecond Latency Benchmarking & Profiling:**
   - JMH benchmarks with blackhole consumption and constant warmup (200k iterations).
   - `HdrHistogram` recording for p50 (< 1.0µs), p90 (< 2.2µs), p99 (< 4.0µs), p99.9 (< 7.5µs), and max jitter (< 15.0µs).
   - Coordinated omission prevention on all pipeline benchmarks.
3. **Rigorous Edge-Case & Boundary Simulation:**
   - Out-of-order and dropped packet permutations on dual A/B multicast streams.
   - Order book corruptions, crossing books, zero/negative pricing, massive depth ladders.
   - Toxic order sweeps, micro-bursts (5,000,000 msgs/s), and rate-limit edge triggers.
4. **SEC Rule 15c3-5 Pre-Trade Risk Verification:**
   - 100% test coverage across all 8 mandatory risk checks.
   - Deterministic rejection without throwing exceptions or allocating error strings.
5. **MiFID II RTS 25 Nanosecond Audit Trail Validation:**
   - Monotonicity verification of $T_1 \le T_2 \le T_3 \le T_4$.
   - Bit-level verification of binary journaling.

---

## 2. Test Suites & Verification Matrix

### 2.1 Component 1: Market Data Ingestion & Dual-Feed Arbitration
- **TS-MD-01 (Deduplication):** Feed A and Feed B publish identical sequence $S$; assert first arrival processed, second discarded in $< 50\text{ ns}$ with 0 heap allocation.
- **TS-MD-02 (Single-Feed Gap Fallback):** Feed A drops sequence $S$; verify Feed B seamlessly supplies $S$ without pipeline stall.
- **TS-MD-03 (Dual-Feed Gap Trigger):** Both feeds drop sequence $S$; verify out-of-band TCP replay trigger fired, book marks `DEFENSIVE_QUOTING`, hot path remains unblocked.
- **TS-MD-04 (Packet Corruption):** Corrupted length or checksum headers rejected cleanly without throwing `IndexOutOfBoundsException` or allocating objects.

### 2.2 Component 2: Contiguous L2/L3 Order Book
- **TS-OB-01 (Order Lifecycle):** Sequential `Add`, `Modify`, `Execute`, `Cancel`, `Delete` operations on Level 3 pool. Verify $O(1)$ pool pointer updates.
- **TS-OB-02 (Queue Priority Tracking):** Track working quote with 5 orders ahead. Verify volume-ahead updates dynamically as preceding orders cancel or fill.
- **TS-OB-03 (L2 Depth Ladder Aggregation):** Verify top 20 bid/ask contiguous arrays maintain accurate aggregate volume and price levels across 1,000,000 random mutations.
- **TS-OB-04 (Crossed Book Defense):** Inbound update where Bid $\ge$ Ask; verify engine detects cross and handles according to exchange matching rules without looping.

### 2.3 Component 3: Avellaneda-Stoikov & Microstructure Strategy
- **TS-ST-01 (Reservation Price Skew):** Verify $r(s, q, t)$ shifts bid/ask quotes downward when long inventory ($q > 0$) and upward when short ($q < 0$) using fixed-point integer math.
- **TS-ST-02 (Order Book Imbalance Reaction):** When $|OBI| > 0.70$, verify quote cancellation or widening triggered within $\le 1.2\text{ µs}$.
- **TS-ST-03 (Micro-Price Precision):** Compare fixed-point integer micro-price calculation against double-precision reference model; error must be $< 10^{-4}$.
- **TS-ST-04 (Toxic Sweep Detection):** Rapid sweep consuming 3 top price levels triggers immediate quote fade.

### 2.4 Component 4: Pre-Trade Risk Gate (SEC Rule 15c3-5)
- **TS-RSK-01 (Kill Switch):** Volatile flag trip halts all new orders in $< 100\text{ ns}$ and returns `REJECT_KILL_SWITCH`.
- **TS-RSK-02 (Price Collar):** Orders outside collar band ($Ask + X\%$ or $Bid - X\%$) rejected.
- **TS-RSK-03 (Clip Size & Notional):** Boundary tests on `maxClipSize` and `maxNotional` ($P \times Q$).
- **TS-RSK-04 (Max Long/Short Inventory):** Test open working order additions against credit ceilings.
- **TS-RSK-05 (Short Locate):** Short sell rejected when locate bit unset.
- **TS-RSK-06 (Rate Limiting / Churn):** Sliding window token bucket rejects orders exceeding burst quota in 1ms/10ms/1000ms.
- **TS-RSK-07 (Self-Cross / Wash Trade):** New order matching firm's own resting quote rejected immediately.
- **TS-RSK-08 (Zero-Allocation & Non-Throwing):** All rejections return primitive error codes with zero object creations.

### 2.5 Component 5: Execution Gateway & Order State Management
- **TS-GW-01 (OUCH / iLink Serialization):** Verify big-endian binary struct layout matches protocol specifications down to the exact byte offset.
- **TS-GW-02 (State Machine Validity):** Test transitions: `PENDING_NEW` -> `NEW` -> `PARTIALLY_FILLED` -> `FILLED` -> `PENDING_CANCEL` -> `CANCELED`.
- **TS-GW-03 (Drop Copy Stream):** Verify drop-copy ring buffer tap receives all execution reports asynchronously without adding backpressure to the hot path.

### 2.6 Component 6: Regulatory Audit & Timestamping (MiFID II RTS 25)
- **TS-REG-01 (Timestamp Monotonicity):** Assert $T_1 \le T_2 \le T_3 \le T_4$ across all processed transactions.
- **TS-REG-02 (Binary Journal Determinism):** Replay recorded journal and verify order book state matches original run bit-for-bit.

---

## 3. Latency & Throughput Benchmark Harness

```
┌────────────────────────────────────────────────────────┐
│ JMH Microbenchmark Harness                             │
│ - Warmup: 200,000 iterations                           │
│ - Measurement: 500,000 iterations                      │
│ - Profiler: -prof gc, -prof perfcounters               │
│ - Distribution: HdrHistogram recording                 │
└───────────────────────────┬────────────────────────────┘
                            │
       ┌────────────────────┼────────────────────┐
       ▼                    ▼                    ▼
[ Tick-to-Trade ]    [ Order Book L3 ]    [ Risk 8-Checks ]
Target: p50 < 1.0µs  Target: p99 < 600ns  Target: p99 < 500ns
Max:    p99 < 4.0µs  Max:    p99 < 800ns  Max:    p99 < 300ns
```

---

## 4. Zero-Allocation Test Strategy

1. **JMH Allocation Profiler:** `@BenchmarkMode(Mode.SampleTime)` with `-prof gc` verifying `gc.alloc.rate.norm = 0 B/op`.
2. **ThreadAllocatedMemory Inspection:** Use `java.lang.management.ThreadMXBean.getThreadAllocatedBytes(threadId)` before and after 100,000 trading loop iterations; delta must be strictly 0.
3. **Epsilon GC Stress Drill:** Run trading pipeline under `-XX:+UseEpsilonGC -Xms64m -Xmx64m` with 10,000,000 continuous market data events; zero OutOfMemoryErrors allowed.
