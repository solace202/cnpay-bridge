package dev.cnpay.notify;

import dev.cnpay.provider.Provider;
import java.time.LocalDateTime;

public record PaymentEvent(
        long id,
        Provider provider,
        String notifyId,
        String eventType,
        String outTradeNo,
        boolean signatureValid,
        EventResult result,
        String detail,
        String payload,
        int deliveries,
        LocalDateTime receivedAt) {
}
