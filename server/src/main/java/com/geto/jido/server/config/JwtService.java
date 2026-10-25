package com.geto.jido.server.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the HS256 access tokens.
 *
 * <p>Deliberately does no caching: verification is a local HMAC and a parse,
 * and a cache would only add a second thing to invalidate on key rotation.
 */
@Service
public class JwtService {

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issue(UserSubject subject) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenTtl());
        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(subject.userId().toString())
                .claim("email", subject.email())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiry);
    }

    /**
     * @return the verified claims, or empty when the token is malformed,
     *     unsigned by this key, expired, or from another issuer. Callers treat
     *     all four the same: unauthenticated.
     */
    public java.util.Optional<UserSubject> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return java.util.Optional.of(new UserSubject(
                    UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {}

    public record UserSubject(UUID userId, String email) {}
}
