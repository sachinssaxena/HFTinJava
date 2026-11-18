package com.hft.pipeline;

import com.hft.audit.AuditJournalTap;
import com.hft.core.buffer.DirectBuffer;
import com.hft.core.model.RiskRejectCode;
import com.hft.core.model.Side;
import com.hft.gateway.ExecutionGateway;
import com.hft.marketdata.DualFeedArbitrator;
import com.hft.marketdata.itch.Itch50Flyweight;
import com.hft.orderbook.OrderBookL3;
import com.hft.risk.PreTradeRiskGate;
import com.hft.strategy.AvellanedaStoikovStrategy;

/**
 * Co-Located Ultra-Low Latency Trading Engine Pipeline (Pinned Core 3).
 * 
 * Pipeline Execution:
 * 1. T1: UDP Packet arrival & A/B Arbitration.
 * 2. T2: Order Book L3/L2 update.
 * 3. T3: Strategy quote generation & SEC 15c3-5 Pre-trade Risk Gate approval.
 * 4. T4: Execution Gateway OUCH wire serialization & transmission.
 * 
 * Invariants:
 * - Steady-state heap allocation: Strictly 0 bytes.
 * - Single isolated CPU core execution (< 950ns intra-core tick-to-trade).
 * - Fully deterministic audit trail tapping to background core.
 */
public class TradingEnginePipeline implements DualFeedArbitrator.MarketDataConsumer {

    private final DualFeedArbitrator arbitrator;
    private final Itch50Flyweight itchFlyweight = new Itch50Flyweight();
    private final OrderBookL3 orderBook;
    private final AvellanedaStoikovStrategy strategy;
    private final PreTradeRiskGate riskGate;
    private final ExecutionGateway gateway;
    private final AuditJournalTap auditJournal;

    private long currentInventory = 0L;
    private long clientOrderIdSeq = 1_000_000L;
    private int securityId = 1;
    private long symbolAscii = 0x4141504c20202020L; // "AAPL    "

    // Risk policy: whether asymmetric quotes are allowed or paired quotes must both pass
    private boolean enforceDeltaNeutralQuotePair = true;

    // Metrics
    private long processedTicks = 0L;
    private long ordersGenerated = 0L;
    private long ordersRiskRejected = 0L;
    private long lastTickToTradeNanos = 0L;

    public TradingEnginePipeline(int orderPoolCapacity, int ringBufferCapacity,
                                 ExecutionGateway.WireSender wireSender,
                                 DualFeedArbitrator.GapListener gapListener) {
        this.arbitrator = new DualFeedArbitrator(this, gapListener);
        this.orderBook = new OrderBookL3(orderPoolCapacity);
        this.strategy = new AvellanedaStoikovStrategy();
        this.riskGate = new PreTradeRiskGate();
        this.gateway = new ExecutionGateway(orderPoolCapacity, wireSender);
        this.auditJournal = new AuditJournalTap(ringBufferCapacity);
    }

    public void onMarketDataPacket(byte feedId, long sequenceNumber, DirectBuffer buffer, int offset, int length) {
        arbitrator.onPacket(feedId, sequenceNumber, buffer, offset, length);
    }

    @Override
    public void onMarketDataMessage(byte feedId, long sequenceNumber, DirectBuffer buffer, int offset, int length) {
        long t1_rx = System.nanoTime();
        processedTicks++;

        // 1. Zero-copy ITCH 5.0 parsing
        itchFlyweight.wrap(buffer, offset);
        byte msgType = itchFlyweight.messageType();

        // 2. Order Book update
        switch (msgType) {
            case Itch50Flyweight.MSG_ADD_ORDER:
            case Itch50Flyweight.MSG_ADD_ORDER_MPID:
                orderBook.addOrder(itchFlyweight.orderReferenceNumber(),
                        Side.fromChar((char) itchFlyweight.buySellIndicator()),
                        itchFlyweight.shares(),
                        itchFlyweight.price());
                break;
            case Itch50Flyweight.MSG_ORDER_EXECUTED:
            case Itch50Flyweight.MSG_ORDER_EXECUTED_PRICE:
                orderBook.executeOrder(itchFlyweight.orderReferenceNumber(), itchFlyweight.executedShares());
                break;
            case Itch50Flyweight.MSG_ORDER_CANCEL:
                orderBook.cancelOrder(itchFlyweight.orderReferenceNumber(), itchFlyweight.canceledShares());
                break;
            case Itch50Flyweight.MSG_ORDER_DELETE:
                orderBook.deleteOrder(itchFlyweight.orderReferenceNumber());
                break;
            case Itch50Flyweight.MSG_ORDER_REPLACE:
                orderBook.replaceOrder(itchFlyweight.orderReferenceNumber(),
                        itchFlyweight.replacementOrderReferenceNumber(),
                        itchFlyweight.replacementShares(),
                        itchFlyweight.replacementPrice());
                break;
            default:
                break;
        }

        long t2_book = System.nanoTime();

        // If in defensive quoting due to gap or crossed book, halt active quoting
        if (arbitrator.isDefensiveQuoting() || orderBook.isCrossed()) {
            return;
        }

        // 3. Strategy decision
        AvellanedaStoikovStrategy.QuoteIntent quote = strategy.evaluate(orderBook, currentInventory);
        if (!quote.shouldQuoteBid && !quote.shouldQuoteAsk) {
            return;
        }

        // 4. Pre-Trade Risk Gate evaluation (Two-Sided Quoting)
        long bidRejectMask = RiskRejectCode.SUCCESS;
        long askRejectMask = RiskRejectCode.SUCCESS;

        if (quote.shouldQuoteBid) {
            bidRejectMask = riskGate.evaluateOrder(t2_book, securityId, Side.BUY,
                    quote.targetBidShares, quote.targetBidPrice,
                    orderBook.getBestBidPrice(), orderBook.getBestAskPrice());
        }

        if (quote.shouldQuoteAsk) {
            askRejectMask = riskGate.evaluateOrder(t2_book, securityId, Side.SELL,
                    quote.targetAskShares, quote.targetAskPrice,
                    orderBook.getBestBidPrice(), orderBook.getBestAskPrice());
        }

        // If delta-neutral pair gating is enforced and either leg fails, suppress both
        boolean bidApproved = quote.shouldQuoteBid && RiskRejectCode.isApproved(bidRejectMask);
        boolean askApproved = quote.shouldQuoteAsk && RiskRejectCode.isApproved(askRejectMask);

        if (enforceDeltaNeutralQuotePair && quote.shouldQuoteBid && quote.shouldQuoteAsk) {
            if (!bidApproved || !askApproved) {
                bidApproved = false;
                askApproved = false;
            }
        }

        long t3_risk = System.nanoTime();

        // 5. Gateway Dispatch
        if (bidApproved) {
            long clOrdId = ++clientOrderIdSeq;
            gateway.submitNewOrder(clOrdId, Side.BUY, quote.targetBidShares, symbolAscii,
                    (int) quote.targetBidPrice, 1, 100, t1_rx, t2_book, t3_risk);
            ordersGenerated++;
            long t4_wire = gateway.getOrderState(clOrdId).t4_wireTxNanos;
            lastTickToTradeNanos = t4_wire - t1_rx;

            auditJournal.publishAuditRecord(sequenceNumber, clOrdId, Side.BUY, quote.targetBidPrice,
                    quote.targetBidShares, RiskRejectCode.SUCCESS, t1_rx, t2_book, t3_risk, t4_wire);
        } else if (quote.shouldQuoteBid) {
            ordersRiskRejected++;
            auditJournal.publishAuditRecord(sequenceNumber, ++clientOrderIdSeq, Side.BUY, quote.targetBidPrice,
                    quote.targetBidShares, bidRejectMask, t1_rx, t2_book, t3_risk, t3_risk);
        }

        if (askApproved) {
            long clOrdId = ++clientOrderIdSeq;
            gateway.submitNewOrder(clOrdId, Side.SELL, quote.targetAskShares, symbolAscii,
                    (int) quote.targetAskPrice, 1, 100, t1_rx, t2_book, t3_risk);
            ordersGenerated++;
            long t4_wire = gateway.getOrderState(clOrdId).t4_wireTxNanos;
            lastTickToTradeNanos = t4_wire - t1_rx;

            auditJournal.publishAuditRecord(sequenceNumber, clOrdId, Side.SELL, quote.targetAskPrice,
                    quote.targetAskShares, RiskRejectCode.SUCCESS, t1_rx, t2_book, t3_risk, t4_wire);
        } else if (quote.shouldQuoteAsk) {
            ordersRiskRejected++;
            auditJournal.publishAuditRecord(sequenceNumber, ++clientOrderIdSeq, Side.SELL, quote.targetAskPrice,
                    quote.targetAskShares, askRejectMask, t1_rx, t2_book, t3_risk, t3_risk);
        }
    }

    public OrderBookL3 getOrderBook() {
        return orderBook;
    }

    public PreTradeRiskGate getRiskGate() {
        return riskGate;
    }

    public AvellanedaStoikovStrategy getStrategy() {
        return strategy;
    }

    public ExecutionGateway getGateway() {
        return gateway;
    }

    public DualFeedArbitrator getArbitrator() {
        return arbitrator;
    }

    public AuditJournalTap getAuditJournal() {
        return auditJournal;
    }

    public long getProcessedTicks() {
        return processedTicks;
    }

    public long getOrdersGenerated() {
        return ordersGenerated;
    }

    public long getOrdersRiskRejected() {
        return ordersRiskRejected;
    }

    public long getLastTickToTradeNanos() {
        return lastTickToTradeNanos;
    }

    public void setInventory(long currentInventory) {
        this.currentInventory = currentInventory;
    }

    public void setEnforceDeltaNeutralQuotePair(boolean enforceDeltaNeutralQuotePair) {
        this.enforceDeltaNeutralQuotePair = enforceDeltaNeutralQuotePair;
    }

    public boolean isEnforceDeltaNeutralQuotePair() {
        return enforceDeltaNeutralQuotePair;
    }
}
