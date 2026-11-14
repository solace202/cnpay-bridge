package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.provider.Provider;
import dev.cnpay.sandbox.SandboxNotifier;
import dev.cnpay.sandbox.SandboxService;
import dev.cnpay.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

class AlipayFlowIT extends IntegrationTest {

    @Autowired
    SandboxService sandbox;
    @Autowired
    SandboxNotifier notifier;

    @Test
    void precreateQrIsPaidByRsa2SignedFormNotification() {
        JsonNode order = createOrder("ALIPAY_QR", "66.60");
        assertThat(order.path("payment").path("qr_content").asText()).startsWith("https://qr.alipay.com/");
        simulatePay(order.path("id").asText());
        JsonNode paid = awaitStatus(order.path("id").asText(), "PAID");
        assertThat(paid.path("providerTradeNo").asText()).contains("22001");
        assertThat(eventResult(order.path("outTradeNo").asText(), "trade_status_sync.TRADE_SUCCESS")).isEqualTo("APPLIED");
    }

    @Test
    void forgedOrTamperedNotificationAnswersFailure() {
        JsonNode order = createOrder("ALIPAY_QR", "10.00");
        String otn = order.path("outTradeNo").asText();
        sandbox.pay(Provider.ALIPAY, otn);
        awaitStatus(order.path("id").asText(), "PAID");

        Map<String, String> signed = notifier.signAlipay(sandbox.alipayTradeNotification(
                sandbox.find(Provider.ALIPAY, otn).orElseThrow()));
        Map<String, String> tampered = new java.util.HashMap<>(signed);
        tampered.put("total_amount", "0.01");
        assertThat(postForm(tampered).getBody()).isEqualTo("failure");
        // The genuine one (new notify_id) is fine and idempotent.
        assertThat(postForm(signed).getBody()).isEqualTo("success");
        assertThat(postForm(signed).getBody()).isEqualTo("success");
    }

    @Test
    void pagePayRedirectsThroughTheSignedGatewayToTheCashierAndBackWithAVerifiedReturn() {
        JsonNode order = createOrder("ALIPAY_PAGE", "199.00");
        String url = order.path("payment").path("url").asText();
        assertThat(url).contains("/sandbox/alipay/gateway.do?").contains("method=alipay.trade.page.pay").contains("sign=");

        ResponseEntity<String> gateway = rest.getForEntity(java.net.URI.create(url), String.class);
        ResponseEntity<String> cashier = gateway.getStatusCode().is3xxRedirection()
                ? rest.getForEntity(gateway.getHeaders().getLocation(), String.class) : gateway;
        assertThat(cashier.getStatusCode().value()).isEqualTo(200);
        assertThat(cashier.getBody()).contains("199.00").contains("Confirm payment");

        String otn = order.path("outTradeNo").asText();
        // The cashier answers with a 302 to return_url carrying signed sync-return parameters.
        ResponseEntity<String> back = rest.postForEntity("/sandbox/cashier/ALIPAY/" + otn, null, String.class);
        String page = back.getStatusCode().is3xxRedirection()
                ? rest.getForObject(back.getHeaders().getLocation(), String.class) : back.getBody();
        assertThat(page).contains("return signature verified");
        awaitStatus(order.path("id").asText(), "PAID");
    }

    @Test
    void closingAnUnpaidOrderClosesItAtTheProviderToo() {
        JsonNode order = createOrder("ALIPAY_QR", "3.00");
        JsonNode closed = api.postForObject("/api/orders/" + order.path("id").asText() + "/close", null, JsonNode.class);
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(sandbox.find(Provider.ALIPAY, order.path("outTradeNo").asText()).orElseThrow().state()).isEqualTo("CLOSED");
        assertThat(sandbox.pay(Provider.ALIPAY, order.path("outTradeNo").asText())).isEqualTo(SandboxService.PayResult.CLOSED);
    }

    private ResponseEntity<String> postForm(Map<String, String> params) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        params.forEach(form::add);
        return rest.postForEntity("/notify/alipay", new HttpEntity<>(form, h), String.class);
    }
}
