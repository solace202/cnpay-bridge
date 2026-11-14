package dev.cnpay.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code cnpay.public-url} lazily so that placeholders such as {@code ${local.server.port}}
 * work in tests that start on a random port.
 */
@Component
public class PublicUrls {

    private final Environment environment;
    private final CnpayProperties properties;

    public PublicUrls(Environment environment, CnpayProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    public String base() {
        String url = environment.resolveRequiredPlaceholders(properties.publicUrl());
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String wechatNotifyUrl() {
        return base() + "/notify/wechat";
    }

    public String alipayNotifyUrl() {
        return base() + "/notify/alipay";
    }

    public String checkoutUrl(String orderId) {
        return base() + "/pay/" + orderId;
    }

    public String sandboxBase() {
        return base() + "/sandbox";
    }
}
