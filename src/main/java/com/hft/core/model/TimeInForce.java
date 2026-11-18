package com.hft.core.model;

/**
 * Primitive byte representation of Time In Force.
 */
public final class TimeInForce {
    private TimeInForce() {}

    public static final byte DAY = 1;
    public static final byte IOC = 2;
    public static final byte GTC = 3;
    public static final byte FOK = 4;
}
