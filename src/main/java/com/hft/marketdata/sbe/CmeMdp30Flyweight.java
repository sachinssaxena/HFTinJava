package com.hft.marketdata.sbe;

import com.hft.core.buffer.DirectBuffer;
import com.hft.core.buffer.MutableDirectBuffer;

/**
 * CME MDP 3.0 Simple Binary Encoding (SBE) flyweight parser.
 * Reads binary market data messages with zero heap allocation.
 */
public class CmeMdp30Flyweight {

    public static final short TEMPLATE_MD_INCREMENTAL_REFRESH = 46;

    public static final byte ACTION_NEW = 0;
    public static final byte ACTION_CHANGE = 1;
    public static final byte ACTION_DELETE = 2;

    public static final byte ENTRY_BID = (byte) '0';
    public static final byte ENTRY_OFFER = (byte) '1';

    private DirectBuffer buffer;
    private int offset;

    public void wrap(DirectBuffer buffer, int offset) {
        this.buffer = buffer;
        this.offset = offset;
    }

    public short blockLength() {
        return buffer.getShort(offset); // SBE is little-endian on x86
    }

    public short templateId() {
        return buffer.getShort(offset + 2);
    }

    public short schemaId() {
        return buffer.getShort(offset + 4);
    }

    public short version() {
        return buffer.getShort(offset + 6);
    }

    public long transactTime() {
        return buffer.getLong(offset + 8);
    }

    public byte matchEventIndicator() {
        return buffer.getByte(offset + 16);
    }

    // First entry helper
    public byte mdUpdateAction() {
        return buffer.getByte(offset + 17);
    }

    public byte mdEntryType() {
        return buffer.getByte(offset + 18);
    }

    public int securityId() {
        return buffer.getInt(offset + 19);
    }

    public int rptSeq() {
        return buffer.getInt(offset + 23);
    }

    public long mdEntryPx() {
        return buffer.getLong(offset + 27);
    }

    public int mdEntrySize() {
        return buffer.getInt(offset + 35);
    }

    public int numberOfOrders() {
        return buffer.getInt(offset + 39);
    }

    public byte mdPriceLevel() {
        return buffer.getByte(offset + 43);
    }

    public static int encodeIncrementalRefresh(MutableDirectBuffer dst, int offset,
                                              long transactTime, byte updateAction, byte entryType,
                                              int securityId, int rptSeq, long px, int size,
                                              int numOrders, byte priceLevel) {
        dst.putShort(offset, (short) 36); // blockLength
        dst.putShort(offset + 2, TEMPLATE_MD_INCREMENTAL_REFRESH);
        dst.putShort(offset + 4, (short) 1); // schemaId
        dst.putShort(offset + 6, (short) 9); // version
        dst.putLong(offset + 8, transactTime);
        dst.putByte(offset + 16, (byte) 1);
        dst.putByte(offset + 17, updateAction);
        dst.putByte(offset + 18, entryType);
        dst.putInt(offset + 19, securityId);
        dst.putInt(offset + 23, rptSeq);
        dst.putLong(offset + 27, px);
        dst.putInt(offset + 35, size);
        dst.putInt(offset + 39, numOrders);
        dst.putByte(offset + 43, priceLevel);
        return 44;
    }
}
