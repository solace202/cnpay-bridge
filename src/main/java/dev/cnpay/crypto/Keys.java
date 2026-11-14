package dev.cnpay.crypto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads RSA keys from PEM text, a bare base64 body (Alipay's format) or a file path. */
public final class Keys {

    private Keys() {
    }

    public static KeyPair generateRsa2048() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PrivateKey privateKey(String pemOrBase64OrPath) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(pemOrBase64OrPath)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a PKCS#8 RSA private key", e);
        }
    }

    public static PublicKey publicKey(String pemOrBase64OrPath) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pemOrBase64OrPath)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an X.509 RSA public key", e);
        }
    }

    public static String base64(java.security.Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static byte[] der(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("key is not configured");
        }
        String text = value.trim();
        if (!text.contains("-----BEGIN") && !looksLikeBase64(text)) {
            try {
                text = Files.readString(Path.of(text));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read key file " + value, e);
            }
        }
        String body = text.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static boolean looksLikeBase64(String text) {
        return text.length() > 200 && text.replaceAll("\\s", "").matches("[A-Za-z0-9+/=]+");
    }
}
