package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class RefundIT extends IntegrationTest {

    @Test
    void wechatPartialRefundIsProcessingUntilRefundSuccessNotification() {
        String id = paidOrder("WECHAT_NATIVE", "100.00");
        ResponseEntity<JsonNode> r = refund(id, "30.00");
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getBody().path("status").asText()).isEqualTo("PROCESSING");

        await().atMost(Duration.ofSeconds(10)).until(() ->
                "SUCCESS".equals(api.getForObject("/api/orders/" + id + "/refunds", JsonNode.class).get(0).path("status").asText()));
        JsonNode order = order(id);
        assertThat(order.path("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(order.path("refunded").asText()).isEqualTo("30.00");
        assertThat(eventResult(order.path("outTradeNo").asText(), "REFUND.SUCCESS")).isEqualTo("APPLIED");
    }

    @Test
    void refundsCannotExceedWhatIsLeftIncludingInFlightRefunds() {
        String id = paidOrder("WECHAT_NATIVE", "50.00");
        assertThat(refund(id, "40.00").getStatusCode().value()).isEqualTo(201);
        ResponseEntity<JsonNode> over = refund(id, "10.01");
        assertThat(over.getStatusCode().value()).isEqualTo(422);
        assertThat(over.getBody().path("code").asText()).isEqualTo("REFUND_EXCEEDS_BALANCE");
        assertThat(refund(id, "10.00").getStatusCode().value()).isEqualTo(201);
        awaitStatus(id, "REFUNDED");
    }

    @Test
    void alipayRefundIsSynchronous() {
        String id = paidOrder("ALIPAY_QR", "25.50");
        ResponseEntity<JsonNode> r = refund(id, "25.50");
        assertThat(r.getBody().path("status").asText()).isEqualTo("SUCCESS");
        assertThat(order(id).path("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    void unpaidOrdersCannotBeRefunded() {
        JsonNode order = createOrder("ALIPAY_QR", "5.00");
        ResponseEntity<JsonNode> r = refund(order.path("id").asText(), "1.00");
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().path("code").asText()).isEqualTo("ORDER_NOT_PAID");
    }

    private String paidOrder(String channel, String amount) {
        String id = createOrder(channel, amount).path("id").asText();
        simulatePay(id);
        awaitStatus(id, "PAID");
        return id;
    }

    private ResponseEntity<JsonNode> refund(String id, String amount) {
        return api.postForEntity("/api/orders/" + id + "/refunds", json(Map.of("amount", amount, "reason", "test")),
                JsonNode.class);
    }
}
