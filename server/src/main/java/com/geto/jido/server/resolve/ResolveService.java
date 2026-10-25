package com.geto.jido.server.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a share URL into a direct media URL by shelling out to yt-dlp.
 *
 * <p>Caches successful resolutions for {@link ResolveProperties#cacheTtl()}.
 * Failures are not cached: extractor breakage is usually fixed within hours,
 * and pinning a negative result for six hours would keep broken URLs broken
 * long after the fix shipped.
 */
@Service
public class ResolveService {

    private static final Logger log = LoggerFactory.getLogger(ResolveService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** yt-dlp's sentinel for "this stream has no such track". */
    private static final String NO_CODEC = "none";

    private final YtDlpRunner runner;
    private final ResolveProperties properties;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ResolveService(YtDlpRunner runner, ResolveProperties properties) {
        this.runner = runner;
        this.properties = properties;
    }

    public ResolvedMedia resolve(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        Platform platform = Platform.from(url);
        CacheEntry cached = cache.get(url);
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            return cached.value.with(true);
        }

        YtDlpRunner.Result result = runner.run(url);
        if (!result.ok()) {
            throw new ResolutionFailed(result.error());
        }

        ResolvedMedia media = parse(result.json(), platform)
                .orElseThrow(() -> new ResolutionFailed(
                        "yt-dlp returned no downloadable media for this link"));

        cache.put(url, new CacheEntry(media, Instant.now().plus(properties.cacheTtl())));
        return media.with(false);
    }

    /**
     * Picks the best single-file format.
     *
     * <p>Prefers a merged video+audio file over a video-only stream: the
     * Android client downloads one file with its segmented downloader and has
     * no muxing step, so a video-only stream would arrive silent.
     */
    private Optional<ResolvedMedia> parse(String json, Platform platform) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            log.warn("yt-dlp output was not valid JSON for a {} URL", platform);
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }

        JsonNode formats = root.path("formats");
        if (formats.isArray() && !formats.isEmpty()) {
            JsonNode best = selectBest(formats);
            if (best != null) {
                String direct = firstNonBlank(best, "url");
                if (direct != null) {
                    return Optional.of(new ResolvedMedia(
                            direct,
                            platform,
                            root.path("title").asText(null),
                            root.path("extractor_key").asText(platform.name()),
                            directDurationSeconds(best, root),
                            contentLength(best),
                            formatId(best),
                            false));
                }
            }
        }

        // Single-format sites (TikTok, most Instagram posts) put the media
        // at the top level rather than in a formats array.
        String direct = firstNonBlank(root, "url");
        if (direct == null) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedMedia(
                direct,
                platform,
                root.path("title").asText(null),
                root.path("extractor_key").asText(platform.name()),
                root.path("duration").isNumber() ? root.path("duration").asDouble() : null,
                root.path("filesize").isNumber() ? root.path("filesize").asLong() : null,
                root.path("format_id").asText(null),
                false));
    }

    private static JsonNode selectBest(JsonNode formats) {
        JsonNode best = null;
        for (JsonNode format : formats) {
            String url = firstNonBlank(format, "url");
            if (url == null || !url.startsWith("http")) {
                continue;
            }
            if (best == null || score(format) > score(best)) {
                best = format;
            }
        }
        return best;
    }

    /**
     * Ranks a format. Height dominates because that is what the app is
     * actually for; audio-only is ranked last so it never beats video.
     */
    private static double score(JsonNode format) {
        long height = format.path("height").asLong(0);
        long abr = format.path("abr").asLong(0);
        boolean hasVideo = height > 0;
        boolean hasAudio = !NO_CODEC.equals(format.path("acodec").asText(NO_CODEC));
        boolean merged = hasVideo && hasAudio;
        return (merged ? 1_000_000_000d : 0)
                + height * 10_000d
                + (format.path("tbr").asDouble(0) * 10)
                + (merged ? abr : 0);
    }

    private static String firstNonBlank(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static Double directDurationSeconds(JsonNode format, JsonNode root) {
        if (format.path("duration").isNumber()) {
            return format.path("duration").asDouble();
        }
        return root.path("duration").isNumber() ? root.path("duration").asDouble() : null;
    }

    private static Long contentLength(JsonNode format) {
        if (format.path("filesize").isNumber()) {
            return format.path("filesize").asLong();
        }
        // filesize is null for fragmented/DASH formats; the estimate is still
        // good enough for the client's progress bar.
        if (format.path("filesize_approx").isNumber()) {
            return format.path("filesize_approx").asLong();
        }
        return null;
    }

    private static String formatId(JsonNode format) {
        return firstNonBlank(format, "format_id");
    }

    /** Drops cached entries on a timer rather than growing without bound. */
    public void evictExpired() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt.isAfter(now));
    }

    public int cacheSize() {
        evictExpired();
        return cache.size();
    }

    private record CacheEntry(ResolvedMedia value, Instant expiresAt) {}

    public record ResolvedMedia(
            String directUrl,
            Platform platform,
            String title,
            String extractor,
            Double durationSeconds,
            Long contentLength,
            String formatId,
            boolean cachedHit) {

        public ResolvedMedia with(boolean cachedHit) {
            return new ResolvedMedia(
                    directUrl, platform, title, extractor, durationSeconds, contentLength, formatId, cachedHit);
        }
    }

    public static class ResolutionFailed extends RuntimeException {
        public ResolutionFailed(String message) {
            super(message);
        }
    }
}
