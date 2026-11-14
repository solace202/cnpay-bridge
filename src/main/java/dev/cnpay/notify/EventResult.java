package dev.cnpay.notify;

public enum EventResult {
    /** Verified and changed local state. */
    APPLIED,
    /** Verified, but the state change had already happened (redelivery, or the poller got there first). */
    DUPLICATE,
    /** Verified but inconsistent with our order (amount, merchant id, unknown order): acknowledged and flagged. */
    REJECTED,
    /** Signature, timestamp or decryption failed: answered with an error, nothing applied. */
    INVALID,
    /** Verified, nothing to do (e.g. WAIT_BUYER_PAY). */
    IGNORED,
    /** Placeholder while the event is being processed inside the transaction. */
    RECEIVED
}
