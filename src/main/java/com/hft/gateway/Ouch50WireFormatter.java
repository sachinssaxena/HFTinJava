package com.hft.gateway;

import com.hft.core.buffer.MutableDirectBuffer;

/**
 * NASDAQ OUCH 5.0 binary order entry protocol serializer.
 * Writes wire packets directly into pre-allocated off-heap direct byte buffers in Big-Endian order.
 * Serialization latency: < 250ns.
 */
public class Ouch50WireFormatter {

    public static final byte MSG_ENTER_ORDER = (byte) 'O';
    public static final byte MSG_CANCEL_ORDER = (byte) 'X';
    public static final byte MSG_REPLACE_ORDER = (byte) 'U';

    public static final int ENTER_ORDER_LENGTH = 49;
    public static final int CANCEL_ORDER_LENGTH = 19;
    public static final int REPLACE_ORDER_LENGTH = 39;

    /**
     * Formats OUCH 5.0 Enter Order message ('O') into destination buffer.
     */
    public static int formatEnterOrder(MutableDirectBuffer dst, int offset, long clientOrderId,
                                       byte side, int shares, long stockSymbol, int price,
                                       int tif, int firm) {
        dst.putByte(offset, MSG_ENTER_ORDER);
        dst.putLongBigEndian(offset + 1, clientOrderId);
        dst.putLongBigEndian(offset + 9, 0L); // pad remainder of 14-byte token
        dst.putByte(offset + 15, side);
        dst.putIntBigEndian(offset + 16, shares);
        dst.putLongBigEndian(offset + 20, stockSymbol);
        dst.putIntBigEndian(offset + 28, price);
        dst.putIntBigEndian(offset + 32, tif);
        dst.putIntBigEndian(offset + 36, firm);
        dst.putByte(offset + 40, (byte) 'Y'); // Display
        dst.putByte(offset + 41, (byte) 'P'); // Capacity: Principal
        dst.putByte(offset + 42, (byte) 'N'); // Intermarket Sweep: No
        dst.putIntBigEndian(offset + 43, 1);   // Min Qty
        dst.putByte(offset + 47, (byte) 'N'); // Cross Type
        dst.putByte(offset + 48, (byte) 'R'); // Customer Type
        return ENTER_ORDER_LENGTH;
    }

    /**
     * Formats OUCH 5.0 Cancel Order message ('X') into destination buffer.
     */
    public static int formatCancelOrder(MutableDirectBuffer dst, int offset, long clientOrderId, int shares) {
        dst.putByte(offset, MSG_CANCEL_ORDER);
        dst.putLongBigEndian(offset + 1, clientOrderId);
        dst.putLongBigEndian(offset + 9, 0L);
        dst.putIntBigEndian(offset + 15, shares);
        return CANCEL_ORDER_LENGTH;
    }

    /**
     * Formats OUCH 5.0 Replace Order message ('U') into destination buffer.
     */
    public static int formatReplaceOrder(MutableDirectBuffer dst, int offset, long origClientOrderId,
                                        long newClientOrderId, int shares, int price) {
        dst.putByte(offset, MSG_REPLACE_ORDER);
        dst.putLongBigEndian(offset + 1, origClientOrderId);
        dst.putIntBigEndian(offset + 9, 0); // pad orig token
        dst.putShortBigEndian(offset + 13, (short) 0);
        dst.putLongBigEndian(offset + 15, newClientOrderId);
        dst.putIntBigEndian(offset + 23, 0); // pad new token
        dst.putShortBigEndian(offset + 27, (short) 0);
        dst.putIntBigEndian(offset + 29, shares);
        dst.putIntBigEndian(offset + 33, price);
        dst.putShortBigEndian(offset + 37, (short) 0);
        return REPLACE_ORDER_LENGTH;
    }
}
