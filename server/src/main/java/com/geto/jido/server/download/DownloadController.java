package com.geto.jido.server.download;

import com.geto.jido.server.config.JwtService;
import com.geto.jido.server.storage.MediaStorage;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/downloads")
public class DownloadController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_URL_LENGTH = 2048;

    private final DownloadRepository downloads;
    private final DownloadWorker worker;
    private final MediaStorage storage;

    public DownloadController(
            DownloadRepository downloads, DownloadWorker worker, MediaStorage storage) {
        this.downloads = downloads;
        this.worker = worker;
        this.storage = storage;
    }

    /**
     * Enqueues a download.
     *
     * <p>Idempotent on {@code clientRef}: a client that retries after a lost
     * response gets the original job back instead of a duplicate transfer.
     */
    @PostMapping
    ResponseEntity<DownloadResponse> enqueue(
            @AuthenticationPrincipal JwtService.UserSubject subject,
            @RequestBody EnqueueRequest request) {
        String url = request.url() == null ? "" : request.url().strip();
        if (url.length() > MAX_URL_LENGTH || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("url must be http or https");
        }

        if (request.clientRef() != null && !request.clientRef().isBlank()) {
            var existing = downloads.findByUserIdAndClientRef(subject.userId(), request.clientRef());
            if (existing.isPresent()) {
                return ResponseEntity.ok(DownloadResponse.from(existing.get()));
            }
        }

        Download download = downloads.save(new Download(
                subject.userId(),
                url,
                com.geto.jido.server.resolve.Platform.from(url).name().toLowerCase(Locale.ROOT),
                request.clientRef()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DownloadResponse.from(download));
    }

    @GetMapping
    List<DownloadResponse> list(
            @AuthenticationPrincipal JwtService.UserSubject subject,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Clamped rather than rejected: a client asking for a huge page should
        // get a bounded answer, not an error it has no way to interpret.
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        return downloads.findByUserIdOrderByCreatedAtDesc(
                        subject.userId(), PageRequest.of(safePage, safeSize))
                .stream()
                .map(DownloadResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    DownloadResponse get(
            @AuthenticationPrincipal JwtService.UserSubject subject, @PathVariable UUID id) {
        return downloads.findByIdAndUserId(id, subject.userId())
                .map(DownloadResponse::from)
                .orElseThrow(() -> new NotFound("Download not found"));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> cancel(
            @AuthenticationPrincipal JwtService.UserSubject subject, @PathVariable UUID id) {
        Download download = downloads.findByIdAndUserId(id, subject.userId())
                .orElseThrow(() -> new NotFound("Download not found"));
        if (download.isTerminal()) {
            // Nothing to cancel; 409 rather than 404 so a client can tell the
            // job apart from the request being malformed.
            throw new Conflict("Download already finished with status " + download.getStatus());
        }
        worker.requestCancel(id);
        return ResponseEntity.accepted().build();
    }

    /**
     * Streams stored media back to the client.
     *
     * <p>Scoped by user on lookup, so a valid job id belonging to someone else
     * is a 404 rather than a download of their media.
     */
    @GetMapping("/{id}/content")
    ResponseEntity<InputStreamResource> content(
            @AuthenticationPrincipal JwtService.UserSubject subject, @PathVariable UUID id) {
        Download download = downloads.findByIdAndUserId(id, subject.userId())
                .filter(d -> d.getStorageKey() != null)
                .orElseThrow(() -> new NotFound("Download not found"));
        InputStream stream = storage.open(download.getStorageKey())
                .orElseThrow(() -> new NotFound("Stored media is no longer available"));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.getSizeBytes() == null ? -1 : download.getSizeBytes())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.getFileName() == null ? "download" : download.getFileName())
                                .build()
                                .toString())
                .body(new InputStreamResource(stream));
    }

    public record EnqueueRequest(String url, String clientRef) {}

    public record DownloadResponse(
            UUID id,
            String sourceUrl,
            String platform,
            String status,
            String fileName,
            String contentType,
            Long sizeBytes,
            long transferredBytes,
            Double progressPercent,
            String error,
            String contentUrl,
            java.time.Instant createdAt,
            java.time.Instant completedAt) {

        static DownloadResponse from(Download download) {
            Double progress = null;
            if (download.getSizeBytes() != null && download.getSizeBytes() > 0) {
                progress = Math.min(100.0,
                        Math.round(download.getTransferredBytes() * 10000.0 / download.getSizeBytes())
                                / 100.0);
            }
            String contentUrl = download.getStorageKey() == null ? null
                    : ServletUriComponentsBuilder.fromCurrentContextPath()
                            .path("/api/v1/downloads/")
                            .path(download.getId().toString())
                            .path("/content")
                            .toUriString();
            return new DownloadResponse(
                    download.getId(),
                    download.getSourceUrl(),
                    download.getPlatform(),
                    download.getStatus().name(),
                    download.getFileName(),
                    download.getContentType(),
                    download.getSizeBytes(),
                    download.getTransferredBytes(),
                    progress,
                    download.getErrorDetail(),
                    contentUrl,
                    download.getCreatedAt(),
                    download.getCompletedAt());
        }
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}