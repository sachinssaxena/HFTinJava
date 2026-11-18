package com.hft.audit;

import com.hft.core.ringbuffer.SpscRingBuffer;

/**
 * Asynchronous, non-blocking MiFID II RTS 25 audit and deterministic event journal tap.
 * Operates on non-isolated background cores (Core 6-7) tapped from the hot path.
 * Zero jitter or allocation impact on Core 2/3/4.
 */
public class AuditJournalTap {

    private final SpscRingBuffer<AuditJournalEntry> ringBuffer;
    private long totalEventsJournaled = 0L;
    private long nonMonotonicAnomalies = 0L;

    public AuditJournalTap(int bufferCapacity) {
        this.ringBuffer = new SpscRingBuffer<>(bufferCapacity, AuditJournalEntry::new);
    }

    public boolean publishAuditRecord(long seq, long clientOrderId, byte side, long price,
                                     int shares, long rejectMask,
                                     long t1, long t2, long t3, long t4) {
        long ringSeq = ringBuffer.next();
        if (ringSeq < 0) {
            return false; // Ring buffer full (drop or flag without blocking hot path)
        }

        AuditJournalEntry entry = ringBuffer.get(ringSeq);
        entry.set(seq, clientOrderId, side, price, shares, rejectMask, t1, t2, t3, t4);
        ringBuffer.publish(ringSeq);
        return true;
    }

    /**
     * Drains pending audit records on background thread.
     */
    public int drainPending() {
        int drained = 0;
        while (true) {
            long seq = ringBuffer.poll();
            if (seq < 0) {
                break;
            }

            AuditJournalEntry entry = ringBuffer.get(seq);
            if (!entry.isMonotonic()) {
                nonMonotonicAnomalies++;
            }
            totalEventsJournaled++;
            ringBuffer.release(seq);
            drained++;
        }
        return drained;
    }

    public long getTotalEventsJournaled() {
        return totalEventsJournaled;
    }

    public long getNonMonotonicAnomalies() {
        return nonMonotonicAnomalies;
    }

    public SpscRingBuffer<AuditJournalEntry> getRingBuffer() {
        return ringBuffer;
    }
}
