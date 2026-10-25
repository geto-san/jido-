package com.geto.jido.server.config;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global tile configuration consumed by the Android client at boot.
 *
 * <p>Reads are open on purpose: the Android app fetches this before login to
 * decide whether to register tile shortcuts, so requiring a bearer token would
 * leave first-run users with no shortcuts until they complete a round trip. The
 * payload is non-sensitive (app name plus tile on/off states).
 *
 * <p>Writes are authenticated, because an unauthenticated write would let any
 * caller persist an app name that every client then renders.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private final TileConfigStore store;

    public ConfigController(TileConfigStore store) {
        this.store = store;
    }

    @GetMapping("/tiles")
    ResponseEntity<ConfigResponse> getTiles() {
        return ResponseEntity.ok(new ConfigResponse(store.getAppName(), store.getTiles()));
    }

    @PutMapping("/app-name")
    ResponseEntity<ConfigResponse> updateAppName(
            @RequestBody AppNameRequest request,
            @AuthenticationPrincipal JwtService.UserSubject subject) {
        if (subject == null) {
            return ResponseEntity.status(401).build();
        }
        store.updateAppName(request.name());
        return ResponseEntity.ok(new ConfigResponse(store.getAppName(), store.getTiles()));
    }

    @PutMapping("/tiles/{key}")
    ResponseEntity<ConfigResponse> updateTile(
            @PathVariable String key,
            @RequestBody TileRequest request,
            @AuthenticationPrincipal JwtService.UserSubject subject) {
        if (subject == null) {
            return ResponseEntity.status(401).build();
        }
        store.updateTile(key, request.enabled(), request.value());
        return ResponseEntity.ok(new ConfigResponse(store.getAppName(), store.getTiles()));
    }

    @DeleteMapping("/tiles/{key}")
    ResponseEntity<ConfigResponse> resetTile(
            @PathVariable String key,
            @AuthenticationPrincipal JwtService.UserSubject subject) {
        if (subject == null) {
            return ResponseEntity.status(401).build();
        }
        store.resetTile(key);
        return ResponseEntity.ok(new ConfigResponse(store.getAppName(), store.getTiles()));
    }

    public record AppNameRequest(String name) {}

    public record TileRequest(Boolean enabled, String value) {}

    public record ConfigResponse(String appName, Map<String, TileConfigStore.TileState> tiles) {}
}