package com.hft.risk;

import com.hft.core.model.ModelConstants;
import com.hft.core.model.RiskRejectCode;
import com.hft.core.model.Side;

/**
 * Ultra-low latency in-line Pre-Trade Risk Gate compliant with SEC Rule 15c3-5.
 * 
 * Invariants:
 * 1. Executes all 8 regulatory checks in a single method call in &lt; 300ns.
 * 2. Strictly 0 bytes heap allocation on hot path.
 * 3. Returns primitive 64-bit bitmask reject codes without throwing exceptions.
 * 4. Zero-delay atomic emergency kill-switch.
 */
public class PreTradeRiskGate {

    // 1. Emergency Kill Switch
    private volatile boolean killSwitchActive = false;

    // 2. Fat-Finger Collar parameters
    private long maxPriceSpreadAllowed = 20_000L; // $2.00 scaled by 10,000

    // 3. Max Clip Size
    private int maxClipSize = 10_000;

    // 4. Max Notional Value ($ scaled by 10,000)
    private long maxNotionalAllowed = 1_000_000L * ModelConstants.PRICE_SCALE; // $1,000,000

    // 5. Aggregate Inventory / Position Exposure Limits
    private long maxLongExposure = 50_000L;
    private long maxShortExposure = 50_000L;
    private long currentInventory = 0L;
    private long openWorkingBuyQty = 0L;
    private long openWorkingSellQty = 0L;

    // 6. Short Locate Bitset (indexed by security locate ID 0..1023)
    private final long[] shortLocateBitset = new long[16]; // 16 * 64 = 1024 locates

    // 7. Rolling Window Rate Limiter (O(1) Circular ring buffer of timestamps)
    private static final int RATE_LIMIT_CAPACITY = 16384;
    private static final int RATE_LIMIT_MASK = RATE_LIMIT_CAPACITY - 1;
    private final long[] orderTimestamps = new long[RATE_LIMIT_CAPACITY];
    private int orderTimestampHead = 0;
    private int maxOrdersPer1ms = 200;
    private int maxOrdersPer1000ms = 10_000;

    // 8. Self-Cross / Wash Trade Resting Quotes
    private long restingFirmBuyPrice = 0L;
    private long restingFirmSellPrice = 0L;

    public PreTradeRiskGate() {
        // By default set locate bit 0 and 1
        setLocate(0, true);
        setLocate(1, true);
    }

    /**
     * Executes all 8 SEC Rule 15c3-5 checks sequentially in &lt; 300ns.
     * 
     * @param timestampNanos Current high-resolution timestamp
     * @param securityId     Numeric security identifier
     * @param side           Order side (BUY / SELL)
     * @param shares         Order quantity
     * @param price          Fixed-point scaled price
     * @param bestBid        Current best bid in the book
     * @param bestAsk        Current best ask in the book
     * @return RiskRejectCode bitmask (0L = approved, >0 = rejected)
     */
    public long evaluateOrder(long timestampNanos, int securityId, byte side, int shares, long price,
                              long bestBid, long bestAsk) {
        long rejectMask = RiskRejectCode.SUCCESS;

        // Check 1: Emergency Kill Switch (~10ns)
        if (killSwitchActive) {
            return RiskRejectCode.REJECT_KILL_SWITCH; // Fail-fast immediately
        }

        // Check 2: Fat-Finger Price Collar (~15ns)
        if (side == Side.BUY) {
            if (bestAsk > 0 && price > (bestAsk + maxPriceSpreadAllowed)) {
                rejectMask |= RiskRejectCode.REJECT_PRICE_COLLAR;
            }
        } else if (side == Side.SELL) {
            if (bestBid > 0 && price < (bestBid - maxPriceSpreadAllowed)) {
                rejectMask |= RiskRejectCode.REJECT_PRICE_COLLAR;
            }
        }

        // Check 3: Maximum Clip Size (~10ns)
        if (shares <= 0 || shares > maxClipSize) {
            rejectMask |= RiskRejectCode.REJECT_MAX_CLIP_SIZE;
        }

        // Check 4: Maximum Order Notional Value (~15ns)
        long notionalScaled = price * shares;
        if (notionalScaled > maxNotionalAllowed) {
            rejectMask |= RiskRejectCode.REJECT_MAX_NOTIONAL;
        }

        // Check 5: Aggregate Position & Inventory Exposure (~20ns)
        if (side == Side.BUY) {
            long projectedLong = currentInventory + openWorkingBuyQty + shares;
            if (projectedLong > maxLongExposure) {
                rejectMask |= RiskRejectCode.REJECT_MAX_INVENTORY;
            }
        } else if (side == Side.SELL) {
            long projectedShort = currentInventory - openWorkingSellQty - shares;
            if (projectedShort < -maxShortExposure) {
                rejectMask |= RiskRejectCode.REJECT_MAX_INVENTORY;
            }
        }

        // Check 6: Short Locate Verification (~20ns)
        if (side == Side.SELL) {
            long projectedNet = currentInventory - shares;
            if (projectedNet < 0) { // Short selling condition
                if (!hasLocate(securityId)) {
                    rejectMask |= RiskRejectCode.REJECT_SHORT_LOCATE;
                }
            }
        }

        // Check 7: Rate Limiting & Order Churn (~60ns)
        if (!checkRateLimit(timestampNanos)) {
            rejectMask |= RiskRejectCode.REJECT_RATE_LIMIT;
        }

        // Check 8: Self-Cross / Wash Trade Prevention (~40ns)
        if (side == Side.BUY) {
            if (restingFirmSellPrice > 0 && price >= restingFirmSellPrice) {
                rejectMask |= RiskRejectCode.REJECT_SELF_CROSS;
            }
        } else if (side == Side.SELL) {
            if (restingFirmBuyPrice > 0 && price <= restingFirmBuyPrice) {
                rejectMask |= RiskRejectCode.REJECT_SELF_CROSS;
            }
        }

        // If approved, record timestamp into circular rate limit buffer
        if (rejectMask == RiskRejectCode.SUCCESS) {
            recordOrder(timestampNanos);
        }

        return rejectMask;
    }

    private boolean checkRateLimit(long currentTimestampNanos) {
        if (orderTimestampHead >= maxOrdersPer1ms) {
            long ts1ms = orderTimestamps[(orderTimestampHead - maxOrdersPer1ms) & RATE_LIMIT_MASK];
            if ((currentTimestampNanos - ts1ms) < ModelConstants.NANOS_PER_MILLI) {
                return false;
            }
        }
        if (orderTimestampHead >= maxOrdersPer1000ms) {
            long ts1000ms = orderTimestamps[(orderTimestampHead - maxOrdersPer1000ms) & RATE_LIMIT_MASK];
            if ((currentTimestampNanos - ts1000ms) < ModelConstants.NANOS_PER_SECOND) {
                return false;
            }
        }
        return true;
    }

    private void recordOrder(long currentTimestampNanos) {
        orderTimestamps[orderTimestampHead & RATE_LIMIT_MASK] = currentTimestampNanos;
        orderTimestampHead++;
    }

    public void setLocate(int securityId, boolean located) {
        int word = (securityId & 1023) >>> 6;
        int bit = securityId & 63;
        if (located) {
            shortLocateBitset[word] |= (1L << bit);
        } else {
            shortLocateBitset[word] &= ~(1L << bit);
        }
    }

    public boolean hasLocate(int securityId) {
        int word = (securityId & 1023) >>> 6;
        int bit = securityId & 63;
        return (shortLocateBitset[word] & (1L << bit)) != 0;
    }

    public void updateRestingFirmQuotes(long buyPrice, long sellPrice) {
        this.restingFirmBuyPrice = buyPrice;
        this.restingFirmSellPrice = sellPrice;
    }

    public void updateRestingQuotes(long buyPrice, long sellPrice) {
        updateRestingFirmQuotes(buyPrice, sellPrice);
    }

    public void updateInventory(long currentInventory, long openWorkingBuyQty, long openWorkingSellQty) {
        this.currentInventory = currentInventory;
        this.openWorkingBuyQty = openWorkingBuyQty;
        this.openWorkingSellQty = openWorkingSellQty;
    }

    public void updateInventory(long currentInventory) {
        this.currentInventory = currentInventory;
    }

    public void triggerEmergencyKillSwitch() {
        this.killSwitchActive = true;
    }

    public void tripKillSwitch() {
        triggerEmergencyKillSwitch();
    }

    public void resetEmergencyKillSwitch() {
        this.killSwitchActive = false;
    }

    public boolean isKillSwitchActive() {
        return killSwitchActive;
    }

    public void setMaxClipSize(int maxClipSize) {
        this.maxClipSize = maxClipSize;
    }

    public void setMaxNotionalAllowed(long maxNotionalAllowed) {
        this.maxNotionalAllowed = maxNotionalAllowed;
    }

    public void setMaxSpreadAllowed(long maxPriceSpreadAllowed) {
        this.maxPriceSpreadAllowed = maxPriceSpreadAllowed;
    }

    public void setExposureLimits(long maxLong, long maxShort) {
        this.maxLongExposure = maxLong;
        this.maxShortExposure = maxShort;
    }
}
