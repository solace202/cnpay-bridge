package dev.cnpay.provider.alipay;

import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import dev.cnpay.config.CnpayProperties;
import dev.cnpay.config.PublicUrls;
import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.Keys;
import org.springframework.stereotype.Component;

/**
 * The official alipay-sdk-java client in RSA2 public-key mode. In sandbox mode only the gateway URL changes;
 * request signing and response signature verification are the SDK's own.
 */
@Component
public class AlipayClients {

    private final CnpayProperties properties;
    private final KeyRing keys;
    private final PublicUrls urls;
    private volatile AlipayClient client;

    public AlipayClients(CnpayProperties properties, KeyRing keys, PublicUrls urls) {
        this.properties = properties;
        this.keys = keys;
        this.urls = urls;
    }

    public AlipayClient get() {
        AlipayClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = new DefaultAlipayClient(gateway(), properties.alipay().appId(),
                            Keys.base64(keys.alipayAppPrivateKey()), "json", "UTF-8",
                            alipayPublicKeyBase64(), "RSA2");
                }
                c = client;
            }
        }
        return c;
    }

    public String gateway() {
        return properties.sandboxMode() ? urls.sandboxBase() + "/alipay/gateway.do" : properties.alipay().gateway();
    }

    public String alipayPublicKeyBase64() {
        return Keys.base64(keys.alipayPublicKey());
    }
}
