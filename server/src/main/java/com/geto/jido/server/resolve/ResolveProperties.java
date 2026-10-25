package com.geto.jido.server.resolve;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * yt-dlp invocation settings.
 *
 * <p>The timeout is not optional: yt-dlp will happily sit on a slow extractor
 * forever, and one hung subprocess per request is a trivial way to exhaust the
 * connection pool.
 */
@ConfigurationProperties(prefix = "jido.resolve")
public record ResolveProperties(
        @DefaultValue("yt-dlp") String binary,
        @DefaultValue("PT45S") java.time.Duration timeout,
        @DefaultValue("PT10S") java.time.Duration connectTimeout,
        @DefaultValue("default") String cookieFile,
        @DefaultValue("false") boolean includeHttpHeaders,
        @DefaultValue("PT6H") java.time.Duration cacheTtl) {}
