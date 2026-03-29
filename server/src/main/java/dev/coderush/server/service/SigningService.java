package dev.coderush.server.service;

import dev.coderush.common.crypto.SigningUtil;
import dev.coderush.server.config.CodeRushProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

@Service
public class SigningService {

    private static final Logger log = LoggerFactory.getLogger(SigningService.class);
    private static final String PUBLIC_KEY_FILE = "public.key";
    private static final String PRIVATE_KEY_FILE = "private.key";

    private final Path keysDir;
    private PublicKey publicKey;
    private PrivateKey privateKey;

    public SigningService(CodeRushProperties properties) {
        this.keysDir = Path.of(properties.keysDir());
    }

    public void initialize() {
        var publicKeyPath = keysDir.resolve(PUBLIC_KEY_FILE);
        var privateKeyPath = keysDir.resolve(PRIVATE_KEY_FILE);

        if (Files.exists(publicKeyPath) && Files.exists(privateKeyPath)) {
            loadKeyPair(publicKeyPath, privateKeyPath);
            log.info("Loaded existing Ed25519 key pair from {}", keysDir);
        } else {
            generateAndStoreKeyPair(publicKeyPath, privateKeyPath);
            log.info("Generated new Ed25519 key pair in {}", keysDir);
        }
    }

    private void loadKeyPair(Path publicKeyPath, Path privateKeyPath) {
        try {
            publicKey = SigningUtil.decodePublicKey(Files.readString(publicKeyPath).trim());
            privateKey = SigningUtil.decodePrivateKey(Files.readString(privateKeyPath).trim());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load key pair from " + keysDir, e);
        }
    }

    private void generateAndStoreKeyPair(Path publicKeyPath, Path privateKeyPath) {
        try {
            Files.createDirectories(keysDir);
            KeyPair keyPair = SigningUtil.generateKeyPair();
            publicKey = keyPair.getPublic();
            privateKey = keyPair.getPrivate();
            Files.writeString(publicKeyPath, SigningUtil.encodePublicKey(publicKey));
            Files.writeString(privateKeyPath, SigningUtil.encodePrivateKey(privateKey));
        } catch (IOException e) {
            throw new RuntimeException("Failed to store key pair in " + keysDir, e);
        }
    }

    public String sign(String payload) {
        return SigningUtil.sign(payload, privateKey);
    }

    public boolean verify(String payload, String signature) {
        return SigningUtil.verify(payload, signature, publicKey);
    }

    public String getPublicKeyBase64() {
        return SigningUtil.encodePublicKey(publicKey);
    }
}
