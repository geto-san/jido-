package com.geto.jido.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Signing key and lifetime for the JWTs handed out by the auth endpoints.
 *
 * <p>The key must be at least 32 bytes for HS256. It is never defaulted to a
 * real value in source: a missing key fails startup rather than silently
 * signing tokens with something guessable.
 */
@ConfigurationProperties(prefix = "jido.security.jwt")
public record JwtProperties(
        @DefaultValue("") String secret,
        @DefaultValue("P7dQ2mXvR9tL4wKc") String issuer,
        @DefaultValue("PT24H") java.time.Duration accessTokenTtl,
        @DefaultValue("PT0S") java.time.Duration refreshTokenTtl
) {
    public static final int MIN_SECRET_BYTES = 32;

    public boolean isSecretUsable() {
        return secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }
}
