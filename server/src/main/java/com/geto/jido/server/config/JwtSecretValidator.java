package com.geto.jido.server.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Fails startup when the JWT signing key is missing or too short.
 *
 * <p>Without this, a deployment that forgot to set JWT_SECRET would boot
 * happily and sign tokens with an empty key — anyone could mint a valid token.
 * A hard startup failure is the safer failure mode.
 */
@Configuration
public class JwtSecretValidator {

    private final JwtProperties properties;

    public JwtSecretValidator(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        if (!properties.isSecretUsable()) {
            throw new IllegalStateException(
                    "jido.security.jwt.secret must be set to at least "
                            + JwtProperties.MIN_SECRET_BYTES
                            + " bytes. Generate one with: openssl rand -base64 48");
        }
    }
}
