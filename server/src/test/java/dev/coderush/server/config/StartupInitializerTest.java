package dev.coderush.server.config;

import dev.coderush.server.model.User;
import dev.coderush.server.repository.UserRepository;
import dev.coderush.server.service.SigningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class StartupInitializerTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SigningService signingService;

    @Autowired
    private CodeRushProperties properties;

    @Test
    void firstStartupCreatesAdminUser() {
        Optional<User> admin = userRepository.findByUsername("admin");
        assertTrue(admin.isPresent());
        assertEquals(User.Role.ADMIN, admin.get().getRole());
    }

    @Test
    void firstStartupGeneratesKeyPair() {
        Path keysDir = Path.of(properties.keysDir());
        assertTrue(Files.exists(keysDir.resolve("public.key")));
        assertTrue(Files.exists(keysDir.resolve("private.key")));
    }

    @Test
    void signingServiceIsOperational() {
        String payload = "test payload";
        String signature = signingService.sign(payload);
        assertTrue(signingService.verify(payload, signature));
    }

    @Test
    void publicKeyIsAvailable() {
        String publicKey = signingService.getPublicKeyBase64();
        assertNotNull(publicKey);
        assertFalse(publicKey.isBlank());
    }
}
