package com.hft.gateway;

import com.hft.core.buffer.MutableDirectBuffer;
import com.hft.core.buffer.UnsafeBuffer;
import com.hft.core.collections.Long2ObjectHashMap;
import com.hft.core.model.OrderStatus;

import java.nio.ByteBuffer;

/**
 * High-performance Execution Gateway for NASDAQ OUCH 5.0 and order state management.
 * 
 * Features:
 * 1. Pre-allocated order pool and Long2ObjectHashMap for zero-allocation state tracking.
 * 2. Zero-copy binary wire formatting.
 * 3. MiFID II RTS 25 T4 timestamp recording.
 */
public class ExecutionGateway {

    public interface WireSender {
        void sendWirePacket(MutableDirectBuffer buffer, int offset, int length);
    }

    private final WireSender wireSender;
    private final MutableDirectBuffer outboundBuffer;

    private final OrderState[] statePool;
    private int poolTop;
    private final Long2ObjectHashMap<OrderState> activeOrders;

    private long totalOrdersSubmitted = 0L;
    private long totalCancelsSubmitted = 0L;

    public ExecutionGateway(int poolCapacity, WireSender wireSender) {
        this.wireSender = wireSender;
        ByteBuffer direct = ByteBuffer.allocateDirect(1024);
        this.outboundBuffer = new UnsafeBuffer(direct);

        this.statePool = new OrderState[poolCapacity];
        this.poolTop = poolCapacity;
        for (int i = 0; i < poolCapacity; i++) {
            this.statePool[i] = new OrderState();
        }
        this.activeOrders = new Long2ObjectHashMap<>(poolCapacity, 0.65f);
    }

    /**
     * Submits a new order outbound to the exchange venue.
     */
    public boolean submitNewOrder(long clientOrderId, byte side, int shares, long stockSymbol,
                                  int price, int tif, int firm,
                                  long t1_rx, long t2_book, long t3_risk) {
        if (poolTop <= 0) {
            return false; // Order pool exhausted
        }

        OrderState state = statePool[--poolTop];
        state.reset();
        state.clientOrderId = clientOrderId;
        state.side = side;
        state.shares = shares;
        state.leavesShares = shares;
        state.leavesQty = shares;
        state.price = price;
        state.orderStatus = OrderStatus.PENDING_NEW;
        state.status = OrderStatus.PENDING_NEW;
        state.t1_rxNanos = t1_rx;
        state.t2_bookNanos = t2_book;
        state.t3_riskDecisionNanos = t3_risk;

        // Serialize OUCH EnterOrder packet
        int len = Ouch50WireFormatter.formatEnterOrder(outboundBuffer, 0, clientOrderId,
                side, shares, stockSymbol, price, tif, firm);

        // Capture MiFID II RTS 25 T4 timestamp
        long t4_tx = System.nanoTime();
        state.t4_wireTxNanos = t4_tx;

        activeOrders.put(clientOrderId, state);
        totalOrdersSubmitted++;

        if (wireSender != null) {
            wireSender.sendWirePacket(outboundBuffer, 0, len);
        }
        return true;
    }

    /**
     * Submits an order cancellation request.
     */
    public boolean submitCancelOrder(long clientOrderId, int shares) {
        OrderState state = activeOrders.get(clientOrderId);
        if (state == null) {
            return false;
        }

        state.orderStatus = OrderStatus.PENDING_CANCEL;
        int len = Ouch50WireFormatter.formatCancelOrder(outboundBuffer, 0, clientOrderId, shares);
        totalCancelsSubmitted++;

        if (wireSender != null) {
            wireSender.sendWirePacket(outboundBuffer, 0, len);
        }
        return true;
    }

    public void onOrderAcknowledged(long clientOrderId) {
        OrderState state = activeOrders.get(clientOrderId);
        if (state != null) {
            state.orderStatus = OrderStatus.NEW;
            state.status = OrderStatus.NEW;
        }
    }

    public void onOrderAccepted(long clientOrderId, long timestampNanos) {
        onOrderAcknowledged(clientOrderId);
    }

    public void onOrderFilled(long clientOrderId, int filledShares) {
        OrderState state = activeOrders.get(clientOrderId);
        if (state != null) {
            state.executedShares += filledShares;
            state.cumQty = state.executedShares;
            state.leavesShares -= filledShares;
            state.leavesQty = state.leavesShares;
            if (state.leavesShares <= 0) {
                state.orderStatus = OrderStatus.FILLED;
                state.status = OrderStatus.FILLED;
                activeOrders.remove(clientOrderId);
                statePool[poolTop++] = state;
            } else {
                state.orderStatus = OrderStatus.PARTIALLY_FILLED;
                state.status = OrderStatus.PARTIALLY_FILLED;
            }
        }
    }

    public void onOrderExecuted(long clientOrderId, int executedShares, long executionPrice) {
        onOrderFilled(clientOrderId, executedShares);
    }

    public void onOrderCanceled(long clientOrderId) {
        OrderState state = activeOrders.remove(clientOrderId);
        if (state != null) {
            state.orderStatus = OrderStatus.CANCELED;
            state.reset();
            statePool[poolTop++] = state;
        }
    }

    public OrderState getOrderState(long clientOrderId) {
        return activeOrders.get(clientOrderId);
    }

    public long getTotalOrdersSubmitted() {
        return totalOrdersSubmitted;
    }

    public long getTotalCancelsSubmitted() {
        return totalCancelsSubmitted;
    }
}
