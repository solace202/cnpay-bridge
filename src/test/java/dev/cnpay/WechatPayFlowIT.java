package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.WechatPayCrypto;
import dev.cnpay.sandbox.SandboxNotifier;
import dev.cnpay.sandbox.SandboxService;
import dev.cnpay.sandbox.SandboxTrade;
import dev.cnpay.provider.Provider;
import dev.cnpay.support.IntegrationTest;
import java.security.PublicKey;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class WechatPayFlowIT extends IntegrationTest {

    @Autowired
    SandboxNotifier notifier;
    @Autowired
    SandboxService sandbox;
    @Autowired
    KeyRing keys;

    @Test
    void nativeQrOrderIsPaidBySignedEncryptedNotification() {
        JsonNode order = createOrder("WECHAT_NATIVE", "88.00");
        assertThat(order.path("status").asText()).isEqualTo("PENDING");
        assertThat(order.path("payment").path("kind").asText()).isEqualTo("qr_code");
        assertThat(order.path("payment").path("qr_content").asText()).startsWith("weixin://wxpay/bizpayurl?pr=");

        simulatePay(order.path("id").asText());
        JsonNode paid = awaitStatus(order.path("id").asText(), "PAID");

        assertThat(paid.path("providerTradeNo").asText()).startsWith("4200002");
        assertThat(eventResult(order.path("outTradeNo").asText(), "TRANSACTION.SUCCESS")).isEqualTo("APPLIED");
    }

    @Test
    void redeliveredNotificationIsAcknowledgedButAppliedOnce() {
        JsonNode order = createOrder("WECHAT_NATIVE", "12.34");
        String otn = order.path("outTradeNo").asText();
        sandbox.pay(Provider.WECHAT, otn);
        awaitStatus(order.path("id").asText(), "PAID");

        SandboxTrade trade = sandbox.find(Provider.WECHAT, otn).orElseThrow();
        var n = notifier.wechatNotification("TRANSACTION.SUCCESS", "transaction", "支付成功", sandbox.wechatTransaction(trade));
        assertThat(post(n.headers(), n.body()).getStatusCode().value()).isEqualTo(204);
        assertThat(post(n.headers(), n.body()).getStatusCode().value()).isEqualTo(204);

        Integer deliveries = jdbc.sql("select deliveries from payment_events where notify_id = ?").param(n.id())
                .query(Integer.class).single();
        assertThat(deliveries).isEqualTo(2);
        // A second, different notification for the same paid trade is a DUPLICATE, not a second payment.
        assertThat(jdbc.sql("select result from payment_events where notify_id = ?").param(n.id()).query(String.class).single())
                .isEqualTo("DUPLICATE");
        assertThat(order(order.path("id").asText()).path("status").asText()).isEqualTo("PAID");
    }

    @Test
    void forgedSignatureIsRejectedWith401AndLoggedAsInvalid() {
        JsonNode order = createOrder("WECHAT_NATIVE", "5.00");
        String otn = order.path("outTradeNo").asText();
        SandboxTrade trade = sandbox.find(Provider.WECHAT, otn).orElseThrow();
        var n = notifier.wechatNotification("TRANSACTION.SUCCESS", "transaction", "支付成功",
                Map.of("mchid", trade.mchId(), "appid", trade.appId(), "out_trade_no", otn, "trade_state", "SUCCESS",
                        "transaction_id", "4200009999", "amount", Map.of("total", 500)));
        Map<String, String> headers = new java.util.HashMap<>(n.headers());
        headers.put("Wechatpay-Signature", WechatPayCrypto.sign(dev.cnpay.crypto.Keys.generateRsa2048().getPrivate(),
                WechatPayCrypto.message(headers.get("Wechatpay-Timestamp"), headers.get("Wechatpay-Nonce"), n.body())));

        ResponseEntity<String> r = post(headers, n.body());
        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getBody()).contains("FAIL");
        assertThat(order(order.path("id").asText()).path("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.sql("select count(*) from payment_events where out_trade_no is null and result = 'INVALID'")
                .query(Integer.class).single()).isPositive();
    }

    @Test
    void staleTimestampIsTreatedAsReplay() {
        var n = notifier.wechatNotification("TRANSACTION.SUCCESS", "transaction", "支付成功", Map.of("x", 1));
        Map<String, String> headers = new java.util.HashMap<>(n.headers());
        String old = String.valueOf(java.time.Instant.now().getEpochSecond() - 3600);
        headers.put("Wechatpay-Timestamp", old);
        headers.put("Wechatpay-Signature", "irrelevant");
        assertThat(post(headers, n.body()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void verifiedNotificationWithWrongAmountIsAcknowledgedButRejected() {
        JsonNode order = createOrder("WECHAT_NATIVE", "100.00");
        String otn = order.path("outTradeNo").asText();
        SandboxTrade trade = sandbox.find(Provider.WECHAT, otn).orElseThrow();
        Map<String, Object> tx = new java.util.LinkedHashMap<>(sandbox.wechatTransaction(trade));
        tx.put("trade_state", "SUCCESS");
        tx.put("transaction_id", "4200001111");
        tx.put("success_time", "2026-11-14T10:00:00+08:00");
        tx.put("amount", Map.of("total", 1, "currency", "CNY"));
        var n = notifier.wechatNotification("TRANSACTION.SUCCESS", "transaction", "支付成功", tx);

        assertThat(post(n.headers(), n.body()).getStatusCode().value()).isEqualTo(204);
        assertThat(eventResult(otn, "TRANSACTION.SUCCESS")).isEqualTo("REJECTED");
        assertThat(order(order.path("id").asText()).path("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void jsapiReturnsRequestPaymentParamsSignedWithTheMerchantKey() {
        JsonNode order = createOrder("WECHAT_JSAPI", "9.90", Map.of("payerOpenid", "oUpF8uMuAJO_M2pxb1Q9zNjWeS6o"));
        JsonNode p = order.path("payment");
        assertThat(p.path("kind").asText()).isEqualTo("wx_request_payment");
        assertThat(p.path("package").asText()).startsWith("prepay_id=wx");
        assertThat(p.path("signType").asText()).isEqualTo("RSA");
        String message = WechatPayCrypto.message(p.path("appId").asText(), p.path("timeStamp").asText(),
                p.path("nonceStr").asText(), p.path("package").asText());
        PublicKey merchantPublic = keys.sandbox().wechatMerchantPublicKey();
        assertThat(WechatPayCrypto.verify(merchantPublic, message, p.path("paySign").asText())).isTrue();
    }

    @Test
    void h5ReturnsCashierRedirectAndJsapiWithoutOpenidIsRejected() {
        JsonNode order = createOrder("WECHAT_H5", "1.00", Map.of("clientIp", "203.0.113.7"));
        assertThat(order.path("payment").path("kind").asText()).isEqualTo("redirect");
        assertThat(order.path("payment").path("url").asText()).contains("/sandbox/cashier/WECHAT/");

        var r = api.postForEntity("/api/orders", json(Map.of("merchantOrderNo", "NO-OPENID-1", "channel", "WECHAT_JSAPI",
                "amount", "1.00", "description", "x")), JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().path("detail").asText()).contains("payerOpenid");
    }

    private ResponseEntity<String> post(Map<String, String> headers, String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(h::add);
        return rest.postForEntity("/notify/wechat", new HttpEntity<>(body, h), String.class);
    }
}
