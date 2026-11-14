package dev.cnpay.sandbox;

import dev.cnpay.provider.Provider;
import java.time.LocalDateTime;

public record SandboxRefund(
        Provider provider,
        String outRefundNo,
        String refundId,
        String outTradeNo,
        long amountFen,
        String status,
        String notifyUrl,
        LocalDateTime createdAt,
        LocalDateTime succeededAt) {
}
