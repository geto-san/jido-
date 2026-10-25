package com.geto.jido.server.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches resolved media over HTTP.
 *
 * <p>Follows redirects manually with a hop cap: the default HttpClient permits
 * unlimited redirects, and a misbehaving CDN could otherwise bounce the worker
 * between two URLs forever.
 */
@Component
public class MediaFetcher {

    private static final Logger log = LoggerFactory.getLogger(MediaFetcher.class);
    private static final int MAX_REDIRECTS = 5;

    private final HttpClient httpClient;

    public MediaFetcher(DownloadProperties properties) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // Left off deliberately: the streaming body handler must be
                // supplied per request so a download is not buffered whole.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Streams the body, handing each chunk to {@code consumer} as it arrives.
     *
     * <p>Streaming rather than buffering: a 2GB video would otherwise sit in
     * heap, and the whole reason for a segmented write is to never hold the
     * file in memory.
     */
    public Fetched fetch(String url, Duration timeout, ChunkConsumer consumer) {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(current))
                    .timeout(timeout)
                    .header("User-Agent", "jido-server/0.1 (+https://github.com/geto-san/jido-)")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new FetchException("Could not reach the media host", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Download was interrupted", e);
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                // Drain before redirecting, or the connection is never reused.
                try (InputStream ignored = response.body()) {
                    // discard
                } catch (IOException ignored) {
                    // nothing useful to do
                }
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new FetchException("Media host returned " + status + " with no Location");
                }
                current = URI.create(current).resolve(location).toString();
                continue;
            }

            if (status != 200) {
                try (InputStream ignored = response.body()) {
                    // discard
                } catch (IOException ignored) {
                    // nothing useful to do
                }
                throw new FetchException("Media host returned HTTP " + status);
            }

            Optional<String> length = response.headers().firstValue("Content-Length");
            long declaredSize = length.map(Long::parseLong).orElse(-1L);
            String contentType = response.headers().firstValue("Content-Type").orElse(null);

            try (InputStream body = response.body()) {
                consumer.accept(body, declaredSize);
            } catch (IOException e) {
                throw new FetchException("Download failed part-way", e);
            }
            return new Fetched(current, contentType);
        }
        throw new FetchException("Media host exceeded " + MAX_REDIRECTS + " redirects");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(InputStream body, long declaredSize) throws IOException;
    }

    public record Fetched(String finalUrl, String contentType) {}

    public static class FetchException extends RuntimeException {
        public FetchException(String message) {
            super(message);
        }

        public FetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}