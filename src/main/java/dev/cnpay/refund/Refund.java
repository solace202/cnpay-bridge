package dev.cnpay.refund;

import java.time.LocalDateTime;

public record Refund(
        String id,
        String orderId,
        String outRefundNo,
        long amountFen,
        String reason,
        RefundStatus status,
        String providerRefundNo,
        LocalDateTime createdAt,
        LocalDateTime succeededAt) {
}
