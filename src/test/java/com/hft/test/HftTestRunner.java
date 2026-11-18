package com.hft.test;

import com.hft.audit.AuditJournalEntry;
import com.hft.core.buffer.MutableDirectBuffer;
import com.hft.core.buffer.UnsafeBuffer;
import com.hft.core.model.ModelConstants;
import com.hft.core.model.OrderStatus;
import com.hft.core.model.RiskRejectCode;
import com.hft.core.model.Side;
import com.hft.gateway.ExecutionGateway;
import com.hft.gateway.Ouch50WireFormatter;
import com.hft.marketdata.DualFeedArbitrator;
import com.hft.marketdata.itch.Itch50Flyweight;
import com.hft.orderbook.OrderBookL3;
import com.hft.pipeline.TradingEnginePipeline;
import com.hft.risk.PreTradeRiskGate;
import com.hft.strategy.AvellanedaStoikovStrategy;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Comprehensive Automated Functional, Zero-Allocation and Performance Test Suite.
 * Directly implements and validates every test scenario defined in QA Verification Plan.
 */
public class HftTestRunner {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  ULTRA-LOW LATENCY JAVA 21 HFT PLATFORM - VERIFICATION & TEST SUITE");
        System.out.println("================================================================================");

        runTest("TS-MD-01: Dual-Feed Deduplication", HftTestRunner::testDualFeedDeduplication);
        runTest("TS-MD-02: Single-Feed Gap Fallback", HftTestRunner::testSingleFeedGapFallback);
        runTest("TS-MD-03: Dual-Feed Gap & Defensive Quoting", HftTestRunner::testDualFeedGapDetection);
        runTest("TS-OB-01: L3 Order Lifecycle & Pointer Updates", HftTestRunner::testOrderBookLifecycle);
        runTest("TS-OB-02: Dynamic Queue Priority & Degradation", HftTestRunner::testQueuePriorityTracking);
        runTest("TS-OB-03: L2 Top-20 Depth Ladder Aggregation", HftTestRunner::testL2DepthLadder);
        runTest("TS-OB-04: Crossed Book Defense", HftTestRunner::testCrossedBookDefense);
        runTest("TS-RSK-01: Emergency Kill-Switch Gating", HftTestRunner::testKillSwitchGating);
        runTest("TS-RSK-02: Fat-Finger Price Collar", HftTestRunner::testFatFingerCollar);
        runTest("TS-RSK-03: Max Clip Size & Notional Cap", HftTestRunner::testClipAndNotionalLimits);
        runTest("TS-RSK-04: Inventory & Exposure Ceilings", HftTestRunner::testInventoryLimits);
        runTest("TS-RSK-05: Short Locate Verification", HftTestRunner::testShortLocate);
        runTest("TS-RSK-06: Rolling Window Rate Limiter", HftTestRunner::testRateLimiter);
        runTest("TS-RSK-07: Self-Cross / Wash Trade Prevention", HftTestRunner::testSelfCrossPrevention);
        runTest("TS-ST-01: Avellaneda-Stoikov Reservation Price Skew", HftTestRunner::testAvellanedaStoikovSkew);
        runTest("TS-ST-02: Order Book Imbalance (OBI) & Toxic Fade", HftTestRunner::testObiAndToxicFade);
        runTest("TS-GW-01: OUCH 5.0 Binary Wire Formatting", HftTestRunner::testOuchWireFormatting);
        runTest("TS-GW-02: Order State Lifecycle Machine", HftTestRunner::testOrderStateLifecycle);
        runTest("TS-REG-01: MiFID II RTS 25 Nanosecond Monotonicity", HftTestRunner::testTimestampMonotonicity);
        runTest("TS-ALLOC-01: Zero-Heap Allocation Invariant (100,000 Iterations)", HftTestRunner::testZeroHeapAllocation);
        runTest("TS-PERF-01: Nanosecond Latency Benchmarks (p50, p90, p99)", HftTestRunner::testLatencyPerformance);

        System.out.println("================================================================================");
        System.out.println("  TEST SUMMARY: " + testsPassed + " PASSED, " + testsFailed + " FAILED");
        System.out.println("================================================================================");

        if (testsFailed > 0) {
            System.exit(1);
        }
    }

    private static void runTest(String testName, Runnable test) {
        try {
            test.run();
            testsPassed++;
            System.out.printf("[PASS] %s%n", testName);
        } catch (Throwable t) {
            testsFailed++;
            System.out.printf("[FAIL] %s - %s%n", testName, t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    // --- TS-MD-01: Dual Feed Deduplication ---
    private static void testDualFeedDeduplication() {
        int[] processedCount = new int[1];
        DualFeedArbitrator arb = new DualFeedArbitrator((feed, seq, buf, off, len) -> processedCount[0]++, null);
        MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

        // Sequence 1 arrives on Feed A
        arb.onPacket(DualFeedArbitrator.FEED_A, 1L, buf, 0, 64);
        assertEquals(1, processedCount[0], "First packet must be processed");

        // Duplicate Sequence 1 arrives on Feed B
        arb.onPacket(DualFeedArbitrator.FEED_B, 1L, buf, 0, 64);
        assertEquals(1, processedCount[0], "Duplicate packet on Feed B must be dropped");
        assertEquals(1L, arb.getDroppedDuplicatesCount(), "Dropped duplicate counter must increment");
    }

    // --- TS-MD-02: Single-Feed Gap Fallback ---
    private static void testSingleFeedGapFallback() {
        int[] processedCount = new int[1];
        DualFeedArbitrator arb = new DualFeedArbitrator((feed, seq, buf, off, len) -> processedCount[0]++, null);
        MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

        // Sequence 1 on A
        arb.onPacket(DualFeedArbitrator.FEED_A, 1L, buf, 0, 64);
        // Sequence 2 dropped on A, but arrives on B
        arb.onPacket(DualFeedArbitrator.FEED_B, 2L, buf, 0, 64);
        // Sequence 3 on A
        arb.onPacket(DualFeedArbitrator.FEED_A, 3L, buf, 0, 64);

        assertEquals(3, processedCount[0], "All 3 sequences must be processed seamlessly");
        assertEquals(0L, arb.getGapCount(), "No gap should be recorded when alternate feed supplies sequence");
    }

    // --- TS-MD-03: Dual-Feed Gap & Defensive Quoting ---
    private static void testDualFeedGapDetection() {
        long[] gapInfo = new long[2];
        DualFeedArbitrator arb = new DualFeedArbitrator((feed, seq, buf, off, len) -> {},
                (exp, rec, feed) -> { gapInfo[0] = exp; gapInfo[1] = rec; });
        MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

        arb.onPacket(DualFeedArbitrator.FEED_A, 1L, buf, 0, 64);
        // Both feeds drop seq 2; seq 3 arrives
        arb.onPacket(DualFeedArbitrator.FEED_A, 3L, buf, 0, 64);

        assertEquals(2L, gapInfo[0], "Expected sequence must be 2");
        assertEquals(3L, gapInfo[1], "Received sequence must be 3");
        assertEquals(1L, arb.getGapCount(), "Gap count must increment");
        assertTrue(arb.isDefensiveQuoting(), "Arbitrator must enter DEFENSIVE_QUOTING mode");
    }

    // --- TS-OB-01: L3 Order Lifecycle ---
    private static void testOrderBookLifecycle() {
        OrderBookL3 book = new OrderBookL3(1024);

        // Add Buy Order: 100 shares @ $150.00
        book.addOrder(1001L, Side.BUY, 100, 1_500_000L);
        assertEquals(1_500_000L, book.getBestBidPrice(), "Best bid price");
        assertEquals(100L, book.getBestBidQty(), "Best bid qty");

        // Partial Execution: 40 shares
        book.executeOrder(1001L, 40);
        assertEquals(60L, book.getBestBidQty(), "Remaining shares after partial execution");

        // Order Cancel: remaining 60 shares
        book.cancelOrder(1001L, 60);
        assertEquals(0L, book.getBestBidPrice(), "Book should be empty after full cancellation");
        assertEquals(0L, book.getBestBidQty(), "Book qty should be 0");
    }

    // --- TS-OB-02: Queue Priority Tracking ---
    private static void testQueuePriorityTracking() {
        OrderBookL3 book = new OrderBookL3(1024);

        // Add 3 orders at same price: $150.00
        book.addOrder(1L, Side.BUY, 100, 1_500_000L);
        book.addOrder(2L, Side.BUY, 200, 1_500_000L);
        book.addOrder(3L, Side.BUY, 300, 1_500_000L); // Our quote

        assertEquals(300L, book.getVolumeAhead(3L), "Volume ahead of Order 3 should be 100 + 200 = 300");

        // Order 1 is cancelled
        book.cancelOrder(1L, 100);
        assertEquals(200L, book.getVolumeAhead(3L), "Volume ahead after Order 1 cancel should be 200");

        // Order 2 is partially executed by 50
        book.executeOrder(2L, 50);
        assertEquals(150L, book.getVolumeAhead(3L), "Volume ahead after Order 2 partial fill should be 150");
    }

    // --- TS-OB-03: L2 Top-20 Depth Ladder ---
    private static void testL2DepthLadder() {
        OrderBookL3 book = new OrderBookL3(1024);

        // Insert multiple bid levels
        book.addOrder(1L, Side.BUY, 100, 1_500_000L);
        book.addOrder(2L, Side.BUY, 200, 1_510_000L); // higher price, goes to top
        book.addOrder(3L, Side.BUY, 150, 1_490_000L); // lower price, 3rd level

        assertEquals(1_510_000L, book.getBidPriceAt(0), "Top bid price");
        assertEquals(200L, book.getBidVolumeAt(0), "Top bid volume");
        assertEquals(1_500_000L, book.getBidPriceAt(1), "Level 1 bid price");
        assertEquals(100L, book.getBidVolumeAt(1), "Level 1 bid volume");
        assertEquals(1_490_000L, book.getBidPriceAt(2), "Level 2 bid price");
        assertEquals(150L, book.getBidVolumeAt(2), "Level 2 bid volume");
    }

    // --- TS-OB-04: Crossed Book Defense ---
    private static void testCrossedBookDefense() {
        OrderBookL3 book = new OrderBookL3(1024);

        book.addOrder(1L, Side.BUY, 100, 1_500_000L);
        book.addOrder(2L, Side.SELL, 100, 1_510_000L);
        assertFalse(book.isCrossed(), "Book should not be crossed (Bid 150 < Ask 151)");

        // Add crossing bid: $152.00
        book.addOrder(3L, Side.BUY, 100, 1_520_000L);
        assertTrue(book.isCrossed(), "Book must detect crossed state (Bid 152 >= Ask 151)");
    }

    // --- TS-RSK-01: Emergency Kill-Switch ---
    private static void testKillSwitchGating() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        long now = System.nanoTime();

        // Normal order
        long res = risk.evaluateOrder(now, 1, Side.BUY, 100, 1_500_000L, 1_499_000L, 1_501_000L);
        assertEquals(RiskRejectCode.SUCCESS, res, "Normal order must pass");

        // Trip Kill-Switch
        risk.triggerEmergencyKillSwitch();
        res = risk.evaluateOrder(now, 1, Side.BUY, 100, 1_500_000L, 1_499_000L, 1_501_000L);
        assertTrue((res & RiskRejectCode.REJECT_KILL_SWITCH) != 0, "Must reject with REJECT_KILL_SWITCH");

        risk.resetEmergencyKillSwitch();
    }

    // --- TS-RSK-02: Fat-Finger Price Collar ---
    private static void testFatFingerCollar() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        risk.setMaxSpreadAllowed(20_000L); // $2.00 max spread
        long now = System.nanoTime();

        // Buy order far above Ask: Ask is $150.00, Buy is $155.00 (+$5.00)
        long res = risk.evaluateOrder(now, 1, Side.BUY, 100, 1_550_000L, 1_490_000L, 1_500_000L);
        assertTrue((res & RiskRejectCode.REJECT_PRICE_COLLAR) != 0, "Fat-finger buy must be rejected");

        // Sell order far below Bid: Bid is $150.00, Sell is $145.00 (-$5.00)
        res = risk.evaluateOrder(now, 1, Side.SELL, 100, 1_450_000L, 1_500_000L, 1_510_000L);
        assertTrue((res & RiskRejectCode.REJECT_PRICE_COLLAR) != 0, "Fat-finger sell must be rejected");
    }

    // --- TS-RSK-03: Max Clip Size & Notional Cap ---
    private static void testClipAndNotionalLimits() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        risk.setMaxClipSize(500);
        risk.setMaxNotionalAllowed(50_000L * ModelConstants.PRICE_SCALE); // $50,000 max
        long now = System.nanoTime();

        // Clip size violation: 1,000 shares > 500
        long res = risk.evaluateOrder(now, 1, Side.BUY, 1000, 100_000L, 99_000L, 101_000L);
        assertTrue((res & RiskRejectCode.REJECT_MAX_CLIP_SIZE) != 0, "Excessive clip size must be rejected");

        // Notional violation: 400 shares @ $200 = $80,000 > $50,000
        res = risk.evaluateOrder(now, 1, Side.BUY, 400, 2_000_000L, 1_990_000L, 2_010_000L);
        assertTrue((res & RiskRejectCode.REJECT_MAX_NOTIONAL) != 0, "Excessive notional must be rejected");
    }

    // --- TS-RSK-04: Inventory & Exposure Ceilings ---
    private static void testInventoryLimits() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        risk.setExposureLimits(1000, 1000);
        risk.updateInventory(900, 0, 0); // 900 long currently
        long now = System.nanoTime();

        // Buying 200 would result in 1,100 long > 1,000 max
        long res = risk.evaluateOrder(now, 1, Side.BUY, 200, 1_500_000L, 1_499_000L, 1_501_000L);
        assertTrue((res & RiskRejectCode.REJECT_MAX_INVENTORY) != 0, "Inventory ceiling breach must be rejected");
    }

    // --- TS-RSK-05: Short Locate Verification ---
    private static void testShortLocate() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        risk.updateInventory(0, 0, 0);
        risk.setLocate(5, false); // No short locate for security 5
        long now = System.nanoTime();

        // Selling short without locate
        long res = risk.evaluateOrder(now, 5, Side.SELL, 100, 1_500_000L, 1_499_000L, 1_501_000L);
        assertTrue((res & RiskRejectCode.REJECT_SHORT_LOCATE) != 0, "Short sale without locate must be rejected");

        // Add locate and retry
        risk.setLocate(5, true);
        res = risk.evaluateOrder(now, 5, Side.SELL, 100, 1_500_000L, 1_499_000L, 1_501_000L);
        assertEquals(RiskRejectCode.SUCCESS, res, "Short sale with valid locate must be approved");
    }

    // --- TS-RSK-06: Rolling Window Rate Limiter ---
    private static void testRateLimiter() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        long baseTime = 1_000_000_000L;

        // Saturate 200 orders in 1ms window
        for (int i = 0; i < 200; i++) {
            risk.evaluateOrder(baseTime + (i * 1000), 1, Side.BUY, 10, 1_500_000L, 1_499_000L, 1_501_000L);
        }

        // 201st order in same 1ms window must be rejected
        long res = risk.evaluateOrder(baseTime + 500_000, 1, Side.BUY, 10, 1_500_000L, 1_499_000L, 1_501_000L);
        assertTrue((res & RiskRejectCode.REJECT_RATE_LIMIT) != 0, "Rate limit threshold must trigger rejection");
    }

    // --- TS-RSK-07: Self-Cross Prevention ---
    private static void testSelfCrossPrevention() {
        PreTradeRiskGate risk = new PreTradeRiskGate();
        risk.updateRestingFirmQuotes(1_490_000L, 1_510_000L); // Firm is resting sell at $151.00
        long now = System.nanoTime();

        // New buy order at $151.00 would cross firm's own resting sell
        long res = risk.evaluateOrder(now, 1, Side.BUY, 100, 1_510_000L, 1_490_000L, 1_515_000L);
        assertTrue((res & RiskRejectCode.REJECT_SELF_CROSS) != 0, "Self-cross buy against own sell must be rejected");
    }

    // --- TS-ST-01: Avellaneda-Stoikov Reservation Price ---
    private static void testAvellanedaStoikovSkew() {
        AvellanedaStoikovStrategy strategy = new AvellanedaStoikovStrategy();
        OrderBookL3 book = new OrderBookL3(1024);

        book.addOrder(1L, Side.BUY, 100, 1_500_000L);
        book.addOrder(2L, Side.SELL, 100, 1_502_000L);

        // Inventory = 0 (neutral)
        AvellanedaStoikovStrategy.QuoteIntent neutral = strategy.evaluate(book, 0L);
        long neutralBid = neutral.targetBidPrice;

        // Inventory = +100 (Long): reservation price must skew quotes downward to dump inventory
        AvellanedaStoikovStrategy.QuoteIntent longSkew = strategy.evaluate(book, 100L);
        assertTrue(longSkew.targetBidPrice < neutralBid, "Long inventory must skew bid price downward");

        // Inventory = -100 (Short): reservation price must skew quotes upward to buy back
        AvellanedaStoikovStrategy.QuoteIntent shortSkew = strategy.evaluate(book, -100L);
        assertTrue(shortSkew.targetBidPrice > neutralBid, "Short inventory must skew bid price upward");
    }

    // --- TS-ST-02: OBI & Toxic Fade ---
    private static void testObiAndToxicFade() {
        AvellanedaStoikovStrategy strategy = new AvellanedaStoikovStrategy();
        OrderBookL3 book = new OrderBookL3(1024);

        // Huge buy imbalance: 1,000 bid vs 50 ask (OBI > +0.90)
        book.addOrder(1L, Side.BUY, 1000, 1_500_000L);
        book.addOrder(2L, Side.SELL, 50, 1_502_000L);

        AvellanedaStoikovStrategy.QuoteIntent quote = strategy.evaluate(book, 0L);
        assertTrue(quote.toxicFadeActive, "Heavy imbalance must activate toxic fade protection");
    }

    // --- TS-GW-01: OUCH 5.0 Wire Formatting ---
    private static void testOuchWireFormatting() {
        MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);
        int len = Ouch50WireFormatter.formatEnterOrder(buf, 0, 99999L, Side.BUY, 100,
                0x4141504c20202020L, 1_500_000, 1, 100);

        assertEquals(Ouch50WireFormatter.ENTER_ORDER_LENGTH, len, "OUCH EnterOrder length must be 49 bytes");
        assertEquals((byte) 'O', buf.getByte(0), "Message type must be 'O'");
        assertEquals(99999L, buf.getLongBigEndian(1), "ClientOrderId token");
        assertEquals(Side.BUY, buf.getByte(15), "Side byte");
        assertEquals(100, buf.getIntBigEndian(16), "Shares");
        assertEquals(1_500_000, buf.getIntBigEndian(28), "Price");
    }

    // --- TS-GW-02: Order State Lifecycle ---
    private static void testOrderStateLifecycle() {
        ExecutionGateway gw = new ExecutionGateway(128, (b, o, l) -> {});

        gw.submitNewOrder(12345L, Side.BUY, 100, 0x4141504cL, 1_500_000, 1, 1, 0, 0, 0);
        assertEquals(OrderStatus.PENDING_NEW, gw.getOrderState(12345L).orderStatus, "Status must be PENDING_NEW");

        gw.onOrderAcknowledged(12345L);
        assertEquals(OrderStatus.NEW, gw.getOrderState(12345L).orderStatus, "Status must be NEW");

        gw.onOrderFilled(12345L, 50);
        assertEquals(OrderStatus.PARTIALLY_FILLED, gw.getOrderState(12345L).orderStatus, "Status must be PARTIALLY_FILLED");

        gw.onOrderFilled(12345L, 50);
        assertNull(gw.getOrderState(12345L), "Filled order must be reclaimed back to pool");
    }

    // --- TS-REG-01: Nanosecond Monotonicity ---
    private static void testTimestampMonotonicity() {
        AuditJournalEntry entry = new AuditJournalEntry();
        long t1 = 1000L;
        long t2 = 1200L;
        long t3 = 1400L;
        long t4 = 1600L;

        entry.set(1L, 101L, Side.BUY, 1_500_000L, 100, RiskRejectCode.SUCCESS, t1, t2, t3, t4);
        assertTrue(entry.isMonotonic(), "Checkpoints T1 <= T2 <= T3 <= T4 must be monotonic");
    }

    // --- TS-ALLOC-01: Zero-Heap Allocation Invariant ---
    private static void testZeroHeapAllocation() {
        TradingEnginePipeline pipeline = new TradingEnginePipeline(2048, 2048, (b, o, l) -> {}, null);
        MutableDirectBuffer packetBuf = new UnsafeBuffer(new byte[64]);

        // Encode synthetic ITCH AddOrder message
        Itch50Flyweight.encodeAddOrder(packetBuf, 0, (short) 1, (short) 1, System.nanoTime(),
                1001L, (byte) 'B', 100, 0x4141504c20202020L, 1_500_000);

        // Pre-warm JIT C2 Tier 4 with 200,000 iterations (Pre-market startup warmup)
        for (int i = 0; i < 200_000; i++) {
            pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, i + 1, packetBuf, 0, 36);
        }

        // Measure allocated bytes using com.sun.management.ThreadMXBean
        com.sun.management.ThreadMXBean mxBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();

        long startBytes = mxBean.getThreadAllocatedBytes(threadId);

        // 100,000 steady-state market data updates through entire pipeline
        for (int i = 200_000; i < 300_000; i++) {
            pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, i + 1, packetBuf, 0, 36);
        }

        long endBytes = mxBean.getThreadAllocatedBytes(threadId);
        long allocatedDelta = endBytes - startBytes;

        System.out.printf("    [Zero-Allocation Test] 100,000 Trading Cycles: Allocated %d bytes on heap%n", allocatedDelta);
        assertEquals(0L, allocatedDelta, "Steady-state trading loop MUST allocate strictly 0 bytes on the heap!");
    }

    // --- TS-PERF-01: Nanosecond Latency Benchmarks ---
    private static void testLatencyPerformance() {
        TradingEnginePipeline pipeline = new TradingEnginePipeline(2048, 2048, (b, o, l) -> {}, null);
        MutableDirectBuffer packetBuf = new UnsafeBuffer(new byte[64]);

        Itch50Flyweight.encodeAddOrder(packetBuf, 0, (short) 1, (short) 1, System.nanoTime(),
                1001L, (byte) 'B', 100, 0x4141504c20202020L, 1_500_000);

        // Warmup JIT C2 Tier 4
        for (int i = 0; i < 100_000; i++) {
            pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, i + 1, packetBuf, 0, 36);
        }

        // Benchmark 100,000 iterations
        int sampleCount = 100_000;
        long[] samples = new long[sampleCount];

        for (int i = 0; i < sampleCount; i++) {
            long tStart = System.nanoTime();
            pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, 100_001 + i, packetBuf, 0, 36);
            long tEnd = System.nanoTime();
            samples[i] = tEnd - tStart;
        }

        Arrays.sort(samples);
        long p50 = samples[(int) (sampleCount * 0.50)];
        long p90 = samples[(int) (sampleCount * 0.90)];
        long p99 = samples[(int) (sampleCount * 0.99)];
        long p999 = samples[(int) (sampleCount * 0.999)];
        long maxJitter = samples[sampleCount - 1];

        System.out.printf("    [Benchmark Results - End-to-End Tick-to-Trade]:%n");
        System.out.printf("      p50:   %d ns (SLO target: < 1000 ns)%n", p50);
        System.out.printf("      p90:   %d ns (SLO target: < 2200 ns)%n", p90);
        System.out.printf("      p99:   %d ns (SLO target: < 4000 ns)%n", p99);
        System.out.printf("      p99.9: %d ns (SLO target: < 7500 ns)%n", p999);
        System.out.printf("      Max:   %d ns%n", maxJitter);

        assertTrue(p50 < 1000, "p50 latency must be < 1,000 ns");
        assertTrue(p99 < 4000, "p99 latency must be < 4,000 ns");
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " - expected [" + expected + "] but was [" + actual + "]");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertNull(Object obj, String message) {
        if (obj != null) {
            throw new AssertionError(message + " - expected null but was [" + obj + "]");
        }
    }
}
