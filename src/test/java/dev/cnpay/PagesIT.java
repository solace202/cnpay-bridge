package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cnpay.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class PagesIT extends IntegrationTest {

    @Test
    void checkoutPageRendersAQrCodeAndExposesOnlyStatus() {
        JsonNode order = createOrder("WECHAT_NATIVE", "88.00");
        String page = rest.getForObject("/pay/" + order.path("id").asText(), String.class);
        assertThat(page).contains("<svg").contains("88.00").contains("Simulate scan");
        assertThat(rest.getForObject("/pay/" + order.path("id").asText() + "/status", String.class))
                .contains("PENDING").doesNotContain("amount");
    }

    @Test
    void adminPagesNeedAuthAndRender() {
        assertThat(rest.getForEntity("/admin", String.class).getStatusCode().value()).isEqualTo(401);
        for (String path : new String[] {"/admin", "/admin/events", "/admin/reconciliations"}) {
            var r = api.getForEntity(path, String.class);
            assertThat(r.getStatusCode().value()).as(path).isEqualTo(200);
        }
        assertThat(rest.getForEntity("/", String.class).getBody()).contains("Matcha starter kit");
        assertThat(rest.getForEntity("/v3/api-docs", String.class).getBody()).contains("/api/orders");
    }
}
