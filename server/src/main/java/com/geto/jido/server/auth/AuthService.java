package com.geto.jido.server.auth;

import com.geto.jido.server.config.JwtProperties;
import com.geto.jido.server.config.JwtService;
import com.geto.jido.server.user.User;
import com.geto.jido.server.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, and refresh.
 *
 * <p>Failure modes are deliberately indistinguishable from the outside: bad
 * email and bad password both raise {@code InvalidCredentials}, so the
 * endpoint cannot be used to enumerate registered addresses.
 */
@Service
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final int BCRYPT_MIN_COST = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public TokenPair register(String email, String password, String displayName) {
        String normalised = normaliseEmail(email);
        if (users.existsByEmailIgnoreCase(normalised)) {
            // Registration is the one place a duplicate must be reported, or
            // the client cannot distinguish "taken" from "server error".
            throw new EmailAlreadyRegistered();
        }
        User user = new User(
                normalised, passwordEncoder.encode(password), blankToNull(displayName));
        users.save(user);
        return issuePair(user);
    }

    @Transactional
    public TokenPair login(String email, String password) {
        User user = users.findByEmailIgnoreCase(normaliseEmail(email))
                .filter(User::isEnabled)
                .orElseThrow(InvalidCredentials::new);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new InvalidCredentials();
        }
        return issuePair(user);
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the old one.
     *
     * <p>Rotation is what makes a stolen refresh token detectable: if a client
     * refreshes twice with the same token, the second attempt finds it
     * revoked and the whole family is dropped.
     */
    @Transactional
    public TokenPair refresh(String presentedToken) {
        Instant now = Instant.now();
        RefreshToken stored = refreshTokens.findByTokenHash(hash(presentedToken))
                .orElseThrow(InvalidCredentials::new);
        if (!stored.isUsable(now)) {
            refreshTokens.revokeAllForUser(stored.getUserId(), now);
            throw new InvalidCredentials();
        }
        User user = users.findById(stored.getUserId()).filter(User::isEnabled)
                .orElseThrow(InvalidCredentials::new);
        stored.revoke(now);
        return issuePair(user);
    }

    @Transactional
    public void logout(String presentedToken) {
        refreshTokens.findByTokenHash(hash(presentedToken))
                .ifPresent(token -> token.revoke(Instant.now()));
    }

    private TokenPair issuePair(User user) {
        var access = jwtService.issue(new JwtService.UserSubject(user.getId(), user.getEmail()));
        String refresh = newRefreshToken();
        Instant expiry = Instant.now().plus(jwtProperties.refreshTokenTtl());
        refreshTokens.save(new RefreshToken(user.getId(), hash(refresh), expiry));
        return new TokenPair(access.value(), access.expiresAt(), refresh, expiry);
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }

    private static String normaliseEmail(String email) {
        return email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record TokenPair(
            String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {}

    /** Bad email or bad password — the client must not be able to tell them apart. */
    public static class InvalidCredentials extends RuntimeException {
        public InvalidCredentials() {
            super("Invalid email or password");
        }
    }

    public static class EmailAlreadyRegistered extends RuntimeException {
        public EmailAlreadyRegistered() {
            super("Email is already registered");
        }
    }
}
