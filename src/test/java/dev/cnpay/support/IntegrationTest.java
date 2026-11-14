package dev.cnpay.support;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Full application on a random port against MySQL 8.4. The official SDKs talk over real HTTP to the built-in
 * sandbox, which calls our /notify endpoints back — the same wire path as production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "cnpay.public-url=http://localhost:${local.server.port}",
        "cnpay.scheduling.enabled=false",
        "cnpay.sandbox.notify-delay=50ms",
        "cnpay.sandbox.notify-retry-backoff=100ms",
        "cnpay.poller.min-age=0s",
        "logging.level.dev.cnpay=warn"
})
public abstract class IntegrationTest {

    /** One container for the whole run (shared by the cached Spring context); Ryuk removes it at JVM exit. */
    @ServiceConnection
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("cnpay")
            .withUrlParam("connectionTimeZone", "Asia/Shanghai")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("characterEncoding", "UTF-8");

    static {
        MYSQL.start();
    }

    @LocalServerPort
    protected int port;
    @Autowired
    protected TestRestTemplate rest;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected JdbcClient jdbc;

    protected TestRestTemplate api;

    @BeforeEach
    void authenticate() {
        api = rest.withBasicAuth("admin", "change-me");
    }

    protected JsonNode createOrder(String channel, String amount) {
        return createOrder(channel, amount, Map.of());
    }

    protected JsonNode createOrder(String channel, String amount, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "merchantOrderNo", "T-" + UUID.randomUUID().toString().substring(0, 18),
                "channel", channel,
                "amount", amount,
                "description", "Test item 测试商品"));
        body.putAll(extra);
        ResponseEntity<JsonNode> r = api.postForEntity("/api/orders", json(body), JsonNode.class);
        if (r.getStatusCode().value() != 201) {
            throw new AssertionError("Create failed: " + r.getStatusCode() + " " + r.getBody());
        }
        return r.getBody();
    }

    protected JsonNode order(String id) {
        return api.getForObject("/api/orders/" + id, JsonNode.class);
    }

    protected JsonNode awaitStatus(String id, String status) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50))
                .until(() -> status.equals(order(id).path("status").asText()));
        return order(id);
    }

    protected void simulatePay(String id) {
        rest.postForEntity("/pay/" + id + "/simulate", null, String.class);
    }

    protected HttpEntity<Object> json(Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    protected String eventResult(String outTradeNo, String eventType) {
        return jdbc.sql("select result from payment_events where out_trade_no = ? and event_type = ? order by id limit 1")
                .params(outTradeNo, eventType).query(String.class).optional().orElse(null);
    }
}
