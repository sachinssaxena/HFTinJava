package com.hft.core.buffer;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ultra-low latency memory buffer backed by sun.misc.Unsafe.
 * Guarantees zero heap allocation when reading and writing primitive values.
 * Supports on-heap byte arrays, direct ByteBuffers, and off-heap native memory.
 */
public class UnsafeBuffer implements MutableDirectBuffer {

    public static final Unsafe UNSAFE;
    public static final long BYTE_ARRAY_OFFSET;
    private static final boolean NATIVE_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            BYTE_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access sun.misc.Unsafe", e);
        }
    }

    private byte[] byteArray;
    private ByteBuffer byteBuffer;
    private long addressOffset;
    private int capacity;

    public UnsafeBuffer() {
    }

    public UnsafeBuffer(byte[] buffer) {
        wrap(buffer);
    }

    public UnsafeBuffer(byte[] buffer, int offset, int length) {
        wrap(buffer, offset, length);
    }

    public UnsafeBuffer(ByteBuffer buffer) {
        wrap(buffer);
    }

    public UnsafeBuffer(ByteBuffer buffer, int offset, int length) {
        wrap(buffer, offset, length);
    }

    public UnsafeBuffer(long address, int length) {
        wrap(address, length);
    }

    @Override
    public void wrap(byte[] buffer) {
        wrap(buffer, 0, buffer == null ? 0 : buffer.length);
    }

    @Override
    public void wrap(byte[] buffer, int offset, int length) {
        this.byteArray = buffer;
        this.byteBuffer = null;
        this.addressOffset = BYTE_ARRAY_OFFSET + offset;
        this.capacity = length;
    }

    @Override
    public void wrap(ByteBuffer buffer) {
        wrap(buffer, 0, buffer == null ? 0 : buffer.capacity());
    }

    @Override
    public void wrap(ByteBuffer buffer, int offset, int length) {
        this.byteBuffer = buffer;
        if (buffer != null && buffer.isDirect()) {
            this.byteArray = null;
            this.addressOffset = getDirectBufferAddress(buffer) + offset;
        } else if (buffer != null && buffer.hasArray()) {
            this.byteArray = buffer.array();
            this.addressOffset = BYTE_ARRAY_OFFSET + buffer.arrayOffset() + offset;
        } else {
            this.byteArray = null;
            this.addressOffset = 0;
        }
        this.capacity = length;
    }

    @Override
    public void wrap(long address, int length) {
        this.byteArray = null;
        this.byteBuffer = null;
        this.addressOffset = address;
        this.capacity = length;
    }

    private static long getDirectBufferAddress(ByteBuffer buffer) {
        try {
            Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
            return addressField.getLong(buffer);
        } catch (Exception e) {
            throw new RuntimeException("Could not obtain direct buffer address", e);
        }
    }

    @Override
    public byte getByte(int index) {
        return UNSAFE.getByte(byteArray, addressOffset + index);
    }

    @Override
    public void putByte(int index, byte value) {
        UNSAFE.putByte(byteArray, addressOffset + index, value);
    }

    @Override
    public short getShort(int index) {
        return UNSAFE.getShort(byteArray, addressOffset + index);
    }

    @Override
    public short getShortBigEndian(int index) {
        short value = UNSAFE.getShort(byteArray, addressOffset + index);
        return NATIVE_LITTLE_ENDIAN ? Short.reverseBytes(value) : value;
    }

    @Override
    public void putShort(int index, short value) {
        UNSAFE.putShort(byteArray, addressOffset + index, value);
    }

    @Override
    public void putShortBigEndian(int index, short value) {
        UNSAFE.putShort(byteArray, addressOffset + index, NATIVE_LITTLE_ENDIAN ? Short.reverseBytes(value) : value);
    }

    @Override
    public int getInt(int index) {
        return UNSAFE.getInt(byteArray, addressOffset + index);
    }

    @Override
    public int getIntBigEndian(int index) {
        int value = UNSAFE.getInt(byteArray, addressOffset + index);
        return NATIVE_LITTLE_ENDIAN ? Integer.reverseBytes(value) : value;
    }

    @Override
    public void putInt(int index, int value) {
        UNSAFE.putInt(byteArray, addressOffset + index, value);
    }

    @Override
    public void putIntBigEndian(int index, int value) {
        UNSAFE.putInt(byteArray, addressOffset + index, NATIVE_LITTLE_ENDIAN ? Integer.reverseBytes(value) : value);
    }

    @Override
    public long getLong(int index) {
        return UNSAFE.getLong(byteArray, addressOffset + index);
    }

    @Override
    public long getLongBigEndian(int index) {
        long value = UNSAFE.getLong(byteArray, addressOffset + index);
        return NATIVE_LITTLE_ENDIAN ? Long.reverseBytes(value) : value;
    }

    @Override
    public void putLong(int index, long value) {
        UNSAFE.putLong(byteArray, addressOffset + index, value);
    }

    @Override
    public void putLongBigEndian(int index, long value) {
        UNSAFE.putLong(byteArray, addressOffset + index, NATIVE_LITTLE_ENDIAN ? Long.reverseBytes(value) : value);
    }

    @Override
    public void getBytes(int index, byte[] dst, int dstOffset, int length) {
        UNSAFE.copyMemory(byteArray, addressOffset + index, dst, BYTE_ARRAY_OFFSET + dstOffset, length);
    }

    @Override
    public void putBytes(int index, byte[] src, int srcOffset, int length) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_OFFSET + srcOffset, byteArray, addressOffset + index, length);
    }

    @Override
    public void putBytes(int index, DirectBuffer srcBuffer, int srcIndex, int length) {
        UNSAFE.copyMemory(srcBuffer.byteArray(), srcBuffer.addressOffset() + srcIndex,
                byteArray, addressOffset + index, length);
    }

    @Override
    public void setMemory(int index, int length, byte value) {
        UNSAFE.setMemory(byteArray, addressOffset + index, length, value);
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public byte[] byteArray() {
        return byteArray;
    }

    @Override
    public ByteBuffer byteBuffer() {
        return byteBuffer;
    }

    @Override
    public long addressOffset() {
        return addressOffset;
    }
}
