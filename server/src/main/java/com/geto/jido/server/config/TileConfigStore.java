package com.geto.jido.server.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory config for quick settings tiles.
 *
 * The Android client reads this once at startup to decide whether to show
 * shortcut tiles and what their default state should be. An in-memory map is
 * intentionally sufficient for now: there is no tenant-scoped override, and
 * the JVM is single-process. It keeps the endpoint trivial and makes the
 * health of tile defaults easy to reason about.
 */
@Component
public class TileConfigStore {

    /** Tiles ship with a default so the client can render before any write. */
    private static final Map<String, TileState> DEFAULTS = Map.of(
            "resolution", new TileState(true, "auto"),
            "download", new TileState(false, null));

    private final AtomicReference<String> name = new AtomicReference<>("jido");
    private final Map<String, TileState> tiles;

    public TileConfigStore() {
        this.tiles = new ConcurrentHashMap<>(DEFAULTS);
    }

    public String getAppName() {
        return name.get();
    }

    public Map<String, TileState> getTiles() {
        return Map.copyOf(tiles);
    }

    public void updateAppName(String newName) {
        if (newName == null) {
            return;
        }
        String trimmed = newName.strip();
        if (trimmed.isBlank()) {
            return;
        }
        name.set(trimmed);
    }

    /**
     * Partial update: a null field leaves the current value alone, so a client
     * can flip just {@code enabled} without having to resend {@code value}.
     */
    public void updateTile(String key, Boolean enabled, String value) {
        if (key == null || key.isBlank()) {
            return;
        }
        tiles.compute(key, (k, existing) -> {
            if (existing == null) {
                return new TileState(enabled == null || enabled, value);
            }
            boolean nextEnabled = enabled == null ? existing.enabled() : enabled;
            String nextValue = value == null ? existing.value() : value;
            return new TileState(nextEnabled, nextValue);
        });
    }

    /**
     * Restores a tile to its shipped default, or removes it if unknown.
     * Delete means "reset", not "disable", so the client can rely on the
     * response reflecting a valid default state.
     */
    public void resetTile(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        TileState fallback = DEFAULTS.get(key);
        if (fallback == null) {
            tiles.remove(key);
        } else {
            tiles.put(key, fallback);
        }
    }

    public record TileState(boolean enabled, String value) {}
}