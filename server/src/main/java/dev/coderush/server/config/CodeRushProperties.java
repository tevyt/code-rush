package dev.coderush.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "code-rush")
public record CodeRushProperties(
    String secret,
    String keysDir,
    Duration buildTimeout,
    Duration stepTimeout,
    String serverHost,
    JwtProperties jwt
) {
    public record JwtProperties(
        String secret,
        Duration accessTokenExpiry,
        Duration refreshTokenExpiry
    ) {}
}
