package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class OrderApiIT extends IntegrationTest {

    @Test
    void apiRequiresBasicAuth() {
        assertThat(rest.getForEntity("/api/orders", String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(api.getForEntity("/api/orders", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void rejectsAmountsWithMoreThanTwoDecimalsAndUnknownChannels() {
        ResponseEntity<JsonNode> r = api.postForEntity("/api/orders", json(Map.of("merchantOrderNo", "BAD-1",
                "channel", "WECHAT_NATIVE", "amount", "1.005", "description", "x")), JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(r.getBody().path("detail").asText()).contains("two decimal");

        r = api.postForEntity("/api/orders", json(Map.of("merchantOrderNo", "BAD-2",
                "channel", "PAYPAL", "amount", "1.00", "description", "x")), JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void merchantOrderNumberCanOnlyBePaidOnce() {
        Map<String, Object> body = Map.of("merchantOrderNo", "DUP-" + System.nanoTime(), "channel", "ALIPAY_QR",
                "amount", "20.00", "description", "x");
        assertThat(api.postForEntity("/api/orders", json(body), JsonNode.class).getStatusCode().value()).isEqualTo(201);
        ResponseEntity<JsonNode> again = api.postForEntity("/api/orders", json(body), JsonNode.class);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().path("code").asText()).isEqualTo("ORDER_EXISTS");
    }

    @Test
    void idempotencyKeyReplaysTheFirstResponseAndRejectsADifferentBody() {
        String key = "idem-" + System.nanoTime();
        Map<String, Object> body = Map.of("merchantOrderNo", "IDEM-" + System.nanoTime(), "channel", "WECHAT_NATIVE",
                "amount", "30.00", "description", "x");
        ResponseEntity<JsonNode> first = api.postForEntity("/api/orders", withKey(body, key), JsonNode.class);
        ResponseEntity<JsonNode> second = api.postForEntity("/api/orders", withKey(body, key), JsonNode.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().path("id").asText()).isEqualTo(first.getBody().path("id").asText());

        Map<String, Object> other = new java.util.HashMap<>(body);
        other.put("amount", "31.00");
        ResponseEntity<JsonNode> conflict = api.postForEntity("/api/orders", withKey(other, key), JsonNode.class);
        assertThat(conflict.getStatusCode().value()).isEqualTo(422);
        assertThat(conflict.getBody().path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void orderViewExposesCheckoutUrlAndYuanAmounts() {
        JsonNode order = createOrder("WECHAT_NATIVE", "88.8");
        assertThat(order.path("amount").asText()).isEqualTo("88.80");
        assertThat(order.path("currency").asText()).isEqualTo("CNY");
        assertThat(order.path("checkoutUrl").asText()).endsWith("/pay/" + order.path("id").asText());
        assertThat(order.path("outTradeNo").asText()).matches("CN\\d{22}");
    }

    private HttpEntity<Object> withKey(Object body, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("Idempotency-Key", key);
        return new HttpEntity<>(body, h);
    }
}
