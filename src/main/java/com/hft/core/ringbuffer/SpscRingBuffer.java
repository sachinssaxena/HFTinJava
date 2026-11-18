package com.hft.core.ringbuffer;

import sun.misc.Unsafe;
import java.lang.reflect.Field;

/**
 * Cache-line padded, lock-free Single-Producer Single-Consumer (SPSC) Ring Buffer.
 * Eliminates false sharing via 64-byte padding on sequence counters.
 * Pre-allocates entry slots to guarantee 0 heap allocation during steady-state trading.
 */
public class SpscRingBuffer<T> {

    private static final Unsafe UNSAFE;
    private static final long TAIL_OFFSET;
    private static final long HEAD_OFFSET;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
            TAIL_OFFSET = UNSAFE.objectFieldOffset(PaddedProducerSequence.class.getDeclaredField("tail"));
            HEAD_OFFSET = UNSAFE.objectFieldOffset(PaddedConsumerSequence.class.getDeclaredField("head"));
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SpscRingBuffer offsets", e);
        }
    }

    private static class PaddedProducerSequence {
        protected long p1, p2, p3, p4, p5, p6, p7;
        protected volatile long tail = 0;
        protected long p8, p9, p10, p11, p12, p13, p14;
    }

    private static class PaddedConsumerSequence extends PaddedProducerSequence {
        protected long c1, c2, c3, c4, c5, c6, c7;
        protected volatile long head = 0;
        protected long c8, c9, c10, c11, c12, c13, c14;
    }

    private final PaddedConsumerSequence sequence = new PaddedConsumerSequence();
    private final int capacity;
    private final int mask;
    private final Object[] entries;

    public SpscRingBuffer(int capacity, EventFactory<T> factory) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.entries = new Object[capacity];

        for (int i = 0; i < capacity; i++) {
            this.entries[i] = factory.newInstance();
        }
    }

    public interface EventFactory<T> {
        T newInstance();
    }

    public long next() {
        long currentTail = sequence.tail;
        long currentHead = sequence.head;

        if ((currentTail - currentHead) >= capacity) {
            return -1L; // Buffer full
        }
        return currentTail;
    }

    @SuppressWarnings("unchecked")
    public T get(long seq) {
        return (T) entries[(int) (seq & mask)];
    }

    public void publish(long seq) {
        UNSAFE.putOrderedLong(sequence, TAIL_OFFSET, seq + 1);
    }

    public long poll() {
        long currentHead = sequence.head;
        long currentTail = sequence.tail;

        if (currentHead >= currentTail) {
            return -1L; // Buffer empty
        }
        return currentHead;
    }

    public void release(long seq) {
        UNSAFE.putOrderedLong(sequence, HEAD_OFFSET, seq + 1);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return (int) (sequence.tail - sequence.head);
    }

    public boolean isEmpty() {
        return sequence.head >= sequence.tail;
    }
}
