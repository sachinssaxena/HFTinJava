package com.hft.gateway;

import com.hft.core.model.OrderStatus;

/**
 * Reusable pre-allocated Order State container.
 * Zero heap allocation on order updates.
 */
public class OrderState {

    public long clientOrderId;
    public byte side;
    public long price;
    public int shares;
    public int executedShares;
    public int leavesShares;
    public byte orderStatus = OrderStatus.PENDING_NEW;

    // MiFID II RTS 25 Nanosecond Checkpoint Timestamps
    public long t1_rxNanos;
    public long t2_bookNanos;
    public long t3_riskDecisionNanos;
    public long t4_wireTxNanos;

    public void reset() {
        this.clientOrderId = 0L;
        this.side = 0;
        this.price = 0L;
        this.shares = 0;
        this.executedShares = 0;
        this.leavesShares = 0;
        this.orderStatus = OrderStatus.PENDING_NEW;
        this.t1_rxNanos = 0L;
        this.t2_bookNanos = 0L;
        this.t3_riskDecisionNanos = 0L;
        this.t4_wireTxNanos = 0L;
    }
}
