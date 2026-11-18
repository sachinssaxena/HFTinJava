package com.hft.orderbook;

import com.hft.core.collections.Long2IntHashMap;
import com.hft.core.model.ModelConstants;
import com.hft.core.model.Side;

import java.util.Arrays;

/**
 * Ultra-low latency contiguous memory Level 3 (MBO) and Level 2 (MBP) Order Book.
 * 
 * Invariants:
 * 1. Zero heap allocation during steady-state updates.
 * 2. Pre-allocated flat pool for L3 orders with O(1) insertion/deletion.
 * 3. Contiguous top-20 L2 depth ladder maintained on each operation.
 * 4. Microstructure signals: OBI and Micro-price computed in pure fixed-point arithmetic.
 * 5. Dynamic queue position estimation for active firm quotes.
 */
public class OrderBookL3 {

    public static final int MAX_DEPTH = 20;
    public static final int NULL_INDEX = -1;

    // --- Pre-allocated L3 Order Pool ---
    private final int poolCapacity;
    private final long[] poolOrderId;
    private final long[] poolPrice;
    private final int[] poolShares;
    private final byte[] poolSide;
    private final int[] poolPrev;
    private final int[] poolNext;

    // Free list stack for pool reuse
    private final int[] freeStack;
    private int freeTop;

    // Index lookup: orderId -> pool index
    private final Long2IntHashMap orderIndexMap;

    // Head and Tail pointers for each price level (using a price-to-head primitive map or sparse table)
    // For fast level traversal, we maintain head/tail of price levels:
    private final Long2IntHashMap priceHeadMap;
    private final Long2IntHashMap priceTailMap;

    // --- L2 Depth Ladder (Top 20) ---
    private final long[] bidPrices = new long[MAX_DEPTH];
    private final long[] bidVolumes = new long[MAX_DEPTH];
    private final int[] bidOrderCounts = new int[MAX_DEPTH];
    private int bidDepthCount = 0;

    private final long[] askPrices = new long[MAX_DEPTH];
    private final long[] askVolumes = new long[MAX_DEPTH];
    private final int[] askOrderCounts = new int[MAX_DEPTH];
    private int askDepthCount = 0;

    // Cache of current top of book
    private long bestBidPrice = 0L;
    private long bestBidQty = 0L;
    private long bestAskPrice = 0L;
    private long bestAskQty = 0L;

    public OrderBookL3(int poolCapacity) {
        this.poolCapacity = poolCapacity;
        this.poolOrderId = new long[poolCapacity];
        this.poolPrice = new long[poolCapacity];
        this.poolShares = new int[poolCapacity];
        this.poolSide = new byte[poolCapacity];
        this.poolPrev = new int[poolCapacity];
        this.poolNext = new int[poolCapacity];

        this.freeStack = new int[poolCapacity];
        this.freeTop = poolCapacity;
        for (int i = 0; i < poolCapacity; i++) {
            this.freeStack[i] = poolCapacity - 1 - i;
        }

        this.orderIndexMap = new Long2IntHashMap(poolCapacity, 0.65f, NULL_INDEX);
        this.priceHeadMap = new Long2IntHashMap(poolCapacity / 4, 0.65f, NULL_INDEX);
        this.priceTailMap = new Long2IntHashMap(poolCapacity / 4, 0.65f, NULL_INDEX);

        clear();
    }

    public void clear() {
        Arrays.fill(poolOrderId, 0L);
        Arrays.fill(poolPrice, 0L);
        Arrays.fill(poolShares, 0);
        Arrays.fill(poolSide, (byte) 0);
        Arrays.fill(poolPrev, NULL_INDEX);
        Arrays.fill(poolNext, NULL_INDEX);

        freeTop = poolCapacity;
        for (int i = 0; i < poolCapacity; i++) {
            freeStack[i] = poolCapacity - 1 - i;
        }

        orderIndexMap.clear();
        priceHeadMap.clear();
        priceTailMap.clear();

        Arrays.fill(bidPrices, 0L);
        Arrays.fill(bidVolumes, 0L);
        Arrays.fill(bidOrderCounts, 0);
        bidDepthCount = 0;

        Arrays.fill(askPrices, 0L);
        Arrays.fill(askVolumes, 0L);
        Arrays.fill(askOrderCounts, 0);
        askDepthCount = 0;

        bestBidPrice = 0L;
        bestBidQty = 0L;
        bestAskPrice = 0L;
        bestAskQty = 0L;
    }

    public boolean addOrder(long orderId, byte side, int shares, long price) {
        if (freeTop <= 0) {
            return false; // Pool exhausted
        }

        int nodeIdx = freeStack[--freeTop];
        poolOrderId[nodeIdx] = orderId;
        poolSide[nodeIdx] = side;
        poolShares[nodeIdx] = shares;
        poolPrice[nodeIdx] = price;
        poolNext[nodeIdx] = NULL_INDEX;

        orderIndexMap.put(orderId, nodeIdx);

        // Append to price level FIFO queue
        int tailIdx = priceTailMap.get(price);
        if (tailIdx == NULL_INDEX) {
            priceHeadMap.put(price, nodeIdx);
            priceTailMap.put(price, nodeIdx);
            poolPrev[nodeIdx] = NULL_INDEX;
        } else {
            poolNext[tailIdx] = nodeIdx;
            poolPrev[nodeIdx] = tailIdx;
            priceTailMap.put(price, nodeIdx);
        }

        // Update L2 ladder
        updateL2LadderAdd(side, price, shares);
        return true;
    }

    public boolean executeOrder(long orderId, int executedShares) {
        int nodeIdx = orderIndexMap.get(orderId);
        if (nodeIdx == NULL_INDEX) {
            return false;
        }

        int currentShares = poolShares[nodeIdx];
        byte side = poolSide[nodeIdx];
        long price = poolPrice[nodeIdx];

        if (executedShares >= currentShares) {
            removeOrderFromPool(nodeIdx, orderId, price, side, currentShares);
        } else {
            poolShares[nodeIdx] = currentShares - executedShares;
            updateL2LadderReduce(side, price, executedShares, false);
        }
        return true;
    }

    public boolean cancelOrder(long orderId, int canceledShares) {
        int nodeIdx = orderIndexMap.get(orderId);
        if (nodeIdx == NULL_INDEX) {
            return false;
        }

        int currentShares = poolShares[nodeIdx];
        byte side = poolSide[nodeIdx];
        long price = poolPrice[nodeIdx];

        if (canceledShares >= currentShares) {
            removeOrderFromPool(nodeIdx, orderId, price, side, currentShares);
        } else {
            poolShares[nodeIdx] = currentShares - canceledShares;
            updateL2LadderReduce(side, price, canceledShares, false);
        }
        return true;
    }

    public boolean deleteOrder(long orderId) {
        int nodeIdx = orderIndexMap.get(orderId);
        if (nodeIdx == NULL_INDEX) {
            return false;
        }

        int shares = poolShares[nodeIdx];
        byte side = poolSide[nodeIdx];
        long price = poolPrice[nodeIdx];

        removeOrderFromPool(nodeIdx, orderId, price, side, shares);
        return true;
    }

    public boolean replaceOrder(long origOrderId, long newOrderId, int newShares, long newPrice) {
        int nodeIdx = orderIndexMap.get(origOrderId);
        if (nodeIdx == NULL_INDEX) {
            return false;
        }
        byte side = poolSide[nodeIdx];
        deleteOrder(origOrderId);
        return addOrder(newOrderId, side, newShares, newPrice);
    }

    public boolean replaceOrder(long origOrderId, long newOrderId, byte side, int newShares, long newPrice) {
        deleteOrder(origOrderId);
        return addOrder(newOrderId, side, newShares, newPrice);
    }

    private void removeOrderFromPool(int nodeIdx, long orderId, long price, byte side, int shares) {
        int prev = poolPrev[nodeIdx];
        int next = poolNext[nodeIdx];

        if (prev != NULL_INDEX) {
            poolNext[prev] = next;
        } else {
            if (next != NULL_INDEX) {
                priceHeadMap.put(price, next);
            } else {
                priceHeadMap.remove(price);
            }
        }

        if (next != NULL_INDEX) {
            poolPrev[next] = prev;
        } else {
            if (prev != NULL_INDEX) {
                priceTailMap.put(price, prev);
            } else {
                priceTailMap.remove(price);
            }
        }

        orderIndexMap.remove(orderId);
        freeStack[freeTop++] = nodeIdx;

        updateL2LadderReduce(side, price, shares, true);
    }

    private void updateL2LadderAdd(byte side, long price, int shares) {
        if (side == Side.BUY) {
            int idx = -1;
            for (int i = 0; i < bidDepthCount; i++) {
                if (bidPrices[i] == price) {
                    bidVolumes[i] += shares;
                    bidOrderCounts[i]++;
                    updateTop();
                    return;
                }
                if (price > bidPrices[i]) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1 && bidDepthCount < MAX_DEPTH) {
                idx = bidDepthCount;
            }

            if (idx != -1) {
                insertLevel(bidPrices, bidVolumes, bidOrderCounts, bidDepthCount, idx, price, shares);
                if (bidDepthCount < MAX_DEPTH) bidDepthCount++;
            }
        } else if (side == Side.SELL) {
            int idx = -1;
            for (int i = 0; i < askDepthCount; i++) {
                if (askPrices[i] == price) {
                    askVolumes[i] += shares;
                    askOrderCounts[i]++;
                    updateTop();
                    return;
                }
                if (price < askPrices[i]) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1 && askDepthCount < MAX_DEPTH) {
                idx = askDepthCount;
            }

            if (idx != -1) {
                insertLevel(askPrices, askVolumes, askOrderCounts, askDepthCount, idx, price, shares);
                if (askDepthCount < MAX_DEPTH) askDepthCount++;
            }
        }
        updateTop();
    }

    private void updateL2LadderReduce(byte side, long price, int shares, boolean decrementCount) {
        if (side == Side.BUY) {
            for (int i = 0; i < bidDepthCount; i++) {
                if (bidPrices[i] == price) {
                    bidVolumes[i] -= shares;
                    if (decrementCount) bidOrderCounts[i]--;
                    if (bidVolumes[i] <= 0 || (decrementCount && bidOrderCounts[i] <= 0)) {
                        deleteLevel(bidPrices, bidVolumes, bidOrderCounts, bidDepthCount, i);
                        bidDepthCount--;
                    }
                    break;
                }
            }
        } else if (side == Side.SELL) {
            for (int i = 0; i < askDepthCount; i++) {
                if (askPrices[i] == price) {
                    askVolumes[i] -= shares;
                    if (decrementCount) askOrderCounts[i]--;
                    if (askVolumes[i] <= 0 || (decrementCount && askOrderCounts[i] <= 0)) {
                        deleteLevel(askPrices, askVolumes, askOrderCounts, askDepthCount, i);
                        askDepthCount--;
                    }
                    break;
                }
            }
        }
        updateTop();
    }

    private static void insertLevel(long[] prices, long[] vols, int[] counts, int depth, int atIdx, long px, int vol) {
        int limit = Math.min(depth, MAX_DEPTH - 1);
        for (int i = limit; i > atIdx; i--) {
            prices[i] = prices[i - 1];
            vols[i] = vols[i - 1];
            counts[i] = counts[i - 1];
        }
        prices[atIdx] = px;
        vols[atIdx] = vol;
        counts[atIdx] = 1;
    }

    private static void deleteLevel(long[] prices, long[] vols, int[] counts, int depth, int atIdx) {
        for (int i = atIdx; i < depth - 1; i++) {
            prices[i] = prices[i + 1];
            vols[i] = vols[i + 1];
            counts[i] = counts[i + 1];
        }
        int last = depth - 1;
        prices[last] = 0L;
        vols[last] = 0L;
        counts[last] = 0;
    }

    private void updateTop() {
        if (bidDepthCount > 0) {
            bestBidPrice = bidPrices[0];
            bestBidQty = bidVolumes[0];
        } else {
            bestBidPrice = 0L;
            bestBidQty = 0L;
        }

        if (askDepthCount > 0) {
            bestAskPrice = askPrices[0];
            bestAskQty = askVolumes[0];
        } else {
            bestAskPrice = 0L;
            bestAskQty = 0L;
        }
    }

    /**
     * Computes cumulative volume ahead of this specific order in the queue priority chain.
     */
    public long getVolumeAhead(long orderId) {
        int nodeIdx = orderIndexMap.get(orderId);
        if (nodeIdx == NULL_INDEX) {
            return -1L;
        }

        long volumeAhead = 0L;
        int curr = poolPrev[nodeIdx];
        while (curr != NULL_INDEX) {
            volumeAhead += poolShares[curr];
            curr = poolPrev[curr];
        }
        return volumeAhead;
    }

    /**
     * Checks if the order book is in an inverted or crossed state (Bid >= Ask).
     */
    public boolean isCrossed() {
        return bestBidPrice > 0 && bestAskPrice > 0 && bestBidPrice >= bestAskPrice;
    }

    /**
     * Mid-price in fixed-point 64-bit long.
     */
    public long getMidPrice() {
        if (bestBidPrice == 0 || bestAskPrice == 0) return 0L;
        return (bestBidPrice + bestAskPrice) >>> 1;
    }

    /**
     * Order Book Imbalance (OBI) scaled by 10,000 (-10,000 to +10,000).
     */
    public long getOrderBookImbalance() {
        long totalDepth = bestBidQty + bestAskQty;
        if (totalDepth == 0) return 0L;
        return ((bestBidQty - bestAskQty) * ModelConstants.RATIO_SCALE) / totalDepth;
    }

    /**
     * Volume-Weighted Micro-Price in fixed-point 64-bit long.
     */
    public long getMicroPrice() {
        long totalDepth = bestBidQty + bestAskQty;
        if (totalDepth == 0) return getMidPrice();
        return ((bestBidQty * bestAskPrice) + (bestAskQty * bestBidPrice)) / totalDepth;
    }

    public long getBestBidPrice() {
        return bestBidPrice;
    }

    public long getBestBidQty() {
        return bestBidQty;
    }

    public long getBestAskPrice() {
        return bestAskPrice;
    }

    public long getBestAskQty() {
        return bestAskQty;
    }

    public int getBidDepthCount() {
        return bidDepthCount;
    }

    public int getAskDepthCount() {
        return askDepthCount;
    }

    public long getBidPriceAt(int level) {
        return bidPrices[level];
    }

    public long getBidVolumeAt(int level) {
        return bidVolumes[level];
    }

    public long getAskPriceAt(int level) {
        return askPrices[level];
    }

    public long getAskVolumeAt(int level) {
        return askVolumes[level];
    }
}
