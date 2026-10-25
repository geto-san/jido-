package com.geto.jido.server.error;

import com.geto.jido.server.auth.AuthService;
import com.geto.jido.server.download.DownloadController;
import com.geto.jido.server.resolve.ResolveService;
import com.geto.jido.server.resolve.YtDlpRunner;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Single place where exceptions become HTTP responses, so every error has the
 * same shape and controllers stay free of try/catch.
 *
 * <p>5xx detail is logged and the message returned is generic: stack traces and
 * SQL in an error body are how internals leak to callers.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthService.InvalidCredentials.class)
    ResponseEntity<ApiError> invalidCredentials(AuthService.InvalidCredentials e) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_credentials", e.getMessage());
    }

    @ExceptionHandler(AuthService.EmailAlreadyRegistered.class)
    ResponseEntity<ApiError> emailTaken(AuthService.EmailAlreadyRegistered e) {
        return build(HttpStatus.CONFLICT, "email_taken", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "validation_failed", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, "malformed_request", "Request body could not be parsed");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "invalid_request", e.getMessage());
    }

    @ExceptionHandler(ResolveService.ResolutionFailed.class)
    ResponseEntity<ApiError> resolutionFailed(ResolveService.ResolutionFailed e) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "resolution_failed", e.getMessage());
    }

    @ExceptionHandler(YtDlpRunner.ResolveTimeoutException.class)
    ResponseEntity<ApiError> resolutionTimeout(YtDlpRunner.ResolveTimeoutException e) {
        return build(HttpStatus.GATEWAY_TIMEOUT, "resolution_timeout", e.getMessage());
    }

    @ExceptionHandler(DownloadController.NotFound.class)
    ResponseEntity<ApiError> notFound(DownloadController.NotFound e) {
        return build(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(DownloadController.Conflict.class)
    ResponseEntity<ApiError> conflict(DownloadController.Conflict e) {
        return build(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), code, detail, Instant.now()));
    }

    public record ApiError(int status, String code, String detail, Instant timestamp) {}
}
