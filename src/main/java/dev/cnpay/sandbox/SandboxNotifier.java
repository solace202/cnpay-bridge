package dev.cnpay.sandbox;

import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.Keys;
import dev.cnpay.crypto.WechatPayCrypto;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Delivers notifications the way the real platforms do: WeChat Pay posts an encrypted, signed JSON envelope;
 * Alipay posts a signed form. Delivery is asynchronous, retried on failure, and can be muted to simulate a
 * merchant that missed every retry.
 */
@Component
@ConditionalOnProperty(name = "cnpay.mode", havingValue = "sandbox", matchIfMissing = true)
public class SandboxNotifier {

    private static final Logger log = LoggerFactory.getLogger(SandboxNotifier.class);

    private final KeyRing keys;
    private final CnpayProperties properties;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.create();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "sandbox-notify");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger muted = new AtomicInteger();

    public SandboxNotifier(KeyRing keys, CnpayProperties properties, ObjectMapper mapper) {
        this.keys = keys;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** The next {@code count} notifications are silently dropped (the poller / reconciliation must catch them). */
    public void muteNext(int count) {
        muted.addAndGet(count);
    }

    public int mutedCount() {
        return muted.get();
    }

    // ------------------------------------------------------------- WeChat Pay

    /** Builds the exact envelope + headers WeChat Pay sends. Public so tests can craft and tamper with them. */
    public SignedWechatNotification wechatNotification(String eventType, String originalType, String summary,
                                                       Map<String, Object> resource) {
        String plaintext = json(resource);
        String nonce = WechatPayCrypto.nonce(12);
        String associatedData = originalType;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", UUID.randomUUID().toString());
        envelope.put("create_time", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        envelope.put("resource_type", "encrypt-resource");
        envelope.put("event_type", eventType);
        envelope.put("summary", summary);
        envelope.put("resource", Map.of(
                "original_type", originalType,
                "algorithm", "AEAD_AES_256_GCM",
                "ciphertext", WechatPayCrypto.encryptResource(keys.wechatApiV3Key(), associatedData, nonce, plaintext),
                "associated_data", associatedData,
                "nonce", nonce));
        String body = json(envelope);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String headerNonce = WechatPayCrypto.nonce(32);
        String signature = WechatPayCrypto.sign(keys.sandbox().wechatPlatformPrivateKey(),
                WechatPayCrypto.message(timestamp, headerNonce, body));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Wechatpay-Serial", keys.wechatPlatformPublicKeyId());
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", headerNonce);
        headers.put("Wechatpay-Signature", signature);
        headers.put("Wechatpay-Signature-Type", WechatPayCrypto.SCHEMA);
        return new SignedWechatNotification((String) envelope.get("id"), headers, body);
    }

    public record SignedWechatNotification(String id, Map<String, String> headers, String body) {
    }

    public void sendWechat(String notifyUrl, SignedWechatNotification n) {
        schedule("WeChat Pay " + n.id(), () -> {
            var response = http.post().uri(notifyUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> n.headers().forEach(h::add))
                    .body(n.body())
                    .retrieve().toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        });
    }

    // ------------------------------------------------------------- Alipay

    /** Signs form params like Alipay: sorted key=value pairs excluding sign and sign_type, RSA2. */
    public Map<String, String> signAlipay(Map<String, String> params) {
        Map<String, String> signed = new LinkedHashMap<>(params);
        signed.put("sign_type", "RSA2");
        String content = AlipaySignature.getSignCheckContentV1(signed);
        try {
            signed.put("sign", AlipaySignature.rsa256Sign(content, Keys.base64(keys.sandbox().alipayPrivateKey()), "UTF-8"));
        } catch (com.alipay.api.AlipayApiException e) {
            throw new IllegalStateException(e);
        }
        return signed;
    }

    public void sendAlipay(String notifyUrl, Map<String, String> params) {
        Map<String, String> signed = signAlipay(params);
        schedule("Alipay " + params.get("notify_id"), () -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            signed.forEach(form::add);
            String body = http.post().uri(notifyUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(String.class);
            return "success".equals(body == null ? null : body.trim());
        });
    }

    // ------------------------------------------------------------- delivery

    private interface Attempt {
        boolean deliver() throws Exception;
    }

    private void schedule(String label, Attempt attempt) {
        if (muted.get() > 0 && muted.decrementAndGet() >= 0) {
            log.warn("Sandbox: dropping notification {} (muted)", label);
            return;
        }
        CnpayProperties.Sandbox cfg = properties.sandbox();
        executor.schedule(() -> deliver(label, attempt, 1, cfg), cfg.notifyDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void deliver(String label, Attempt attempt, int n, CnpayProperties.Sandbox cfg) {
        boolean ok;
        try {
            ok = attempt.deliver();
        } catch (Exception e) {
            ok = false;
            log.info("Sandbox notification {} attempt {} failed: {}", label, n, e.getMessage());
        }
        if (!ok && n < cfg.notifyAttempts()) {
            Duration backoff = cfg.notifyRetryBackoff().multipliedBy(n);
            executor.schedule(() -> deliver(label, attempt, n + 1, cfg), backoff.toMillis(), TimeUnit.MILLISECONDS);
        } else if (!ok) {
            log.warn("Sandbox notification {} gave up after {} attempts", label, n);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
