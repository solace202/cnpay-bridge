package dev.cnpay.sandbox;

import dev.cnpay.provider.Provider;
import java.time.LocalDateTime;

/** The sandbox's own ledger row: what "WeChat Pay" or "Alipay" believes about a trade. */
public record SandboxTrade(
        Provider provider,
        String outTradeNo,
        String tradeNo,
        String appId,
        String mchId,
        String tradeType,
        String description,
        long amountFen,
        long refundedFen,
        String state,
        String notifyUrl,
        String returnUrl,
        String payer,
        LocalDateTime expiresAt,
        LocalDateTime paidAt,
        LocalDateTime createdAt) {

    public static final String NOTPAY = "NOTPAY";
    public static final String SUCCESS = "SUCCESS";
    public static final String CLOSED = "CLOSED";
    public static final String REFUND = "REFUND";

    public boolean paid() {
        return SUCCESS.equals(state) || REFUND.equals(state);
    }
}
