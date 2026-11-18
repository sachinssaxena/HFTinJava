# Implementation & Testing Roadmap: Ultra-Low Latency Java HFT Platform

**Document Version:** 1.0.0  
**Architect:** Technical Architect (@tech-architect)  
**Assigned Developers & QA:** @dev (Developer), @qa (QA Engineer)  
**Stakeholders:** @product-owner (PO), @coordinator  
**Date:** 2026-11-19  

---

## 1. Project Modular Structure (Maven / Java 21)

Root artifact: `hft-platform` (Multi-module or clean single-module project with structured packages).

### Core Dependencies:
* `org.agrona:agrona:1.21.2` (DirectBuffer, UnsafeBuffer, primitive maps/sets, lock-free ring buffers)
* `com.lmax:disruptor:4.0.0` (Lock-free circular ring buffer inter-thread messaging)
* `net.openhft:affinity:3.23.3` (Thread affinity & core pinning)
* `org.openjdk.jmh:jmh-core:1.37` & `jmh-generator-annprocess:1.37` (Microbenchmarking)
* `org.junit.jupiter:junit-jupiter:5.10.2` (Unit testing)

---

## 2. Phased Implementation Roadmap for @dev

### Phase 1: Core Framework, Flyweights & Market Data Engine
* **P1.1 Build Setup & Common Primitives:**
  - Setup Maven `pom.xml` targeting Java 21 with preview/compiler options.
  - Define fixed-point primitive arithmetic constants (e.g. `PRICE_SCALE = 10_000L` for Equities, `10_000_000L` for CME).
  - Common models: `Side` (byte), `OrderType` (byte), `TimeInForce` (byte), `ExecutionReport` flyweights.
* **P1.2 Dual-Feed UDP Market Data & Ingestion (Epic 1):**
  - Implement dual-feed A/B channel receiver with hardware-efficient sequence deduplication.
  - Implement zero-copy flyweight decoders for NASDAQ ITCH 5.0 / CME MDP 3.0 SBE using Agrona `DirectBuffer`.
  - Implement sequence gap detection and out-of-band replay event dispatch.

### Phase 2: Contiguous Order Book Engine (Epic 2)
* **P2.1 Level 2 (MBP) Price-Aggregated Depth Ladder:**
  - Contiguous fixed-point tick arrays for top 20 bids and asks with $O(1)$ price indexing.
* **P2.2 Level 3 (MBO) Market-By-Order Engine:**
  - Pre-allocated off-heap / array-backed `OrderPool` with doubly-linked pointers for $O(1)$ node insertion/removal.
  - Agrona `Long2IntHashMap` mapping `orderId` to pool index.
  - Dynamic queue position calculation (orders and cumulative volume ahead).

### Phase 3: Pre-Trade Risk Engine (SEC Rule 15c3-5) (Epic 4)
* **P3.1 Sub-300ns In-Line Risk Gate:**
  - Implement all 8 mandatory checks:
    1. Emergency Kill-Switch (volatile boolean / atomic check)
    2. Fat-Finger Price Collar
    3. Maximum Clip Size
    4. Maximum Order Notional Value
    5. Aggregate Position & Inventory Limits
    6. Short Locate Verification
    7. High-Speed Rate Limiting / Churn Controls (circular rolling window buffer)
    8. Self-Cross / Wash Trade Prevention
  - Zero heap allocation invariant; return reject code bitmask.

### Phase 4: Quoting Strategy Engine (Epic 3)
* **P4.1 Avellaneda-Stoikov Market Making Core:**
  - Reservation price computation using fixed-point arithmetic.
  - Inventory-skewed spread optimization.
* **P4.2 Microstructure Alpha Signals & Toxic Flow:**
  - Order Book Imbalance (OBI) computation.
  - Micro-price volume weighting.
  - Adverse selection / toxic sweep detection triggering quote fading or bulk cancel.

### Phase 5: Execution Gateway & Order State Management (Epic 5 & 6)
* **P5.1 Outbound Binary Formatter (OUCH 5.0 / iLink 3):**
  - Wire formatter directly into off-heap direct byte buffer.
  - Pre-allocated `Long2ObjectHashMap` order state cache.
* **P5.2 MiFID II RTS 25 Nanosecond Audit & Event Journaling:**
  - $T_1$ to $T_4$ timestamp recording.
  - Asynchronous ring buffer tap to binary journal.

---

## 3. Testing, Verification & Benchmarking Roadmap for @qa

### Test Category 1: Zero-Heap Allocation Verification
* Instrument unit and integration tests using JVM options or allocation counters:
  - Verify that during 100,000 steady-state market data updates and order cycles, heap allocation is **strictly 0 bytes**.
  - Any memory allocation flagged by QA must fail the build.

### Test Category 2: JMH Latency & Jitter Microbenchmarks
* Author JMH benchmarks for each pipeline stage:
  1. `BenchmarkMarketDataIngest`: Decode latency, A/B arbitration overhead (< 250ns target).
  2. `BenchmarkOrderBookL3`: Order add/modify/cancel latency (< 450ns target).
  3. `BenchmarkPreTradeRisk`: All 8 checks latency (< 300ns target).
  4. `BenchmarkQuotingStrategy`: Reservation price, OBI, micro-price (< 450ns target).
  5. `BenchmarkEndToEndTickToTrade`: Ingestion to Gateway wire formatting (< 2.5µs p99 target).

### Test Category 3: Edge Case, Risk & Regulatory Stress Drills
* **SEC 15c3-5 Gating Drills:**
  - Verify each of the 8 risk rules fires correctly and rejects invalid orders without throwing exceptions.
  - Test emergency kill-switch under 100,000 orders/sec burst.
* **Market Data Packet Loss & Replay Drills:**
  - Simulate packet drop on Channel A; verify Channel B seamless consumption.
  - Simulate dual packet drops; verify gap detection and defensive quoting transition.
* **Order Book Integrity:**
  - Out-of-order execution, partial fills, queue position degradation under stress.

---

## 4. Immediate Action Items
1. **@dev**: Begin Phase 1 (project build setup with Agrona/Disruptor, fixed-point primitive definitions, and Dual-Feed Market Data Ingest + Flyweight SBE/ITCH decoders).
2. **@qa**: Setup the testing harness (JUnit 5 + JMH framework + Zero-Allocation test assertions). Prepare test plans for Phase 1 and the SEC 15c3-5 Risk Gate.