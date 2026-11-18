package com.hft.core.model;

/**
 * Primitive byte representation of order type.
 */
public final class OrderType {
    private OrderType() {}

    public static final byte LIMIT = 1;
    public static final byte MARKET = 2;
    public static final byte STOP_LIMIT = 3;
}
