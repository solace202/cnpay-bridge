package dev.cnpay.order;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cnpay.money.Money;
import dev.cnpay.provider.Channel;
import dev.cnpay.provider.Provider;
import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderView(
        String id,
        String merchantOrderNo,
        String outTradeNo,
        Provider provider,
        Channel channel,
        String description,
        String amount,
        String refunded,
        String currency,
        OrderStatus status,
        Map<String, Object> payment,
        String checkoutUrl,
        String providerTradeNo,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime createdAt) {

    public static OrderView of(Order o, Map<String, Object> payment, String checkoutUrl) {
        return new OrderView(o.id(), o.merchantOrderNo(), o.outTradeNo(), o.provider(), o.channel(), o.description(),
                Money.toYuan(o.amountFen()), Money.toYuan(o.refundedFen()), o.currency(), o.status(), payment,
                checkoutUrl, o.providerTradeNo(), o.expiresAt(), o.paidAt(), o.createdAt());
    }
}
