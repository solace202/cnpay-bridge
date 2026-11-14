package dev.cnpay.provider;

import java.time.LocalDateTime;

/** A provider's view of one trade, from a query call or a verified notification. */
public record TradeSnapshot(
        String outTradeNo,
        TradeState state,
        String providerTradeNo,
        long amountFen,
        String payer,
        LocalDateTime paidAt) {

    public enum TradeState { NOT_PAID, PAID, CLOSED, REFUNDED, NOT_FOUND }
}
