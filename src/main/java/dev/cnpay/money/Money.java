package dev.cnpay.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * CNY amounts. WeChat Pay uses integer fen ({@code amount.total = 8800}); Alipay uses yuan strings with two
 * decimals ({@code total_amount = "88.00"}). Internally everything is a {@code long} of fen.
 */
public final class Money {

    /** Alipay's single-transaction ceiling (100,000,000.00 CNY). */
    public static final long MAX_FEN = 10_000_000_000L;

    private Money() {
    }

    /** "88", "88.8", "88.80" → 8880. Rejects negatives, more than two decimals and absurd values. */
    public static long parseYuan(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            throw new IllegalArgumentException("amount is required");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(yuan.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("amount must be a decimal string such as \"88.00\"");
        }
        if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("CNY amounts have at most two decimal places (fen)");
        }
        long fen = value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (fen <= 0) {
            throw new IllegalArgumentException("amount must be at least 0.01");
        }
        if (fen > MAX_FEN) {
            throw new IllegalArgumentException("amount exceeds the per-transaction limit");
        }
        return fen;
    }

    /** 8880 → "88.80" (Alipay format, also used in our API responses). */
    public static String toYuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Bill files print yuan amounts, sometimes with a sign or thousands separators. */
    public static long parseBillYuan(String text) {
        String cleaned = text == null ? "" : text.replace(",", "").replace("¥", "").trim();
        if (cleaned.isEmpty()) {
            return 0;
        }
        return new BigDecimal(cleaned).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String display(long fen) {
        return "¥" + toYuan(fen);
    }
}
