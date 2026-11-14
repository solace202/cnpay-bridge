package dev.cnpay.notify;

/** What we tell the provider. A non-success answer makes WeChat Pay / Alipay retry the notification. */
public record NotifyResponse(boolean acknowledged, EventResult result, String message) {
}
