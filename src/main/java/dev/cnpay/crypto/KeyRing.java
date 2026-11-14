package dev.cnpay.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Every key the bridge uses. In sandbox mode all of them are generated at start-up, including the
 * "platform" halves that only WeChat Pay / Alipay would normally hold; in live mode those are {@code null}.
 */
public record KeyRing(
        PrivateKey wechatMerchantPrivateKey,
        String wechatMerchantSerialNumber,
        PublicKey wechatPlatformPublicKey,
        String wechatPlatformPublicKeyId,
        String wechatApiV3Key,
        PrivateKey alipayAppPrivateKey,
        PublicKey alipayPublicKey,
        Sandbox sandbox) {

    /** The halves held by the providers; only present when the built-in sandbox plays them. */
    public record Sandbox(
            PublicKey wechatMerchantPublicKey,
            PrivateKey wechatPlatformPrivateKey,
            PublicKey alipayAppPublicKey,
            PrivateKey alipayPrivateKey) {
    }
}
