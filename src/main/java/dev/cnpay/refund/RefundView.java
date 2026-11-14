package dev.cnpay.refund;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.cnpay.money.Money;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefundView(
        String id,
        String orderId,
        String outRefundNo,
        String amount,
        String reason,
        RefundStatus status,
        String providerRefundNo,
        LocalDateTime createdAt,
        LocalDateTime succeededAt) {

    public static RefundView of(Refund r) {
        return new RefundView(r.id(), r.orderId(), r.outRefundNo(), Money.toYuan(r.amountFen()), r.reason(),
                r.status(), r.providerRefundNo(), r.createdAt(), r.succeededAt());
    }
}
