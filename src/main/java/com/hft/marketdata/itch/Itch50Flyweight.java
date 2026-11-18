package com.hft.marketdata.itch;

import com.hft.core.buffer.DirectBuffer;
import com.hft.core.buffer.MutableDirectBuffer;

/**
 * NASDAQ TotalView-ITCH 5.0 zero-copy flyweight parser.
 * Reads raw binary network packets in place without object creation or string conversions.
 */
public class Itch50Flyweight {

    // ITCH 5.0 Message Types
    public static final byte MSG_ADD_ORDER = (byte) 'A';
    public static final byte MSG_ADD_ORDER_MPID = (byte) 'F';
    public static final byte MSG_ORDER_EXECUTED = (byte) 'E';
    public static final byte MSG_ORDER_EXECUTED_PRICE = (byte) 'C';
    public static final byte MSG_ORDER_CANCEL = (byte) 'X';
    public static final byte MSG_ORDER_DELETE = (byte) 'D';
    public static final byte MSG_ORDER_REPLACE = (byte) 'U';

    private DirectBuffer buffer;
    private int offset;

    public void wrap(DirectBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
    }

    public byte messageType() {
        return buffer.getByte(offset);
    }

    public short stockLocate() {
        return buffer.getShortBigEndian(offset + 1);
    }

    public short trackingNumber() {
        return buffer.getShortBigEndian(offset + 3);
    }

    /** 48-bit (6-byte) nanosecond timestamp */
    public long timestampNanos() {
        long high = (buffer.getShortBigEndian(offset + 5) & 0xFFFFL) << 32;
        long low = buffer.getIntBigEndian(offset + 7) & 0xFFFFFFFFL;
        return high | low;
    }

    /**
     * Applicable for 'A', 'F', 'E', 'C', 'X', 'D', 'U' (Original Order Ref)
     */
    public long orderReferenceNumber() {
        return buffer.getLongBigEndian(offset + 11);
    }

    /** For 'A' and 'F' */
    public byte buySellIndicator() {
        return buffer.getByte(offset + 19);
    }

    /** For 'A' and 'F' */
    public int shares() {
        return buffer.getIntBigEndian(offset + 20);
    }

    /** For 'A' and 'F': Stock symbol encoded as 8-byte ASCII */
    public long stockSymbolAsLong() {
        return buffer.getLongBigEndian(offset + 24);
    }

    /** For 'A' and 'F': Price scaled by 10,000 (4 decimals) */
    public int price() {
        return buffer.getIntBigEndian(offset + 32);
    }

    // --- Message 'E' (Executed) ---
    public int executedShares() {
        return buffer.getIntBigEndian(offset + 19);
    }

    public long matchNumber() {
        return buffer.getLongBigEndian(offset + 23);
    }

    // --- Message 'X' (Cancel) ---
    public int canceledShares() {
        return buffer.getIntBigEndian(offset + 19);
    }

    // --- Message 'U' (Replace) ---
    public long replacementOrderReferenceNumber() {
        return buffer.getLongBigEndian(offset + 19);
    }

    public int replacementShares() {
        return buffer.getIntBigEndian(offset + 27);
    }

    public int replacementPrice() {
        return buffer.getIntBigEndian(offset + 31);
    }

    // --- Static Encoders for Synthetic Generation / Replay ---

    public static int encodeAddOrder(MutableDirectBuffer dst, int offset, short locate, short tracking,
                                    long timestampNanos, long orderRef, byte side, int shares,
                                    long stockSymbol, int price) {
        dst.putByte(offset, MSG_ADD_ORDER);
        dst.putShortBigEndian(offset + 1, locate);
        dst.putShortBigEndian(offset + 3, tracking);
        dst.putShortBigEndian(offset + 5, (short) (timestampNanos >>> 32));
        dst.putIntBigEndian(offset + 7, (int) timestampNanos);
        dst.putLongBigEndian(offset + 11, orderRef);
        dst.putByte(offset + 19, side);
        dst.putIntBigEndian(offset + 20, shares);
        dst.putLongBigEndian(offset + 24, stockSymbol);
        dst.putIntBigEndian(offset + 32, price);
        return 36;
    }

    public static int encodeOrderExecuted(MutableDirectBuffer dst, int offset, short locate, short tracking,
                                         long timestampNanos, long orderRef, int executedShares, long matchNumber) {
        dst.putByte(offset, MSG_ORDER_EXECUTED);
        dst.putShortBigEndian(offset + 1, locate);
        dst.putShortBigEndian(offset + 3, tracking);
        dst.putShortBigEndian(offset + 5, (short) (timestampNanos >>> 32));
        dst.putIntBigEndian(offset + 7, (int) timestampNanos);
        dst.putLongBigEndian(offset + 11, orderRef);
        dst.putIntBigEndian(offset + 19, executedShares);
        dst.putLongBigEndian(offset + 23, matchNumber);
        return 31;
    }

    public static int encodeOrderCancel(MutableDirectBuffer dst, int offset, short locate, short tracking,
                                       long timestampNanos, long orderRef, int canceledShares) {
        dst.putByte(offset, MSG_ORDER_CANCEL);
        dst.putShortBigEndian(offset + 1, locate);
        dst.putShortBigEndian(offset + 3, tracking);
        dst.putShortBigEndian(offset + 5, (short) (timestampNanos >>> 32));
        dst.putIntBigEndian(offset + 7, (int) timestampNanos);
        dst.putLongBigEndian(offset + 11, orderRef);
        dst.putIntBigEndian(offset + 19, canceledShares);
        return 23;
    }

    public static int encodeOrderDelete(MutableDirectBuffer dst, int offset, short locate, short tracking,
                                       long timestampNanos, long orderRef) {
        dst.putByte(offset, MSG_ORDER_DELETE);
        dst.putShortBigEndian(offset + 1, locate);
        dst.putShortBigEndian(offset + 3, tracking);
        dst.putShortBigEndian(offset + 5, (short) (timestampNanos >>> 32));
        dst.putIntBigEndian(offset + 7, (int) timestampNanos);
        dst.putLongBigEndian(offset + 11, orderRef);
        return 19;
    }

    public static int encodeOrderReplace(MutableDirectBuffer dst, int offset, short locate, short tracking,
                                        long timestampNanos, long origOrderRef, long newOrderRef,
                                        int shares, int price) {
        dst.putByte(offset, MSG_ORDER_REPLACE);
        dst.putShortBigEndian(offset + 1, locate);
        dst.putShortBigEndian(offset + 3, tracking);
        dst.putShortBigEndian(offset + 5, (short) (timestampNanos >>> 32));
        dst.putIntBigEndian(offset + 7, (int) timestampNanos);
        dst.putLongBigEndian(offset + 11, origOrderRef);
        dst.putLongBigEndian(offset + 19, newOrderRef);
        dst.putIntBigEndian(offset + 27, shares);
        dst.putIntBigEndian(offset + 31, price);
        return 35;
    }
}
