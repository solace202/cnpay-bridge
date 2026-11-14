package dev.cnpay.sandbox;

import dev.cnpay.config.ClockConfig;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.money.Money;
import dev.cnpay.provider.Provider;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** The provider-side behaviour of the sandbox: what happens when a shopper pays, a refund settles, etc. */
@Service
@ConditionalOnProperty(name = "cnpay.mode", havingValue = "sandbox", matchIfMissing = true)
public class SandboxService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SandboxLedger ledger;
    private final SandboxNotifier notifier;
    private final CnpayProperties properties;
    private final Clock clock;

    public SandboxService(SandboxLedger ledger, SandboxNotifier notifier, CnpayProperties properties, Clock clock) {
        this.ledger = ledger;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    public Optional<SandboxTrade> find(Provider provider, String outTradeNo) {
        return ledger.find(provider, outTradeNo);
    }

    public SandboxTrade open(Provider provider, String outTradeNo, String tradeType, String description, long fen,
                             String notifyUrl, String returnUrl, String payer, LocalDateTime expiresAt) {
        SandboxTrade trade = new SandboxTrade(provider, outTradeNo, tradeNo(provider), appId(provider),
                provider == Provider.WECHAT ? properties.wechat().mchId() : null, tradeType, description, fen, 0,
                SandboxTrade.NOTPAY, notifyUrl, returnUrl, payer, expiresAt, null, now());
        ledger.insert(trade);
        return trade;
    }

    /** The shopper scans and confirms. Marks the trade paid and fires the asynchronous notification. */
    public PayResult pay(Provider provider, String outTradeNo) {
        SandboxTrade trade = ledger.find(provider, outTradeNo).orElse(null);
        if (trade == null) {
            return PayResult.NOT_FOUND;
        }
        if (trade.paid()) {
            return PayResult.ALREADY_PAID;
        }
        if (SandboxTrade.CLOSED.equals(trade.state())
                || (trade.expiresAt() != null && trade.expiresAt().isBefore(now()))) {
            return PayResult.CLOSED;
        }
        String payer = trade.payer() != null ? trade.payer()
                : provider == Provider.WECHAT ? "oUpF8u" + randomAlnum(22) : "138****" + digits(4);
        if (!ledger.markPaid(provider, outTradeNo, payer, now())) {
            return PayResult.ALREADY_PAID;
        }
        SandboxTrade paid = ledger.find(provider, outTradeNo).orElseThrow();
        if (provider == Provider.WECHAT) {
            notifier.sendWechat(paid.notifyUrl(), notifier.wechatNotification("TRANSACTION.SUCCESS", "transaction",
                    "支付成功", wechatTransaction(paid)));
        } else {
            notifier.sendAlipay(paid.notifyUrl(), alipayTradeNotification(paid));
        }
        return PayResult.PAID;
    }

    public enum PayResult { PAID, ALREADY_PAID, CLOSED, NOT_FOUND }

    /** WeChat Pay refunds settle asynchronously: PROCESSING now, REFUND.SUCCESS callback shortly after. */
    public void settleWechatRefund(SandboxRefund refund) {
        SandboxTrade trade = ledger.find(Provider.WECHAT, refund.outTradeNo()).orElseThrow();
        ledger.markRefundSucceeded(Provider.WECHAT, refund.outRefundNo(), now());
        ledger.addRefunded(Provider.WECHAT, trade.outTradeNo(), refund.amountFen());
        SandboxRefund settled = ledger.findRefund(Provider.WECHAT, refund.outRefundNo()).orElseThrow();
        if (refund.notifyUrl() != null) {
            notifier.sendWechat(refund.notifyUrl(), notifier.wechatNotification("REFUND.SUCCESS", "refund", "退款成功",
                    wechatRefund(trade, settled)));
        }
    }

    // ------------------------------------------------------------- chaos for demos and tests

    /** A payment the merchant never created locally (e.g. an order placed through another integration). */
    public SandboxTrade orphanPayment(Provider provider, long fen) {
        String otn = "EXT" + now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + digits(6);
        SandboxTrade trade = open(provider, otn, provider == Provider.WECHAT ? "NATIVE" : "FACE_TO_FACE_PAYMENT",
                "Order from another channel", fen, null, null, null, now().plusMinutes(30));
        ledger.markPaid(provider, otn, provider == Provider.WECHAT ? "oUpF8u" + randomAlnum(22) : "139****" + digits(4), now());
        return ledger.find(provider, otn).orElseThrow();
    }

    /** Makes the provider's record of the latest payment disagree with ours by one yuan. */
    public Optional<SandboxTrade> tamperLatestAmount(Provider provider) {
        return ledger.latestPaid(provider).map(t -> tamperAmount(provider, t.outTradeNo()));
    }

    public SandboxTrade tamperAmount(Provider provider, String outTradeNo) {
        SandboxTrade t = ledger.find(provider, outTradeNo).orElseThrow();
        ledger.setAmount(provider, outTradeNo, t.amountFen() + 100);
        return ledger.find(provider, outTradeNo).orElseThrow();
    }

    // ------------------------------------------------------------- payloads

    public Map<String, Object> wechatTransaction(SandboxTrade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mchid", t.mchId());
        m.put("appid", t.appId());
        m.put("out_trade_no", t.outTradeNo());
        if (t.paid()) {
            m.put("transaction_id", t.tradeNo());
        }
        m.put("trade_type", t.tradeType());
        m.put("trade_state", t.state());
        m.put("trade_state_desc", switch (t.state()) {
            case SandboxTrade.SUCCESS -> "支付成功";
            case SandboxTrade.REFUND -> "转入退款";
            case SandboxTrade.CLOSED -> "已关闭";
            default -> "订单未支付";
        });
        if (t.paid()) {
            m.put("bank_type", "OTHERS");
            m.put("attach", "");
            m.put("success_time", rfc3339(t.paidAt()));
            m.put("payer", Map.of("openid", t.payer()));
        }
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", t.amountFen());
        if (t.paid()) {
            amount.put("payer_total", t.amountFen());
        }
        amount.put("currency", "CNY");
        amount.put("payer_currency", "CNY");
        m.put("amount", amount);
        return m;
    }

    public Map<String, Object> wechatRefund(SandboxTrade trade, SandboxRefund r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mchid", trade.mchId());
        m.put("out_trade_no", trade.outTradeNo());
        m.put("transaction_id", trade.tradeNo());
        m.put("out_refund_no", r.outRefundNo());
        m.put("refund_id", r.refundId());
        m.put("refund_status", r.status());
        if (r.succeededAt() != null) {
            m.put("success_time", rfc3339(r.succeededAt()));
        }
        m.put("user_received_account", "支付用户零钱");
        m.put("amount", Map.of("total", trade.amountFen(), "refund", r.amountFen(),
                "payer_total", trade.amountFen(), "payer_refund", r.amountFen()));
        return m;
    }

    public Map<String, String> alipayTradeNotification(SandboxTrade t) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("notify_time", now().format(ALIPAY_TIME));
        p.put("notify_type", "trade_status_sync");
        p.put("notify_id", "2026" + randomAlnum(28).toLowerCase());
        p.put("charset", "UTF-8");
        p.put("version", "1.0");
        p.put("app_id", t.appId());
        p.put("auth_app_id", t.appId());
        p.put("trade_no", t.tradeNo());
        p.put("out_trade_no", t.outTradeNo());
        p.put("trade_status", "TRADE_SUCCESS");
        p.put("total_amount", Money.toYuan(t.amountFen()));
        p.put("receipt_amount", Money.toYuan(t.amountFen()));
        p.put("buyer_pay_amount", Money.toYuan(t.amountFen()));
        p.put("invoice_amount", Money.toYuan(t.amountFen()));
        p.put("point_amount", "0.00");
        p.put("subject", t.description());
        p.put("buyer_logon_id", t.payer());
        p.put("seller_id", "2088000000000001");
        p.put("gmt_create", t.createdAt().format(ALIPAY_TIME));
        p.put("gmt_payment", t.paidAt().format(ALIPAY_TIME));
        p.put("fund_bill_list", "[{\"amount\":\"" + Money.toYuan(t.amountFen()) + "\",\"fundChannel\":\"ALIPAYACCOUNT\"}]");
        return p;
    }

    // ------------------------------------------------------------- ids

    String appId(Provider provider) {
        return provider == Provider.WECHAT ? properties.wechat().appId() : properties.alipay().appId();
    }

    String tradeNo(Provider provider) {
        String day = now().format(DAY);
        return provider == Provider.WECHAT ? "4200002" + digits(3) + day + digits(10) : day + "22001" + digits(15);
    }

    String refundId() {
        return "50300" + digits(25);
    }

    static String rfc3339(LocalDateTime beijing) {
        return beijing.withNano(0).atZone(ClockConfig.BEIJING).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String digits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    static String randomAlnum(int n) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, Math.min(n, 32))
                + (n > 32 ? randomAlnum(n - 32) : "");
    }
}
