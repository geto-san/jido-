package com.geto.jido.server.resolve;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/resolve")
public class ResolveController {

    private static final int MAX_URL_LENGTH = 2048;

    private final ResolveService resolveService;

    public ResolveController(ResolveService resolveService) {
        this.resolveService = resolveService;
    }

    @PostMapping
    ResponseEntity<ResolvedResponse> resolve(@jakarta.validation.Valid @RequestBody ResolveRequest request) {
        var url = validate(request.url());
        var media = resolveService.resolve(url);
        return ResponseEntity.ok(ResolvedResponse.from(media));
    }

    /**
     * Rejects anything that is not http(s) before it reaches yt-dlp.
     *
     * <p>This endpoint shells out to a subprocess with the URL as an
     * argument, so accepting a {@code file://} URL would let a caller ask the
     * server to read local files into an error message.
     */
    private static String validate(String raw) {
        String url = raw.strip();
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url must be http or https");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("url must include a host");
        }
        return url;
    }

    public record ResolveRequest(@NotBlank @Size(max = MAX_URL_LENGTH) String url) {}

    public record ResolvedResponse(
            String url,
            String platform,
            String title,
            String extractor,
            Double durationSeconds,
            Long contentLength,
            String formatId,
            boolean cached) {

        static ResolvedResponse from(ResolveService.ResolvedMedia media) {
            return new ResolvedResponse(
                    media.directUrl(),
                    media.platform().name().toLowerCase(java.util.Locale.ROOT),
                    media.title(),
                    media.extractor(),
                    media.durationSeconds(),
                    media.contentLength(),
                    media.formatId(),
                    media.cachedHit());
        }
    }
}
