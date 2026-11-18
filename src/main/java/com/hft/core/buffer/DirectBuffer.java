package com.hft.core.buffer;

import java.nio.ByteBuffer;

/**
 * DirectBuffer interface for zero-copy memory access on the hot path.
 * Compatible with ultra-low latency flyweights and off-heap/on-heap memory representations.
 */
public interface DirectBuffer {

    byte getByte(int index);

    short getShort(int index);

    short getShortBigEndian(int index);

    int getInt(int index);

    int getIntBigEndian(int index);

    long getLong(int index);

    long getLongBigEndian(int index);

    void getBytes(int index, byte[] dst, int dstOffset, int length);

    int capacity();

    byte[] byteArray();

    ByteBuffer byteBuffer();

    long addressOffset();

    void wrap(byte[] buffer);

    void wrap(byte[] buffer, int offset, int length);

    void wrap(ByteBuffer buffer);

    void wrap(ByteBuffer buffer, int offset, int length);

    void wrap(long address, int length);
}
