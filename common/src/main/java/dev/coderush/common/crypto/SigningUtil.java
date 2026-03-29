package dev.coderush.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class SigningUtil {

    private static final String ALGORITHM = "Ed25519";

    private SigningUtil() {}

    public static KeyPair generateKeyPair() {
        try {
            var generator = KeyPairGenerator.getInstance(ALGORITHM);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate Ed25519 key pair", e);
        }
    }

    public static String sign(String payload, PrivateKey privateKey) {
        try {
            var signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign payload", e);
        }
    }

    public static boolean verify(String payload, String signatureBase64, PublicKey publicKey) {
        try {
            var signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (Exception e) {
            return false;
        }
    }

    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static String encodePrivateKey(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    public static PublicKey decodePublicKey(String base64) {
        try {
            var keyBytes = Base64.getDecoder().decode(base64);
            var keySpec = new X509EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(ALGORITHM).generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode public key", e);
        }
    }

    public static PrivateKey decodePrivateKey(String base64) {
        try {
            var keyBytes = Base64.getDecoder().decode(base64);
            var keySpec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode private key", e);
        }
    }
}
