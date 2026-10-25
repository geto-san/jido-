package com.geto.jido.server.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * BCrypt truncates at 72 bytes, so anything longer is silently ignored
     * past that point — meaning only the first 72 bytes would actually
     * matter. Cap it rather than let users believe the tail counts.
     */
    private static final int PASSWORD_MAX = 72;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    ResponseEntity<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthService.TokenPair pair =
                authService.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(TokenResponse.from(pair));
    }

    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return TokenResponse.from(authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = PASSWORD_MAX) String password,
            @Size(max = 120) String displayName) {}

    public record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = PASSWORD_MAX) String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {

        static TokenResponse from(AuthService.TokenPair pair) {
            return new TokenResponse(
                    pair.accessToken(),
                    pair.refreshToken(),
                    "Bearer",
                    pair.accessExpiresAt(),
                    pair.refreshExpiresAt());
        }
    }
}
