package dev.cnpay.order;

import dev.cnpay.provider.Channel;
import dev.cnpay.provider.Provider;
import java.time.LocalDateTime;

public record Order(
        String id,
        String merchantOrderNo,
        String outTradeNo,
        Provider provider,
        Channel channel,
        String description,
        long amountFen,
        long refundedFen,
        String currency,
        OrderStatus status,
        String payerOpenid,
        String clientIp,
        String providerTradeNo,
        String payerAccount,
        String payPayload,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public long refundableFen() {
        return amountFen - refundedFen;
    }
}
