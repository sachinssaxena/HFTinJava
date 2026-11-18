package com.hft.core.model;

/**
 * Primitive byte representation of Order Status lifecycle state.
 */
public final class OrderStatus {
    private OrderStatus() {}

    public static final byte PENDING_NEW = 1;
    public static final byte NEW = 2;
    public static final byte PARTIALLY_FILLED = 3;
    public static final byte FILLED = 4;
    public static final byte PENDING_CANCEL = 5;
    public static final byte CANCELED = 6;
    public static final byte REJECTED = 7;
    public static final byte PENDING_REPLACE = 8;
    public static final byte REPLACED = 9;
}
