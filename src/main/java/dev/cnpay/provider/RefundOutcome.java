package dev.cnpay.provider;

/** WeChat Pay answers refunds with PROCESSING and confirms by callback; Alipay is synchronous. */
public record RefundOutcome(Status status, String providerRefundNo) {

    public enum Status { SUCCESS, PROCESSING }
}
