package com.hft.audit;

/**
 * Reusable binary audit record conforming to MiFID II RTS 25 and US CAT requirements.
 */
public class AuditJournalEntry {

    public long sequenceNumber;
    public long clientOrderId;
    public byte side;
    public long price;
    public int shares;
    public long rejectMask;

    // Monotonic Checkpoints
    public long t1_rxNanos;
    public long t2_bookNanos;
    public long t3_decisionNanos;
    public long t3_riskNanos;
    public long t4_wireTxNanos;

    public void set(long seq, long clientOrderId, byte side, long price, int shares, long rejectMask,
                    long t1, long t2, long t3, long t4) {
        this.sequenceNumber = seq;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.price = price;
        this.shares = shares;
        this.rejectMask = rejectMask;
        this.t1_rxNanos = t1;
        this.t2_bookNanos = t2;
        this.t3_decisionNanos = t3;
        this.t3_riskNanos = t3;
        this.t4_wireTxNanos = t4;
    }

    public boolean isMonotonic() {
        return t1_rxNanos <= t2_bookNanos &&
               t2_bookNanos <= t3_decisionNanos &&
               t3_decisionNanos <= t4_wireTxNanos;
    }
}
