package com.hft.test;

import com.hft.audit.AuditJournalTap;
import com.hft.core.buffer.MutableDirectBuffer;
import com.hft.core.buffer.UnsafeBuffer;
import com.hft.core.model.Side;
import com.hft.gateway.ExecutionGateway;
import com.hft.marketdata.DualFeedArbitrator;
import com.hft.marketdata.itch.Itch50Flyweight;
import com.hft.orderbook.OrderBookL3;
import com.hft.pipeline.TradingEnginePipeline;
import com.hft.risk.PreTradeRiskGate;
import com.hft.strategy.AvellanedaStoikovStrategy;

import java.lang.management.ManagementFactory;

public class AllocTracer {

    public static void main(String[] args) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();

        // 1. Trace Arbitrator
        DualFeedArbitrator arb = new DualFeedArbitrator((f, s, b, o, l) -> {}, null);
        MutableDirectBuffer buf = new UnsafeBuffer(new byte[64]);
        for (int i = 0; i < 200_000; i++) arb.onPacket(DualFeedArbitrator.FEED_A, i, buf, 0, 64);
        long a1 = bean.getThreadAllocatedBytes(tid);
        for (int i = 200_000; i < 300_000; i++) arb.onPacket(DualFeedArbitrator.FEED_A, i, buf, 0, 64);
        long a2 = bean.getThreadAllocatedBytes(tid);
        System.out.println("Arbitrator alloc (200k warmup): " + (a2 - a1));

        // 2. Trace OrderBook
        OrderBookL3 book = new OrderBookL3(2048);
        for (int i = 0; i < 50_000; i++) {
            book.addOrder(1L, Side.BUY, 100, 1_500_000L);
            book.cancelOrder(1L, 100);
        }
        long b1 = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) {
            book.addOrder(1L, Side.BUY, 100, 1_500_000L);
            book.cancelOrder(1L, 100);
        }
        long b2 = bean.getThreadAllocatedBytes(tid);
        System.out.println("OrderBook alloc: " + (b2 - b1));

        // 3. Trace RiskGate
        PreTradeRiskGate risk = new PreTradeRiskGate();
        long now = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            risk.evaluateOrder(now + i, 1, Side.BUY, 100, 1_500_000L, 1_499_000L, 1_501_000L);
        }
        long r1 = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) {
            risk.evaluateOrder(now + i, 1, Side.BUY, 100, 1_500_000L, 1_499_000L, 1_501_000L);
        }
        long r2 = bean.getThreadAllocatedBytes(tid);
        System.out.println("RiskGate alloc: " + (r2 - r1));

        // 4. Trace Strategy
        AvellanedaStoikovStrategy strat = new AvellanedaStoikovStrategy();
        book.addOrder(10L, Side.BUY, 100, 1_500_000L);
        book.addOrder(20L, Side.SELL, 100, 1_502_000L);
        for (int i = 0; i < 50_000; i++) strat.evaluate(book, 0L);
        long s1 = bean.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 100_000; i++) strat.evaluate(book, 0L);
        long s2 = bean.getThreadAllocatedBytes(tid);
        System.out.println("Strategy alloc: " + (s2 - s1));

        // 5. Trace Gateway
        ExecutionGateway gw = new ExecutionGateway(2048, (b, o, l) -> {});
        for (int i = 0; i < 50_000; i++) {
            gw.submitNewOrder(i, Side.BUY, 100, 0x4141504cL, 1_500_000, 1, 1, 0, 0, 0);
            gw.onOrderFilled(i, 100);
        }
        long g1 = bean.getThreadAllocatedBytes(tid);
        for (int i = 50_000; i < 150_000; i++) {
            gw.submitNewOrder(i, Side.BUY, 100, 0x4141504cL, 1_500_000, 1, 1, 0, 0, 0);
            gw.onOrderFilled(i, 100);
        }
        long g2 = bean.getThreadAllocatedBytes(tid);
        System.out.println("Gateway alloc: " + (g2 - g1));

        // 6. Trace AuditJournal
        AuditJournalTap tap = new AuditJournalTap(2048);
        for (int i = 0; i < 50_000; i++) {
            tap.publishAuditRecord(i, i, Side.BUY, 1_500_000L, 100, 0L, 1, 2, 3, 4);
            tap.drainPending();
        }
        long j1 = bean.getThreadAllocatedBytes(tid);
        for (int i = 50_000; i < 150_000; i++) {
            tap.publishAuditRecord(i, i, Side.BUY, 1_500_000L, 100, 0L, 1, 2, 3, 4);
            tap.drainPending();
        }
        long j2 = bean.getThreadAllocatedBytes(tid);
        System.out.println("AuditJournal alloc: " + (j2 - j1));
    }
}
