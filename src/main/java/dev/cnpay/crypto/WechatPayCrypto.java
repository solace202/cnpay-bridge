package dev.cnpay.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The primitives of WeChat Pay API v3, written out so the sandbox can play the platform side:
 * SHA256withRSA signatures over newline-joined messages and AEAD_AES_256_GCM for callback resources.
 * The merchant side uses the official SDK; tests cross-check the two.
 */
public final class WechatPayCrypto {

    public static final String SCHEMA = "WECHATPAY2-SHA256-RSA2048";
    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private WechatPayCrypto() {
    }

    /** Each part is followed by "\n", e.g. timestamp\nnonce\nbody\n for responses and callbacks. */
    public static String message(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part == null ? "" : part).append('\n');
        }
        return sb.toString();
    }

    public static String sign(PrivateKey key, String message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(PublicKey key, String message, String base64Signature) {
        if (base64Signature == null) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(base64Signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static String encryptResource(String apiV3Key, String associatedData, String nonce, String plaintext) {
        try {
            Cipher cipher = gcm(Cipher.ENCRYPT_MODE, apiV3Key, nonce);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decryptResource(String apiV3Key, String associatedData, String nonce, String ciphertext) {
        try {
            Cipher cipher = gcm(Cipher.DECRYPT_MODE, apiV3Key, nonce);
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Resource decryption failed", e);
        }
    }

    private static Cipher gcm(int mode, String apiV3Key, String nonce) throws GeneralSecurityException {
        byte[] key = apiV3Key.getBytes(StandardCharsets.UTF_8);
        if (key.length != 32) {
            throw new IllegalArgumentException("APIv3 key must be 32 bytes");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        return cipher;
    }

    public static String nonce(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }

    public static String hex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return sb.toString().toUpperCase();
    }
}
