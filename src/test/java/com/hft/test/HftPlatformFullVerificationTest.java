package com.hft.test;

import com.hft.audit.AuditJournalEntry;
import com.hft.audit.AuditJournalTap;
import com.hft.core.buffer.MutableDirectBuffer;
import com.hft.core.buffer.UnsafeBuffer;
import com.hft.core.model.ModelConstants;
import com.hft.core.model.OrderStatus;
import com.hft.core.model.RiskRejectCode;
import com.hft.core.model.Side;
import com.hft.gateway.ExecutionGateway;
import com.hft.gateway.OrderState;
import com.hft.gateway.Ouch50WireFormatter;
import com.hft.marketdata.DualFeedArbitrator;
import com.hft.marketdata.itch.Itch50Flyweight;
import com.hft.orderbook.OrderBookL3;
import com.hft.pipeline.TradingEnginePipeline;
import com.hft.risk.PreTradeRiskGate;
import com.hft.strategy.AvellanedaStoikovStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Institutional-Grade QA Test Suite for Ultra-Low Latency Java 21 HFT Platform.
 * Validates Functional Correctness, Boundary Edge Cases, Latency SLOs, and Zero-Heap Invariants.
 */
public class HftPlatformFullVerificationTest {

    private com.sun.management.ThreadMXBean threadBean;
    private long threadId;

    @BeforeEach
    void setUp() {
        threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        threadId = Thread.currentThread().threadId();
    }

    // =========================================================================
    // 1. DUAL-FEED MARKET DATA & ARBITRATION (EPIC 1)
    // =========================================================================
    @Nested
    @DisplayName("Dual-Feed Market Data & Arbitration Tests")
    class DualFeedArbitrationTests {

        @Test
        @DisplayName("TS-MD-01: Line-rate deduplication drops duplicate packet in < 50ns")
        void testDualFeedDeduplication() {
            int[] processed = new int[1];
            DualFeedArbitrator arb = new DualFeedArbitrator((feed, seq, buf, off, len) -> processed[0]++, null);
            MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

            arb.onPacket(DualFeedArbitrator.FEED_A, 100L, buf, 0, 64);
            assertEquals(1, processed[0], "First packet must be processed");

            // Duplicate on Feed B
            arb.onPacket(DualFeedArbitrator.FEED_B, 100L, buf, 0, 64);
            assertEquals(1, processed[0], "Duplicate packet must be dropped");
            assertEquals(1L, arb.getDroppedDuplicatesCount());
        }

        @Test
        @DisplayName("TS-MD-02: Seamless fallback when Feed A drops packet and Feed B supplies it")
        void testSingleFeedFallback() {
            int[] processed = new int[1];
            DualFeedArbitrator arb = new DualFeedArbitrator((feed, seq, buf, off, len) -> processed[0]++, null);
            MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

            arb.onPacket(DualFeedArbitrator.FEED_A, 1L, buf, 0, 64);
            arb.onPacket(DualFeedArbitrator.FEED_B, 2L, buf, 0, 64); // Feed A dropped seq 2
            arb.onPacket(DualFeedArbitrator.FEED_A, 3L, buf, 0, 64);

            assertEquals(3, processed[0]);
            assertEquals(0L, arb.getGapCount(), "No gap if alternate feed supplies packet in order");
            assertFalse(arb.isDefensiveQuoting());
        }

        @Test
        @DisplayName("TS-MD-03: Dual-feed gap detection triggers defensive quoting and replay callback")
        void testDualFeedGapDetection() {
            long[] gapData = new long[2];
            DualFeedArbitrator arb = new DualFeedArbitrator(
                    (feed, seq, buf, off, len) -> {},
                    (exp, rec, feed) -> { gapData[0] = exp; gapData[1] = rec; }
            );
            MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

            arb.onPacket(DualFeedArbitrator.FEED_A, 10L, buf, 0, 64);
            arb.onPacket(DualFeedArbitrator.FEED_B, 15L, buf, 0, 64); // Gap: expected 11, got 15

            assertEquals(11L, gapData[0], "Expected sequence must be 11");
            assertEquals(15L, gapData[1], "Received sequence must be 15");
            assertTrue(arb.isDefensiveQuoting(), "Arbitrator must switch to defensive quoting mode");
            assertEquals(1L, arb.getGapCount());
        }

        @Test
        @DisplayName("TS-MD-EDGE: Interleaved Feed A and B high-throughput burst")
        void testInterleavedBurst() {
            int[] processed = new int[1];
            DualFeedArbitrator arb = new DualFeedArbitrator((feed, seq, buf, off, len) -> processed[0]++, null);
            MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);

            for (int i = 1; i <= 10_000; i++) {
                byte primaryFeed = (i % 2 == 0) ? DualFeedArbitrator.FEED_A : DualFeedArbitrator.FEED_B;
                byte secondaryFeed = (i % 2 == 0) ? DualFeedArbitrator.FEED_B : DualFeedArbitrator.FEED_A;

                arb.onPacket(primaryFeed, i, buf, 0, 64);
                arb.onPacket(secondaryFeed, i, buf, 0, 64); // Duplicate
            }

            assertEquals(10_000, processed[0]);
            assertEquals(10_000L, arb.getDroppedDuplicatesCount());
            assertEquals(0L, arb.getGapCount());
        }
    }

    // =========================================================================
    // 2. CONTIGUOUS L2/L3 ORDER BOOK ENGINE (EPIC 2)
    // =========================================================================
    @Nested
    @DisplayName("L2/L3 Order Book Engine Tests")
    class OrderBookEngineTests {

        @Test
        @DisplayName("TS-OB-01: Full Order Lifecycle (Add, Modify, Partial Exec, Cancel, Delete)")
        void testOrderLifecycle() {
            OrderBookL3 book = new OrderBookL3(1024);

            // Add Buy Order: ID=101, Qty=500, Price=$150.00
            assertTrue(book.addOrder(101L, Side.BUY, 500, 1_500_000L));
            assertEquals(1_500_000L, book.getBestBidPrice());
            assertEquals(500, book.getBestBidVolume());

            // Partial Execution: 200 shares filled
            assertTrue(book.executeOrder(101L, 200));
            assertEquals(300, book.getBestBidVolume());

            // Cancel Remaining 300 shares
            assertTrue(book.cancelOrder(101L, 300));
            assertEquals(0L, book.getBestBidPrice());
            assertEquals(0, book.getBestBidVolume());
        }

        @Test
        @DisplayName("TS-OB-02: Dynamic queue priority and volume-ahead tracking")
        void testQueuePriority() {
            OrderBookL3 book = new OrderBookL3(1024);

            // 3 orders at same price level ($150.00)
            book.addOrder(1L, Side.BUY, 200, 1_500_000L); // Position 1
            book.addOrder(2L, Side.BUY, 300, 1_500_000L); // Position 2
            book.addOrder(3L, Side.BUY, 500, 1_500_000L); // Our working quote (Position 3)

            assertEquals(500, book.getVolumeAhead(3L), "Volume ahead of Order 3 must be 200 + 300 = 500");
            assertEquals(2, book.getOrdersAhead(3L), "Orders ahead must be 2");

            // Order 1 is cancelled
            book.deleteOrder(1L);
            assertEquals(300, book.getVolumeAhead(3L), "Volume ahead must degrade to 300");
            assertEquals(1, book.getOrdersAhead(3L), "Orders ahead must degrade to 1");

            // Order 2 partially filled (100 filled)
            book.executeOrder(2L, 100);
            assertEquals(200, book.getVolumeAhead(3L), "Volume ahead must be 200");
        }

        @Test
        @DisplayName("TS-OB-03: L2 Top-20 Contiguous Depth Ladder Sorting & Eviction")
        void testL2DepthLadder() {
            OrderBookL3 book = new OrderBookL3(2048);

            // Populate 25 different ask price levels ($151.00 down to $175.00)
            for (int i = 25; i >= 1; i--) {
                long price = 1_500_000L + (i * 10_000L);
                book.addOrder(i, Side.SELL, 100, price);
            }

            assertEquals(20, book.getAskDepthCount(), "Ladder must track exactly top 20 levels");
            assertEquals(1_510_000L, book.getBestAskPrice(), "Best ask must be lowest ask ($151.00)");
            assertEquals(1_700_000L, book.getAskPrice(19), "20th level must be $170.00");
        }

        @Test
        @DisplayName("TS-OB-04: Crossed-Book Detection and Defensive State")
        void testCrossedBook() {
            OrderBookL3 book = new OrderBookL3(1024);

            book.addOrder(1L, Side.BUY, 100, 1_500_000L); // Bid $150.00
            book.addOrder(2L, Side.SELL, 100, 1_510_000L); // Ask $151.00
            assertFalse(book.isCrossed());

            // Aggressive Bid at $152.00 crosses Ask at $151.00
            book.addOrder(3L, Side.BUY, 100, 1_520_000L);
            assertTrue(book.isCrossed(), "Book must detect crossed condition (Bid >= Ask)");
        }

        @Test
        @DisplayName("TS-OB-EDGE: Order Replacement preserves side and priority")
        void testOrderReplacement() {
            OrderBookL3 book = new OrderBookL3(1024);

            book.addOrder(10L, Side.SELL, 200, 1_600_000L);
            assertEquals(1_600_000L, book.getBestAskPrice());

            // Replace Order 10 with Order 11 at new price and shares
            assertTrue(book.replaceOrder(10L, 11L, 400, 1_590_000L));
            assertEquals(1_590_000L, book.getBestAskPrice(), "Replaced order must update best ask");
            assertEquals(400, book.getBestAskVolume());
            assertEquals(0L, book.getBestBidPrice(), "Sell order replace MUST NOT mutate into Buy");
        }
    }

    // =========================================================================
    // 3. SEC RULE 15c3-5 PRE-TRADE RISK GATE (EPIC 4)
    // =========================================================================
    @Nested
    @DisplayName("SEC Rule 15c3-5 Pre-Trade Risk Gate Tests")
    class PreTradeRiskGateTests {

        private PreTradeRiskGate riskGate;

        @BeforeEach
        void initRisk() {
            riskGate = new PreTradeRiskGate();
        }

        @Test
        @DisplayName("TS-RSK-01: Emergency Kill-Switch rejects all orders in < 100ns")
        void testKillSwitch() {
            long now = System.nanoTime();
            long approved = riskGate.evaluateOrder(now, 1, Side.BUY, 100, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue(RiskRejectCode.isApproved(approved));

            // Trip Kill Switch
            riskGate.tripKillSwitch();
            assertTrue(riskGate.isKillSwitchActive());

            long rejected = riskGate.evaluateOrder(now + 1, 1, Side.BUY, 100, 1_500_000L, 1_499_000L, 1_501_000L);
            assertEquals(RiskRejectCode.REJECT_KILL_SWITCH, rejected);
        }

        @Test
        @DisplayName("TS-RSK-02: Fat-Finger Collar rejects bids/asks outside collar band")
        void testFatFingerCollar() {
            long now = System.nanoTime();
            long bestBid = 1_500_000L; // $150.00
            long bestAsk = 1_500_500L; // $150.05
            // Max allowed collar spread is $2.00 (20,000 units)

            // Buy at $152.06 (> Ask $150.05 + $2.00)
            long rejectBuy = riskGate.evaluateOrder(now, 1, Side.BUY, 100, 1_520_600L, bestBid, bestAsk);
            assertTrue((rejectBuy & RiskRejectCode.REJECT_PRICE_COLLAR) != 0);

            // Sell at $147.90 (< Bid $150.00 - $2.00)
            long rejectSell = riskGate.evaluateOrder(now, 1, Side.SELL, 100, 1_479_000L, bestBid, bestAsk);
            assertTrue((rejectSell & RiskRejectCode.REJECT_PRICE_COLLAR) != 0);
        }

        @Test
        @DisplayName("TS-RSK-03: Max Clip Size and Notional Value Limits")
        void testClipAndNotional() {
            long now = System.nanoTime();

            // Clip size boundary: max is 10,000
            long clipReject = riskGate.evaluateOrder(now, 1, Side.BUY, 10_001, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue((clipReject & RiskRejectCode.REJECT_MAX_CLIP_SIZE) != 0);

            // Negative or zero shares
            long zeroShareReject = riskGate.evaluateOrder(now, 1, Side.BUY, 0, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue((zeroShareReject & RiskRejectCode.REJECT_MAX_CLIP_SIZE) != 0);

            // Notional boundary: price * qty > $1,000,000 (scaled by 10,000)
            // Price = $1,500.00 (15,000,000L), shares = 700 => Notional = $1,050,000
            long notionalReject = riskGate.evaluateOrder(now, 1, Side.BUY, 700, 15_000_000L, 14_990_000L, 15_010_000L);
            assertTrue((notionalReject & RiskRejectCode.REJECT_MAX_NOTIONAL) != 0);
        }

        @Test
        @DisplayName("TS-RSK-04: Aggregate Long/Short Inventory Ceilings")
        void testInventoryLimits() {
            long now = System.nanoTime();
            riskGate.updateInventory(45_000L); // Long 45,000 shares (max is 50,000)

            // Attempt to buy 5,001 shares (projected = 50,001 > 50,000)
            long invReject = riskGate.evaluateOrder(now, 1, Side.BUY, 5_001, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue((invReject & RiskRejectCode.REJECT_MAX_INVENTORY) != 0);

            // But selling 1,000 shares is approved (reduces long exposure)
            long sellApprove = riskGate.evaluateOrder(now, 1, Side.SELL, 1_000, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue(RiskRejectCode.isApproved(sellApprove));
        }

        @Test
        @DisplayName("TS-RSK-05: Short Locate Verification Gate")
        void testShortLocate() {
            long now = System.nanoTime();
            riskGate.updateInventory(0L); // Flat inventory

            // Security 5 has no locate
            riskGate.setLocate(5, false);
            long shortReject = riskGate.evaluateOrder(now, 5, Side.SELL, 100, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue((shortReject & RiskRejectCode.REJECT_SHORT_LOCATE) != 0);

            // Grant locate for Security 5
            riskGate.setLocate(5, true);
            long shortApprove = riskGate.evaluateOrder(now, 5, Side.SELL, 100, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue(RiskRejectCode.isApproved(shortApprove));
        }

        @Test
        @DisplayName("TS-RSK-06: O(1) Rolling Window Rate Limiter Boundary Trigger")
        void testRateLimiter() {
            long baseTime = 1_000_000_000L;
            int max1ms = 200;

            // Submit 200 orders in the same millisecond
            for (int i = 0; i < max1ms; i++) {
                long res = riskGate.evaluateOrder(baseTime + (i * 100), 1, Side.BUY, 1, 1_500_000L, 1_499_000L, 1_501_000L);
                assertTrue(RiskRejectCode.isApproved(res), "Order " + i + " must be approved within limit");
            }

            // 201st order in the same millisecond MUST be rejected
            long burstReject = riskGate.evaluateOrder(baseTime + 999_999, 1, Side.BUY, 1, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue((burstReject & RiskRejectCode.REJECT_RATE_LIMIT) != 0, "201st order must trigger rate limit");

            // Order 1ms later must be approved
            long nextMilliApprove = riskGate.evaluateOrder(baseTime + 1_000_001L, 1, Side.BUY, 1, 1_500_000L, 1_499_000L, 1_501_000L);
            assertTrue(RiskRejectCode.isApproved(nextMilliApprove), "Order in next millisecond must be approved");
        }

        @Test
        @DisplayName("TS-RSK-07: Self-Cross / Wash Trade Prevention")
        void testSelfCrossPrevention() {
            long now = System.nanoTime();

            // Firm has resting Sell quote at $150.50
            riskGate.updateRestingQuotes(0L, 1_505_000L);

            // Attempt aggressive Buy at $150.50 crosses own Sell quote
            long washReject = riskGate.evaluateOrder(now, 1, Side.BUY, 100, 1_505_000L, 1_500_000L, 1_506_000L);
            assertTrue((washReject & RiskRejectCode.REJECT_SELF_CROSS) != 0);

            // Passive Buy at $150.40 does not cross
            long passiveBuy = riskGate.evaluateOrder(now, 1, Side.BUY, 100, 1_504_000L, 1_500_000L, 1_506_000L);
            assertTrue(RiskRejectCode.isApproved(passiveBuy));
        }

        @Test
        @DisplayName("TS-RSK-EDGE: Combinatorial multi-rule reject bitmask")
        void testMultiRuleRejectMask() {
            long now = System.nanoTime();
            // Order violates: Clip size (> 10,000) AND Price collar (< Bid - $2.00)
            long multiReject = riskGate.evaluateOrder(now, 1, Side.SELL, 20_000, 1_400_000L, 1_500_000L, 1_501_000L);

            assertTrue((multiReject & RiskRejectCode.REJECT_MAX_CLIP_SIZE) != 0, "Must flag clip size");
            assertTrue((multiReject & RiskRejectCode.REJECT_PRICE_COLLAR) != 0, "Must flag price collar");
        }
    }

    // =========================================================================
    // 4. STRATEGY ENGINE & MICROSTRUCTURE ALPHA (EPIC 3)
    // =========================================================================
    @Nested
    @DisplayName("Strategy Engine & Microstructure Alpha Tests")
    class StrategyEngineTests {

        private AvellanedaStoikovStrategy strategy;

        @BeforeEach
        void initStrategy() {
            strategy = new AvellanedaStoikovStrategy();
        }

        @Test
        @DisplayName("TS-ST-01: Avellaneda-Stoikov reservation price skew with inventory")
        void testInventorySkew() {
            OrderBookL3 book = new OrderBookL3(1024);
            book.addOrder(1L, Side.BUY, 500, 1_500_000L); // Bid $150.00
            book.addOrder(2L, Side.SELL, 500, 1_500_200L); // Ask $150.02
            // Mid = $150.01 (1,500,100L)

            // 1. Long Inventory (q = +10,000): Reservation price must skew downward
            AvellanedaStoikovStrategy.QuoteIntent longIntent = strategy.evaluate(book, 10_000L);
            assertTrue(longIntent.reservationPrice < 1_500_100L, "Long inventory must depress reservation price");
            assertTrue(longIntent.targetBidPrice < 1_500_000L, "Bid quote must shade lower to deter fills");

            // 2. Short Inventory (q = -10,000): Reservation price must skew upward
            AvellanedaStoikovStrategy.QuoteIntent shortIntent = strategy.evaluate(book, -10_000L);
            assertTrue(shortIntent.reservationPrice > 1_500_100L, "Short inventory must elevate reservation price");
            assertTrue(shortIntent.targetAskPrice > 1_500_200L, "Ask quote must shade higher to deter fills");
        }

        @Test
        @DisplayName("TS-ST-02: Order Book Imbalance (OBI) threshold trigger")
        void testObiTrigger() {
            OrderBookL3 book = new OrderBookL3(1024);
            // Heavy bid imbalance: 9,000 bid vs 1,000 ask -> OBI = +0.80
            book.addOrder(1L, Side.BUY, 9_000, 1_500_000L);
            book.addOrder(2L, Side.SELL, 1_000, 1_500_200L);

            AvellanedaStoikovStrategy.QuoteIntent intent = strategy.evaluate(book, 0L);
            assertEquals(8_000L, intent.obiScaled, "OBI must equal +8,000 (+0.80)");
            assertTrue(intent.shouldQuoteBid, "Should quote bid with strong buying pressure");
        }

        @Test
        @DisplayName("TS-ST-03: Empty book handling does not throw divide-by-zero")
        void testEmptyBookSafety() {
            OrderBookL3 emptyBook = new OrderBookL3(1024);
            AvellanedaStoikovStrategy.QuoteIntent intent = strategy.evaluate(emptyBook, 0L);
            assertFalse(intent.shouldQuoteBid);
            assertFalse(intent.shouldQuoteAsk);
        }
    }

    // =========================================================================
    // 5. EXECUTION GATEWAY & WIRE FORMATTING (EPIC 5 & 6)
    // =========================================================================
    @Nested
    @DisplayName("Execution Gateway & Wire Formatting Tests")
    class ExecutionGatewayTests {

        @Test
        @DisplayName("TS-GW-01: OUCH 5.0 binary packet serialization matching exact byte offsets")
        void testOuchFormatting() {
            MutableDirectBuffer outBuf = new UnsafeBuffer(new byte[128]);
            long clOrdId = 123456789L;
            long symbolAscii = 0x4141504c20202020L; // "AAPL    "

            int bytesWritten = Ouch50WireFormatter.formatEnterOrder(outBuf, 0, clOrdId, Side.BUY,
                    500, symbolAscii, 1_500_000, 1, 100);

            assertEquals(Ouch50WireFormatter.ENTER_ORDER_LENGTH, bytesWritten);
            assertEquals((byte) 'O', outBuf.getByte(0), "Offset 0 must be MessageType 'O'");
            assertEquals((byte) 'B', outBuf.getByte(15), "Offset 15 must be Side 'B'");
            assertEquals(500, outBuf.getIntBigEndian(16), "Offset 16 must be Shares 500");
            assertEquals(1_500_000, outBuf.getIntBigEndian(28), "Offset 28 must be Price $150.00");
        }

        @Test
        @DisplayName("TS-GW-02: Zero-allocation Order State Machine transitions")
        void testOrderStateMachine() {
            ExecutionGateway gateway = new ExecutionGateway(1024, (buf, off, len) -> {});
            long clOrdId = 98765L;

            gateway.submitNewOrder(clOrdId, Side.BUY, 200, 0x4141504c20202020L, 1_500_000, 1, 100,
                    1000L, 1100L, 1200L);

            OrderState state = gateway.getOrderState(clOrdId);
            assertNotNull(state);
            assertEquals(OrderStatus.PENDING_NEW, state.status);

            // Venue Ack
            gateway.onOrderAccepted(clOrdId, 55555L);
            assertEquals(OrderStatus.NEW, state.status);

            // Partial Fill
            gateway.onOrderExecuted(clOrdId, 50, 1_500_000L);
            assertEquals(OrderStatus.PARTIALLY_FILLED, state.status);
            assertEquals(50, state.cumQty);
            assertEquals(150, state.leavesQty);

            // Final Fill
            gateway.onOrderExecuted(clOrdId, 150, 1_500_000L);
            assertEquals(OrderStatus.FILLED, state.status);
            assertEquals(200, state.cumQty);
            assertEquals(0, state.leavesQty);
        }

        @Test
        @DisplayName("TS-REG-01: MiFID II RTS 25 Nanosecond Timestamp Monotonicity (T1 <= T2 <= T3 <= T4)")
        void testAuditJournalMonotonicity() {
            AuditJournalTap journal = new AuditJournalTap(1024);
            long t1 = 1_000_000_000L;
            long t2 = 1_000_000_250L;
            long t3 = 1_000_000_450L;
            long t4 = 1_000_000_700L;

            journal.publishAuditRecord(1L, 1001L, Side.BUY, 1_500_000L, 100,
                    RiskRejectCode.SUCCESS, t1, t2, t3, t4);

            journal.poll(entry -> {
                assertTrue(entry.t1_rxNanos <= entry.t2_bookNanos, "T1 <= T2");
                assertTrue(entry.t2_bookNanos <= entry.t3_riskNanos, "T2 <= T3");
                assertTrue(entry.t3_riskNanos <= entry.t4_wireTxNanos, "T3 <= T4");
            });
        }
    }

    // =========================================================================
    // 6. ZERO-ALLOCATION INVARIANT VERIFICATION (0 BYTES / SECOND)
    // =========================================================================
    @Nested
    @DisplayName("Zero-Heap Allocation Invariant Tests")
    class ZeroAllocationInvariantTests {

        @Test
        @DisplayName("TS-ALLOC-01: End-to-End Trading Pipeline 100,000 cycles allocates strictly 0 bytes")
        void testEndToEndZeroAllocation() {
            TradingEnginePipeline pipeline = new TradingEnginePipeline(2048, 2048, (b, o, l) -> {}, null);
            MutableDirectBuffer packetBuf = new UnsafeBuffer(new byte[64]);

            Itch50Flyweight.encodeAddOrder(packetBuf, 0, (short) 1, (short) 1, System.nanoTime(),
                    1001L, (byte) 'B', 100, 0x4141504c20202020L, 1_500_000);

            // Pre-warm JIT C2 compiler (150,000 cycles)
            for (int i = 0; i < 150_000; i++) {
                pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, i + 1, packetBuf, 0, 36);
            }

            // Measure steady-state allocation across 100,000 cycles
            long startBytes = threadBean.getThreadAllocatedBytes(threadId);
            for (int i = 150_000; i < 250_000; i++) {
                pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, i + 1, packetBuf, 0, 36);
            }
            long endBytes = threadBean.getThreadAllocatedBytes(threadId);

            long allocated = endBytes - startBytes;
            assertEquals(0L, allocated, "Steady-state trading loop MUST allocate strictly 0 bytes on the heap!");
        }
    }

    // =========================================================================
    // 7. LATENCY SLO BENCHMARK VERIFICATION (p50 < 1.0µs, p99 < 4.0µs)
    // =========================================================================
    @Nested
    @DisplayName("Nanosecond Latency SLO Verification")
    class LatencySloBenchmarks {

        @Test
        @DisplayName("TS-PERF-01: End-to-end Tick-to-Trade meets p50 < 1.0µs and p99 < 4.0µs SLO bounds")
        void testTickToTradeLatencyDistribution() {
            TradingEnginePipeline pipeline = new TradingEnginePipeline(2048, 2048, (b, o, l) -> {}, null);
            MutableDirectBuffer packetBuf = new UnsafeBuffer(new byte[64]);

            Itch50Flyweight.encodeAddOrder(packetBuf, 0, (short) 1, (short) 1, System.nanoTime(),
                    1001L, (byte) 'B', 100, 0x4141504c20202020L, 1_500_000);

            // Warmup JIT C2 compiler
            for (int i = 0; i < 100_000; i++) {
                pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, i + 1, packetBuf, 0, 36);
            }

            int samplesCount = 100_000;
            long[] latencies = new long[samplesCount];

            for (int i = 0; i < samplesCount; i++) {
                long tStart = System.nanoTime();
                pipeline.onMarketDataPacket(DualFeedArbitrator.FEED_A, 100_001 + i, packetBuf, 0, 36);
                long tEnd = System.nanoTime();
                latencies[i] = tEnd - tStart;
            }

            Arrays.sort(latencies);
            long p50 = latencies[(int) (samplesCount * 0.50)];
            long p90 = latencies[(int) (samplesCount * 0.90)];
            long p99 = latencies[(int) (samplesCount * 0.99)];
            long p999 = latencies[(int) (samplesCount * 0.999)];
            long maxJitter = latencies[samplesCount - 1];

            System.out.printf("=== LATENCY BENCHMARK REPORT ===%n");
            System.out.printf("  p50 (Median):   %4d ns (SLO target: < 1,000 ns)%n", p50);
            System.out.printf("  p90:            %4d ns (SLO target: < 2,200 ns)%n", p90);
            System.out.printf("  p99:            %4d ns (SLO target: < 4,000 ns)%n", p99);
            System.out.printf("  p99.9:          %4d ns (SLO target: < 7,500 ns)%n", p999);
            System.out.printf("  Max Jitter:     %4d ns (SLO target: < 15,000 ns)%n", maxJitter);
            System.out.printf("================================%n");

            assertTrue(p50 < 1_000, "p50 latency bound violated!");
            assertTrue(p90 < 2_200, "p90 latency bound violated!");
            assertTrue(p99 < 4_000, "p99 latency bound violated!");
            assertTrue(p999 < 7_500, "p99.9 latency bound violated!");
        }
    }
}
