package dev.cnpay.notify;

import com.alipay.api.AlipayApiException;
import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.wechat.pay.java.core.exception.MalformedMessageException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.WechatPayCrypto;
import dev.cnpay.money.Money;
import dev.cnpay.order.Order;
import dev.cnpay.order.OrderRepository;
import dev.cnpay.order.OrderStatus;
import dev.cnpay.provider.Provider;
import dev.cnpay.provider.alipay.AlipayClients;
import dev.cnpay.provider.wechat.WechatPayClients;
import dev.cnpay.provider.wechat.WechatPayProvider;
import dev.cnpay.refund.RefundRepository;
import dev.cnpay.refund.RefundService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies and applies asynchronous notifications.
 * <ol>
 *   <li>Authenticate: WeChat Pay v3 signature + timestamp window + AES-256-GCM decryption via the official SDK;
 *       Alipay RSA2 {@code rsaCheckV1}. Failures are logged as INVALID and answered with an error.</li>
 *   <li>Deduplicate on (provider, notify id) in the same transaction as the state change.</li>
 *   <li>Cross-check merchant id, app id and amount against our order before changing anything.</li>
 *   <li>Apply with conditional updates, so a callback that loses a race with the poller is a harmless DUPLICATE.</li>
 * </ol>
 * If applying throws, the transaction (including the event row) rolls back and the provider retries.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final long MAX_SKEW_SECONDS = 300;
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentEventRepository events;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final RefundService refundService;
    private final WechatPayClients wechat;
    private final AlipayClients alipay;
    private final KeyRing keys;
    private final CnpayProperties properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public NotificationService(PaymentEventRepository events, OrderRepository orders, RefundRepository refunds,
                               RefundService refundService, WechatPayClients wechat, AlipayClients alipay,
                               KeyRing keys, CnpayProperties properties, ObjectMapper mapper,
                               TransactionTemplate tx, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.refunds = refunds;
        this.refundService = refundService;
        this.wechat = wechat;
        this.alipay = alipay;
        this.keys = keys;
        this.properties = properties;
        this.mapper = mapper;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- WeChat Pay

    public NotifyResponse handleWechat(String serial, String timestamp, String nonce, String signature,
                                       String signType, String body) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return invalid(Provider.WECHAT, "UNKNOWN", null, "Body is not JSON");
        }
        String eventType = envelope.path("event_type").asText("UNKNOWN");
        String notifyId = envelope.path("id").asText(null);

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException | NullPointerException e) {
            return invalid(Provider.WECHAT, eventType, null, "Missing or malformed Wechatpay-Timestamp");
        }
        if (Math.abs(Instant.now(clock).getEpochSecond() - ts) > MAX_SKEW_SECONDS) {
            return invalid(Provider.WECHAT, eventType, null, "Wechatpay-Timestamp outside the 5-minute window (replay?)");
        }

        Class<?> type = eventType.startsWith("TRANSACTION.") ? Transaction.class
                : eventType.startsWith("REFUND.") ? RefundNotification.class : JsonObject.class;
        Object resource;
        try {
            RequestParam param = new RequestParam.Builder()
                    .serialNumber(serial).timestamp(timestamp).nonce(nonce)
                    .signature(signature).signType(signType).body(body).build();
            resource = wechat.get().notificationParser().parse(param, type);
        } catch (ValidationException e) {
            return invalid(Provider.WECHAT, eventType, null, "Signature verification failed (serial " + serial + ")");
        } catch (MalformedMessageException | IllegalArgumentException e) {
            return invalid(Provider.WECHAT, eventType, null, "Malformed notification: " + e.getMessage());
        } catch (RuntimeException e) {
            return invalid(Provider.WECHAT, eventType, null, "Could not decrypt resource: " + e.getMessage());
        }
        if (notifyId == null) {
            return invalid(Provider.WECHAT, eventType, null, "Notification has no id");
        }
        String plaintext = decryptForLog(envelope);
        String outTradeNo = resource instanceof Transaction t ? t.getOutTradeNo()
                : resource instanceof RefundNotification r ? r.getOutTradeNo() : null;

        return tx.execute(status -> {
            boolean first = events.recordDelivery(Provider.WECHAT, notifyId, eventType, outTradeNo, true,
                    EventResult.RECEIVED, null, plaintext);
            if (!first) {
                return new NotifyResponse(true, EventResult.DUPLICATE, "Already processed " + notifyId);
            }
            Outcome outcome = switch (resource) {
                case Transaction t -> applyWechatTransaction(eventType, t);
                case RefundNotification r -> applyWechatRefund(r);
                default -> new Outcome(EventResult.IGNORED, "Event type " + eventType + " is not handled");
            };
            events.setResult(Provider.WECHAT, notifyId, outcome.result(), outcome.detail());
            log.info("WeChat Pay {} {} → {} ({})", eventType, outTradeNo, outcome.result(), outcome.detail());
            return new NotifyResponse(true, outcome.result(), outcome.detail());
        });
    }

    private Outcome applyWechatTransaction(String eventType, Transaction t) {
        if (!properties.wechat().mchId().equals(t.getMchid()) || !properties.wechat().appId().equals(t.getAppid())) {
            return new Outcome(EventResult.REJECTED, "mchid/appid " + t.getMchid() + "/" + t.getAppid() + " are not ours");
        }
        Optional<Order> order = orders.findByOutTradeNo(t.getOutTradeNo());
        if (order.isEmpty()) {
            return new Outcome(EventResult.REJECTED, "Unknown out_trade_no " + t.getOutTradeNo());
        }
        long total = t.getAmount() == null || t.getAmount().getTotal() == null ? -1 : t.getAmount().getTotal();
        if (total != order.get().amountFen() || (t.getAmount().getCurrency() != null && !"CNY".equals(t.getAmount().getCurrency()))) {
            return new Outcome(EventResult.REJECTED, "Amount mismatch: notified " + Money.display(total)
                    + ", order is " + Money.display(order.get().amountFen()));
        }
        if (t.getTradeState() != Transaction.TradeStateEnum.SUCCESS) {
            return new Outcome(EventResult.IGNORED, eventType + " with trade_state " + t.getTradeState());
        }
        String payer = t.getPayer() == null ? null : t.getPayer().getOpenid();
        LocalDateTime paidAt = WechatPayProvider.parseTime(t.getSuccessTime());
        return markPaid(order.get(), t.getTransactionId(), payer, paidAt);
    }

    private Outcome applyWechatRefund(RefundNotification r) {
        if (refunds.findByOutRefundNo(r.getOutRefundNo()).isEmpty()) {
            return new Outcome(EventResult.REJECTED, "Unknown out_refund_no " + r.getOutRefundNo());
        }
        if (r.getRefundStatus() == Status.SUCCESS) {
            boolean applied = refundService.applySucceeded(r.getOutRefundNo(), r.getRefundId(),
                    Optional.ofNullable(WechatPayProvider.parseTime(r.getSuccessTime())).orElse(LocalDateTime.now(clock)));
            return applied
                    ? new Outcome(EventResult.APPLIED, "Refund " + r.getOutRefundNo() + " PROCESSING → SUCCESS")
                    : new Outcome(EventResult.DUPLICATE, "Refund " + r.getOutRefundNo() + " already settled");
        }
        boolean failed = refunds.markFailed(r.getOutRefundNo());
        return new Outcome(failed ? EventResult.APPLIED : EventResult.DUPLICATE,
                "Refund " + r.getOutRefundNo() + " ended " + r.getRefundStatus());
    }

    private String decryptForLog(JsonNode envelope) {
        JsonNode res = envelope.path("resource");
        try {
            return WechatPayCrypto.decryptResource(keys.wechatApiV3Key(), res.path("associated_data").asText(""),
                    res.path("nonce").asText(), res.path("ciphertext").asText());
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Alipay

    public NotifyResponse handleAlipay(Map<String, String> params) {
        Map<String, String> copy = new TreeMap<>(params);
        boolean valid;
        try {
            valid = AlipaySignature.rsaCheckV1(new TreeMap<>(params), alipay.alipayPublicKeyBase64(), "UTF-8", "RSA2");
        } catch (AlipayApiException | RuntimeException e) {
            valid = false;
        }
        String notifyType = copy.getOrDefault("notify_type", "unknown");
        String eventType = notifyType + "." + copy.getOrDefault("trade_status", "?");
        if (!valid) {
            return invalid(Provider.ALIPAY, eventType, copy.get("out_trade_no"), "RSA2 signature verification failed");
        }
        String notifyId = copy.get("notify_id");
        if (notifyId == null) {
            return invalid(Provider.ALIPAY, eventType, copy.get("out_trade_no"), "Notification has no notify_id");
        }
        copy.remove("sign");
        String payload = json(copy);

        return tx.execute(status -> {
            boolean first = events.recordDelivery(Provider.ALIPAY, notifyId, eventType, copy.get("out_trade_no"), true,
                    EventResult.RECEIVED, null, payload);
            if (!first) {
                return new NotifyResponse(true, EventResult.DUPLICATE, "Already processed " + notifyId);
            }
            Outcome outcome = applyAlipay(copy);
            events.setResult(Provider.ALIPAY, notifyId, outcome.result(), outcome.detail());
            log.info("Alipay {} {} → {} ({})", eventType, copy.get("out_trade_no"), outcome.result(), outcome.detail());
            return new NotifyResponse(true, outcome.result(), outcome.detail());
        });
    }

    private Outcome applyAlipay(Map<String, String> p) {
        if (!properties.alipay().appId().equals(p.get("app_id"))) {
            return new Outcome(EventResult.REJECTED, "app_id " + p.get("app_id") + " is not ours");
        }
        Optional<Order> order = orders.findByOutTradeNo(p.getOrDefault("out_trade_no", ""));
        if (order.isEmpty()) {
            return new Outcome(EventResult.REJECTED, "Unknown out_trade_no " + p.get("out_trade_no"));
        }
        long total;
        try {
            total = Money.parseYuan(p.get("total_amount"));
        } catch (IllegalArgumentException e) {
            return new Outcome(EventResult.REJECTED, "Bad total_amount " + p.get("total_amount"));
        }
        if (total != order.get().amountFen()) {
            return new Outcome(EventResult.REJECTED, "Amount mismatch: notified " + Money.display(total)
                    + ", order is " + Money.display(order.get().amountFen()));
        }
        if (p.containsKey("refund_fee") || p.containsKey("gmt_refund")) {
            return new Outcome(EventResult.IGNORED, "Refund notice; Alipay refunds are confirmed synchronously");
        }
        return switch (p.getOrDefault("trade_status", "")) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> markPaid(order.get(), p.get("trade_no"), p.get("buyer_logon_id"),
                    p.containsKey("gmt_payment") ? LocalDateTime.parse(p.get("gmt_payment"), ALIPAY_TIME) : LocalDateTime.now(clock));
            case "TRADE_CLOSED" -> order.get().status() == OrderStatus.PENDING
                    && orders.markClosed(order.get().id(), LocalDateTime.now(clock))
                    ? new Outcome(EventResult.APPLIED, "PENDING → CLOSED")
                    : new Outcome(EventResult.IGNORED, "Order is " + order.get().status());
            default -> new Outcome(EventResult.IGNORED, "trade_status " + p.get("trade_status"));
        };
    }

    // ---------------------------------------------------------------- shared

    private Outcome markPaid(Order order, String providerTradeNo, String payer, LocalDateTime paidAt) {
        OrderStatus before = order.status();
        if (orders.markPaid(order.outTradeNo(), providerTradeNo, payer, paidAt != null ? paidAt : LocalDateTime.now(clock))) {
            return new Outcome(EventResult.APPLIED, before + " → PAID (" + Money.display(order.amountFen()) + ")");
        }
        return new Outcome(EventResult.DUPLICATE, "Order already " + before);
    }

    private NotifyResponse invalid(Provider provider, String eventType, String outTradeNo, String reason) {
        log.warn("Rejected {} notification: {}", provider, reason);
        events.recordDelivery(provider, "invalid-" + UUID.randomUUID(), eventType, outTradeNo, false,
                EventResult.INVALID, reason, null);
        return new NotifyResponse(false, EventResult.INVALID, reason);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Outcome(EventResult result, String detail) {
    }
}
