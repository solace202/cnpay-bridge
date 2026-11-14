package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.order.PendingOrderPoller;
import dev.cnpay.provider.Provider;
import dev.cnpay.sandbox.SandboxNotifier;
import dev.cnpay.sandbox.SandboxService;
import dev.cnpay.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PollerIT extends IntegrationTest {

    @Autowired
    PendingOrderPoller poller;
    @Autowired
    SandboxNotifier notifier;
    @Autowired
    SandboxService sandbox;

    @Test
    void lostNotificationIsRecoveredByActiveQuery() throws Exception {
        JsonNode order = createOrder("WECHAT_NATIVE", "42.00");
        String otn = order.path("outTradeNo").asText();
        notifier.muteNext(1);
        sandbox.pay(Provider.WECHAT, otn);
        Thread.sleep(300);
        assertThat(order(order.path("id").asText()).path("status").asText()).isEqualTo("PENDING");

        poller.sweep();

        assertThat(order(order.path("id").asText()).path("status").asText()).isEqualTo("PAID");
        assertThat(eventResult(otn, "QUERY.TRADE_SUCCESS")).isEqualTo("APPLIED");
    }

    @Test
    void expiredUnpaidOrdersAreClosedOnBothSides() {
        JsonNode order = createOrder("ALIPAY_QR", "8.00");
        String id = order.path("id").asText();
        jdbc.sql("update orders set expires_at = now(3) - interval 1 minute where id = ?").param(id).update();

        poller.sweep();

        assertThat(order(id).path("status").asText()).isEqualTo("CLOSED");
        assertThat(sandbox.find(Provider.ALIPAY, order.path("outTradeNo").asText()).orElseThrow().state()).isEqualTo("CLOSED");
        assertThat(eventResult(order.path("outTradeNo").asText(), "ORDER.EXPIRED")).isEqualTo("APPLIED");
    }
}
