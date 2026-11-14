package dev.cnpay.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All settings live under {@code cnpay.*}. In {@code sandbox} mode every key is generated at start-up and
 * both SDKs talk to the built-in sandbox; in {@code live} mode the merchant keys below are required.
 */
@ConfigurationProperties("cnpay")
public record CnpayProperties(
        @DefaultValue("sandbox") Mode mode,
        @DefaultValue("http://localhost:8091") String publicUrl,
        @DefaultValue("30m") Duration orderTtl,
        @DefaultValue Wechat wechat,
        @DefaultValue Alipay alipay,
        @DefaultValue Sandbox sandbox,
        @DefaultValue Poller poller) {

    public enum Mode { SANDBOX, LIVE }

    public boolean sandboxMode() {
        return mode == Mode.SANDBOX;
    }

    /**
     * WeChat Pay API v3, authenticated with the "WeChat Pay public key" scheme (公钥模式).
     * Keys accept PEM text, a bare base64 body, or a file path.
     */
    public record Wechat(
            @DefaultValue("1900000001") String mchId,
            @DefaultValue("wx5f1e9c0d2a7b3c4d") String appId,
            String merchantSerialNumber,
            String privateKey,
            String platformPublicKey,
            String platformPublicKeyId,
            String apiV3Key) {
    }

    /** Alipay OpenAPI with RSA2 public-key signing (公钥模式). */
    public record Alipay(
            @DefaultValue("2021000000000001") String appId,
            String privateKey,
            String alipayPublicKey,
            @DefaultValue("https://openapi.alipay.com/gateway.do") String gateway) {
    }

    public record Sandbox(
            @DefaultValue("1s") Duration notifyDelay,
            @DefaultValue("3") int notifyAttempts,
            @DefaultValue("2s") Duration notifyRetryBackoff) {
    }

    /** Safety net for lost notifications: query pending orders and close expired ones. */
    public record Poller(
            @DefaultValue("60s") Duration minAge,
            @DefaultValue("50") int batchSize) {
    }
}
