package com.hft.core.model;

/**
 * Primitive byte representation of order side.
 */
public final class Side {
    private Side() {}

    public static final byte UNKNOWN = 0;
    public static final byte BUY = 1;
    public static final byte SELL = 2;

    public static byte fromChar(char c) {
        if (c == 'B' || c == 'b') return BUY;
        if (c == 'S' || c == 's') return SELL;
        return UNKNOWN;
    }

    public static char toChar(byte side) {
        if (side == BUY) return 'B';
        if (side == SELL) return 'S';
        return '?';
    }
}
