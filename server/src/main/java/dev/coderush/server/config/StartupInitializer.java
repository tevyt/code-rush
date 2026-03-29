package dev.coderush.server.config;

import dev.coderush.server.model.User;
import dev.coderush.server.repository.UserRepository;
import dev.coderush.server.service.SigningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SigningService signingService;

    public StartupInitializer(UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              SigningService signingService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.signingService = signingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        signingService.initialize();

        if (userRepository.count() == 0) {
            var admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin"));
            admin.setRole(User.Role.ADMIN);
            userRepository.save(admin);
            log.info("Created default admin user");
        }
    }
}
