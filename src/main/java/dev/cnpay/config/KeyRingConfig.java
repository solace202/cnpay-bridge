package dev.cnpay.config;

import dev.cnpay.crypto.KeyRing;
import dev.cnpay.crypto.Keys;
import dev.cnpay.crypto.WechatPayCrypto;
import java.security.KeyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KeyRingConfig {

    private static final Logger log = LoggerFactory.getLogger(KeyRingConfig.class);

    @Bean
    KeyRing keyRing(CnpayProperties properties) {
        return properties.sandboxMode() ? sandboxKeys() : liveKeys(properties);
    }

    private static KeyRing sandboxKeys() {
        KeyPair merchant = Keys.generateRsa2048();
        KeyPair wechatPlatform = Keys.generateRsa2048();
        KeyPair alipayApp = Keys.generateRsa2048();
        KeyPair alipay = Keys.generateRsa2048();
        log.info("Sandbox mode: generated throw-away RSA-2048 keys for merchant, WeChat Pay and Alipay");
        return new KeyRing(
                merchant.getPrivate(), WechatPayCrypto.hex(40),
                wechatPlatform.getPublic(), "PUB_KEY_ID_01" + WechatPayCrypto.hex(26),
                WechatPayCrypto.nonce(32),
                alipayApp.getPrivate(), alipay.getPublic(),
                new KeyRing.Sandbox(merchant.getPublic(), wechatPlatform.getPrivate(),
                        alipayApp.getPublic(), alipay.getPrivate()));
    }

    private static KeyRing liveKeys(CnpayProperties properties) {
        CnpayProperties.Wechat wx = properties.wechat();
        CnpayProperties.Alipay ali = properties.alipay();
        require(wx.merchantSerialNumber(), "cnpay.wechat.merchant-serial-number");
        require(wx.platformPublicKeyId(), "cnpay.wechat.platform-public-key-id");
        require(wx.apiV3Key(), "cnpay.wechat.api-v3-key");
        return new KeyRing(
                Keys.privateKey(wx.privateKey()), wx.merchantSerialNumber(),
                Keys.publicKey(wx.platformPublicKey()), wx.platformPublicKeyId(), wx.apiV3Key(),
                Keys.privateKey(ali.privateKey()), Keys.publicKey(ali.alipayPublicKey()),
                null);
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when cnpay.mode=live");
        }
    }
}
