package dev.cnpay.provider;

import java.time.LocalDateTime;

/** One line of a provider's daily trade bill, normalised to fen. */
public record BillLine(
        Type type,
        String outTradeNo,
        String providerTradeNo,
        long amountFen,
        String outRefundNo,
        LocalDateTime time) {

    public enum Type { PAYMENT, REFUND }
}
