package dev.cnpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.wechat.pay.java.core.cipher.AeadAesCipher;
import com.wechat.pay.java.core.cipher.RSAVerifier;
import dev.cnpay.crypto.Keys;
import dev.cnpay.crypto.WechatPayCrypto;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;

/** The sandbox's hand-written platform crypto must be byte-compatible with the official SDK. */
class WechatPayCryptoTest {

    private static final String API_V3_KEY = "0123456789abcdefghijABCDEFGHIJ01";

    @Test
    void aesGcmResourceDecryptsWithTheSdkCipher() {
        String ciphertext = WechatPayCrypto.encryptResource(API_V3_KEY, "transaction", "nonce1234567", "{\"x\":\"支付\"}");
        String plain = new AeadAesCipher(API_V3_KEY.getBytes(StandardCharsets.UTF_8))
                .decrypt("transaction".getBytes(StandardCharsets.UTF_8), "nonce1234567".getBytes(StandardCharsets.UTF_8),
                        java.util.Base64.getDecoder().decode(ciphertext));
        assertThat(plain).isEqualTo("{\"x\":\"支付\"}");
        assertThat(WechatPayCrypto.decryptResource(API_V3_KEY, "transaction", "nonce1234567", ciphertext))
                .isEqualTo(plain);
    }

    @Test
    void signaturesVerifyWithTheSdkVerifierAndOnlyForTheRightSerial() {
        KeyPair platform = Keys.generateRsa2048();
        String message = WechatPayCrypto.message("1700000000", "NONCE", "{\"a\":1}");
        String signature = WechatPayCrypto.sign(platform.getPrivate(), message);
        RSAVerifier verifier = new RSAVerifier(platform.getPublic(), "PUB_KEY_ID_01TEST");

        assertThat(verifier.verify("PUB_KEY_ID_01TEST", message, signature)).isTrue();
        assertThat(verifier.verify("PUB_KEY_ID_01OTHER", message, signature)).isFalse();
        assertThat(verifier.verify("PUB_KEY_ID_01TEST", message.replace("1}", "2}"), signature)).isFalse();
    }

    @Test
    void pemAndBareBase64KeysRoundTrip() {
        KeyPair pair = Keys.generateRsa2048();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Keys.base64(pair.getPrivate()) + "\n-----END PRIVATE KEY-----\n";
        assertThat(Keys.privateKey(pem)).isEqualTo(pair.getPrivate());
        assertThat(Keys.publicKey(Keys.base64(pair.getPublic()))).isEqualTo(pair.getPublic());
    }
}
