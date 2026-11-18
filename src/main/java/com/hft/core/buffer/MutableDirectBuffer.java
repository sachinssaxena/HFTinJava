package com.hft.core.buffer;

/**
 * MutableDirectBuffer extends DirectBuffer for zero-copy in-place mutating operations.
 */
public interface MutableDirectBuffer extends DirectBuffer {

    void putByte(int index, byte value);

    void putShort(int index, short value);

    void putShortBigEndian(int index, short value);

    void putInt(int index, int value);

    void putIntBigEndian(int index, int value);

    void putLong(int index, long value);

    void putLongBigEndian(int index, long value);

    void putBytes(int index, byte[] src, int offset, int length);

    void putBytes(int index, DirectBuffer srcBuffer, int srcIndex, int length);

    void setMemory(int index, int length, byte value);
}
