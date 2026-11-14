package dev.cnpay.order;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** out_trade_no / out_refund_no: 6-32 chars of [A-Za-z0-9], unique per merchant, time-sortable. */
public final class Ids {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String DIGITS = "0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String outTradeNo(LocalDateTime now) {
        return "CN" + now.format(STAMP) + random(8);
    }

    public static String outRefundNo(LocalDateTime now) {
        return "RF" + now.format(STAMP) + random(8);
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }
}
