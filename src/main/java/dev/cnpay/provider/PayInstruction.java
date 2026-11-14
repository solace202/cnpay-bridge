package dev.cnpay.provider;

import java.util.Map;

/**
 * What the client needs to collect payment: a QR payload, the JSAPI {@code wx.requestPayment} parameters,
 * an H5 redirect, or the Alipay cashier URL.
 */
public record PayInstruction(String kind, Map<String, String> data) {

    public static PayInstruction qr(String content) {
        return new PayInstruction("qr_code", Map.of("qr_content", content));
    }

    public static PayInstruction redirect(String url) {
        return new PayInstruction("redirect", Map.of("url", url));
    }

    public static PayInstruction jsapi(Map<String, String> requestPaymentParams) {
        return new PayInstruction("wx_request_payment", requestPaymentParams);
    }
}
