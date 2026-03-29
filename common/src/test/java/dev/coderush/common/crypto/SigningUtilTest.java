package dev.coderush.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SigningUtilTest {

    @Test
    void generateKeyPairProducesNonNullKeys() {
        var keyPair = SigningUtil.generateKeyPair();
        assertNotNull(keyPair.getPublic());
        assertNotNull(keyPair.getPrivate());
    }

    @Test
    void signAndVerifyRoundTrip() {
        var keyPair = SigningUtil.generateKeyPair();
        var payload = "{\"buildId\":1,\"repo\":\"git@github.com:org/repo.git\"}";

        var signature = SigningUtil.sign(payload, keyPair.getPrivate());

        assertNotNull(signature);
        assertTrue(SigningUtil.verify(payload, signature, keyPair.getPublic()));
    }

    @Test
    void verifyFailsWithWrongKey() {
        var keyPair1 = SigningUtil.generateKeyPair();
        var keyPair2 = SigningUtil.generateKeyPair();
        var payload = "some payload";

        var signature = SigningUtil.sign(payload, keyPair1.getPrivate());

        assertFalse(SigningUtil.verify(payload, signature, keyPair2.getPublic()));
    }

    @Test
    void verifyFailsWithTamperedPayload() {
        var keyPair = SigningUtil.generateKeyPair();
        var payload = "original payload";

        var signature = SigningUtil.sign(payload, keyPair.getPrivate());

        assertFalse(SigningUtil.verify("tampered payload", signature, keyPair.getPublic()));
    }

    @Test
    void keyEncodingRoundTrip() {
        var keyPair = SigningUtil.generateKeyPair();

        var publicBase64 = SigningUtil.encodePublicKey(keyPair.getPublic());
        var privateBase64 = SigningUtil.encodePrivateKey(keyPair.getPrivate());

        var restoredPublic = SigningUtil.decodePublicKey(publicBase64);
        var restoredPrivate = SigningUtil.decodePrivateKey(privateBase64);

        // Sign with restored private key, verify with restored public key
        var payload = "round trip test";
        var signature = SigningUtil.sign(payload, restoredPrivate);
        assertTrue(SigningUtil.verify(payload, signature, restoredPublic));
    }

    @Test
    void verifyFailsWithInvalidSignature() {
        var keyPair = SigningUtil.generateKeyPair();
        var payload = "test";

        assertFalse(SigningUtil.verify(payload, "notavalidsignature!!!", keyPair.getPublic()));
    }
}
