package com.hft.core.model;

/**
 * 64-bit primitive bitmask reject codes for SEC Rule 15c3-5 Pre-Trade Risk Gate.
 * Zero object instantiation when returning risk validation results.
 */
public final class RiskRejectCode {
    private RiskRejectCode() {}

    public static final long SUCCESS = 0L;
    public static final long REJECT_KILL_SWITCH = 1L << 0;
    public static final long REJECT_PRICE_COLLAR = 1L << 1;
    public static final long REJECT_MAX_CLIP_SIZE = 1L << 2;
    public static final long REJECT_MAX_NOTIONAL = 1L << 3;
    public static final long REJECT_MAX_INVENTORY = 1L << 4;
    public static final long REJECT_SHORT_LOCATE = 1L << 5;
    public static final long REJECT_RATE_LIMIT = 1L << 6;
    public static final long REJECT_SELF_CROSS = 1L << 7;

    public static boolean isApproved(long rejectMask) {
        return rejectMask == SUCCESS;
    }

    public static String toString(long rejectMask) {
        if (rejectMask == SUCCESS) return "SUCCESS";
        StringBuilder sb = new StringBuilder();
        if ((rejectMask & REJECT_KILL_SWITCH) != 0) sb.append("KILL_SWITCH|");
        if ((rejectMask & REJECT_PRICE_COLLAR) != 0) sb.append("PRICE_COLLAR|");
        if ((rejectMask & REJECT_MAX_CLIP_SIZE) != 0) sb.append("MAX_CLIP_SIZE|");
        if ((rejectMask & REJECT_MAX_NOTIONAL) != 0) sb.append("MAX_NOTIONAL|");
        if ((rejectMask & REJECT_MAX_INVENTORY) != 0) sb.append("MAX_INVENTORY|");
        if ((rejectMask & REJECT_SHORT_LOCATE) != 0) sb.append("SHORT_LOCATE|");
        if ((rejectMask & REJECT_RATE_LIMIT) != 0) sb.append("RATE_LIMIT|");
        if ((rejectMask & REJECT_SELF_CROSS) != 0) sb.append("SELF_CROSS|");
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
