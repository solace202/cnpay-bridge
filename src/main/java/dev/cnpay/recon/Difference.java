package dev.cnpay.recon;

public record Difference(
        Kind kind,
        String outTradeNo,
        String localStatus,
        Long localFen,
        String remoteStatus,
        Long remoteFen,
        String action) {

    public enum Kind {
        /** Provider has a payment we have no order for. */
        MISSING_LOCAL,
        /** We think it was paid; the provider's bill has no such payment. */
        MISSING_REMOTE,
        /** Provider says paid, we still have it PENDING/CLOSED (lost notification). Auto-repaired. */
        STATUS_MISMATCH,
        /** Both sides paid, amounts differ. */
        AMOUNT_MISMATCH,
        /** Refund totals for the day differ. */
        REFUND_MISMATCH
    }
}
