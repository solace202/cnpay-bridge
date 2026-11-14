package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.provider.Provider;
import dev.cnpay.recon.Difference;
import dev.cnpay.recon.Reconciliation;
import dev.cnpay.recon.ReconciliationItem;
import dev.cnpay.recon.ReconciliationService;
import dev.cnpay.sandbox.SandboxNotifier;
import dev.cnpay.sandbox.SandboxService;
import dev.cnpay.support.IntegrationTest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationIT extends IntegrationTest {

    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    SandboxService sandbox;
    @Autowired
    SandboxNotifier notifier;
    @Autowired
    Clock clock;

    @Test
    void wechatBillFindsAndRepairsDifferences() throws Exception {
        String matched = paid("WECHAT_NATIVE", "10.00");
        JsonNode lost = createOrder("WECHAT_NATIVE", "20.00");
        notifier.muteNext(1);
        sandbox.pay(Provider.WECHAT, lost.path("outTradeNo").asText());
        Thread.sleep(300);
        var orphan = sandbox.orphanPayment(Provider.WECHAT, 4500);
        String tampered = paid("WECHAT_NATIVE", "30.00");
        sandbox.tamperAmount(Provider.WECHAT, otn(tampered));

        Reconciliation run = reconciliation.run(Provider.WECHAT, LocalDate.now(clock));
        Map<String, ReconciliationItem> items = byTrade(reconciliation.items(run.id()));

        assertThat(items).doesNotContainKey(otn(matched));
        assertThat(items.get(lost.path("outTradeNo").asText()).kind()).isEqualTo(Difference.Kind.STATUS_MISMATCH);
        assertThat(items.get(lost.path("outTradeNo").asText()).action()).startsWith("Repaired");
        assertThat(order(lost.path("id").asText()).path("status").asText()).isEqualTo("PAID");
        assertThat(items.get(orphan.outTradeNo()).kind()).isEqualTo(Difference.Kind.MISSING_LOCAL);
        assertThat(items.get(otn(tampered)).kind()).isEqualTo(Difference.Kind.AMOUNT_MISMATCH);
        assertThat(run.repaired()).isGreaterThanOrEqualTo(1);
        assertThat(run.status()).isEqualTo("DIFFERENCES");
    }

    @Test
    void alipayZipBillMatchesPaymentsAndRefunds() {
        String id = paid("ALIPAY_QR", "77.00");
        api.postForEntity("/api/orders/" + id + "/refunds", json(Map.of("amount", "7.00")), JsonNode.class);

        Reconciliation run = reconciliation.run(Provider.ALIPAY, LocalDate.now(clock));
        Map<String, ReconciliationItem> items = byTrade(reconciliation.items(run.id()));

        assertThat(items).doesNotContainKey(otn(id));
        assertThat(run.matched()).isPositive();
        assertThat(run.remoteCount()).isGreaterThanOrEqualTo(run.matched());
    }

    private String paid(String channel, String amount) {
        String id = createOrder(channel, amount).path("id").asText();
        simulatePay(id);
        awaitStatus(id, "PAID");
        return id;
    }

    private String otn(String id) {
        return order(id).path("outTradeNo").asText();
    }

    private static Map<String, ReconciliationItem> byTrade(List<ReconciliationItem> items) {
        Map<String, ReconciliationItem> m = new java.util.HashMap<>();
        items.forEach(i -> m.put(i.outTradeNo(), i));
        return m;
    }
}
