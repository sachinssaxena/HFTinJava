package com.hft.core.model;

/**
 * Global primitive scaling constants and fixed-point arithmetic utilities.
 * Completely eliminates floating-point representation and BigDecimal latency on hot paths.
 */
public final class ModelConstants {

    private ModelConstants() {}

    /** Equities 4-decimal fixed point scaling factor ($150.2500 -> 1,502,500L) */
    public static final long PRICE_SCALE = 10_000L;

    /** CME MDP 3.0 7-decimal fixed point scaling factor */
    public static final long CME_PRICE_SCALE = 10_000_000L;

    /** Ratio scaling factor for Order Book Imbalance (-10,000 to +10,000 representing -1.0 to +1.0) */
    public static final long RATIO_SCALE = 10_000L;

    /** Nanoseconds per second */
    public static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Nanoseconds per millisecond */
    public static final long NANOS_PER_MILLI = 1_000_000L;

    public static long toFixedPoint(double value, long scale) {
        return Math.round(value * scale);
    }

    public static double toDouble(long fixedPointValue, long scale) {
        return (double) fixedPointValue / scale;
    }

    public static long multiplyFixed(long a, long b, long scale) {
        return (a * b) / scale;
    }

    public static long divideFixed(long a, long b, long scale) {
        return (a * scale) / b;
    }
}
