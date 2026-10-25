package com.geto.jido.server.download;

import com.geto.jido.server.resolve.ResolveService;
import com.geto.jido.server.storage.MediaStorage;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the pending-download queue.
 *
 * <p>Scheduling is one fixed-delay tick claiming a batch, rather than a task
 * per enqueue: a burst of clipboard hits must not spawn a thread each, and the
 * database row is already the durable hand-off between API and worker.
 */
@Component
public class DownloadWorker {

    private static final Logger log = LoggerFactory.getLogger(DownloadWorker.class);

    /** Statuses a worker can hold; anything outside these is not in-flight. */
    private static final List<DownloadStatus> ACTIVE =
            List.of(DownloadStatus.RESOLVING, DownloadStatus.DOWNLOADING);

    private final DownloadRepository downloads;
    private final ResolveService resolveService;
    private final MediaFetcher fetcher;
    private final MediaStorage storage;
    private final DownloadProperties properties;
    private final TransactionTemplate transactions;

    /**
     * Cancellation flags, consulted between reads.
     *
     * <p>In-memory on purpose: a cancel only needs to interrupt a transfer that
     * is in flight now. A restart loses the flag and the job simply fails as
     * interrupted, which is the correct outcome anyway.
     */
    private final java.util.Set<UUID> cancelRequests = ConcurrentHashMap.newKeySet();

    public DownloadWorker(
            DownloadRepository downloads,
            ResolveService resolveService,
            MediaFetcher fetcher,
            MediaStorage storage,
            DownloadProperties properties,
            TransactionTemplate transactions) {
        this.downloads = downloads;
        this.resolveService = resolveService;
        this.fetcher = fetcher;
        this.storage = storage;
        this.properties = properties;
        this.transactions = transactions;
    }

    /**
     * Requeues jobs orphaned by a crash or restart, before the first poll.
     *
     * <p>Runs after startup so Flyway has already applied the schema, and it
     * uses a generous threshold so it cannot steal a job from a healthy worker
     * that is mid-transfer — the lease is far longer than the longest poll gap.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedJobs() {
        recoverOrphaned(Instant.now().minus(properties.staleAfter()));
    }

    /**
     * Select and requeue in one transaction, via the template rather than
     * {@code @Transactional}: the event listener above calls this on
     * {@code this}, and self-invocation bypasses the Spring proxy, so an
     * annotation here would silently not apply.
     */
    private int requeueStale(List<UUID> stale) {
        return transactions.execute(status ->
                downloads.requeueStale(stale, "requeued after server restart"));
    }

    /**
     * @param cutoff active jobs untouched since before this are considered orphaned
     * @return how many jobs were requeued
     */
    public int recoverOrphaned(Instant cutoff) {
        List<UUID> stale = downloads.findStaleIds(ACTIVE, cutoff);
        if (stale.isEmpty()) {
            return 0;
        }
        int requeued = requeueStale(stale);
        log.warn("Requeued {} download(s) orphaned mid-flight", requeued);
        return requeued;
    }

    @Scheduled(fixedDelayString = "${jido.downloads.poll-interval:PT1S}")
    public void poll() {
        List<Download> batch = claimBatch();
        if (batch.isEmpty()) {
            return;
        }
        log.info("Claimed {} download(s)", batch.size());
        for (Download download : batch) {
            try {
                process(download.getId());
            } catch (RuntimeException e) {
                // A failure here must not stop the rest of the batch.
                log.error("Download {} failed unexpectedly", download.getId(), e);
            }
        }
    }

    /**
     * SKIP LOCKED only holds for the life of the claim transaction, so the rows
     * are marked non-pending before this returns. Claim and transition must be
     * one transaction or two workers would pick up the same job.
     */
    private List<Download> claimBatch() {
        return transactions.execute(status -> {
            List<Download> claimed = downloads.claimPending(properties.claimBatchSize());
            claimed.forEach(download -> {
                if (download.markDownloading()) {
                    downloads.save(download);
                }
            });
            return claimed;
        });
    }

    private void process(UUID id) {
        Download download = downloads.findById(id).orElse(null);
        if (download == null || download.isTerminal()) {
            return;
        }
        // Declared outside the try so the catch blocks can clean up the exact
        // object. LocalMediaStorage.delete resolves a key to a single path, so
        // handing it the user prefix would target a directory, not the file.
        String objectKey = null;

        try {
            ResolveService.ResolvedMedia media = resolveService.resolve(download.getSourceUrl());
            applyResolution(download.getId(), media);

            String fileName = fileNameFor(download.getSourceUrl(), media);
            objectKey = "users/" + download.getUserId() + "/" + fileName;
            String finalObjectKey = objectKey;

            var storedSize = new java.util.concurrent.atomic.AtomicLong(-1);
            LongConsumer progress = bytes -> updateProgress(download.getId(), bytes);

            markTransferStarted(download.getId());

            fetcher.fetch(media.directUrl(), properties.requestTimeout(), (body, declaredSize) -> {
                var stream = new ProgressTrackingInputStream(
                        body,
                        declaredSize,
                        () -> cancelRequests.contains(id),
                        progress);
                var stored = storage.store(finalObjectKey, stream, media.extractor());
                storedSize.set(stored.sizeBytes());
            });

            markCompleted(download.getId(), finalObjectKey, fileName, storedSize.get());
        } catch (ProgressTrackingInputStream.DownloadCancelledException e) {
            log.info("Download {} cancelled", id);
            discard(objectKey);
            markCancelled(download.getId());
        } catch (Exception e) {
            log.warn("Download {} failed: {}", id, e.toString());
            discard(objectKey);
            markFailed(download.getId(), rootMessage(e));
        } finally {
            cancelRequests.remove(id);
        }
    }

    /**
     * Removes a partially written object. A null key means the failure happened
     * before a key was chosen, and there is nothing on disk to remove.
     */
    private void discard(String objectKey) {
        if (objectKey != null) {
            storage.delete(objectKey);
        }
    }

    /**
     * Enqueues the transfer state once resolution has produced a real media
     * URL. Without this the row sits at RESOLVING for the entire download and
     * clients cannot distinguish "still figuring out the link" from "transferring".
     */
    private void markTransferStarted(UUID id) {
        downloads.findById(id).ifPresent(download -> {
            if (download.markDownloading()) {
                downloads.save(download);
            }
        });
    }

    private void applyResolution(UUID id, ResolveService.ResolvedMedia media) {
        downloads.findById(id).ifPresent(download -> {
            if (download.markResolving(
                    media.directUrl(), media.title(), media.extractor(), media.contentLength())) {
                downloads.save(download);
            }
        });
    }

    /**
     * Progress is written at most a few times a second; every 1MB chunk would
     * turn one download into hundreds of row updates.
     */
    private void updateProgress(UUID id, long transferred) {
        downloads.findById(id).ifPresent(download -> {
            if (download.updateProgress(transferred)) {
                downloads.save(download);
            }
        });
    }

    private void markCompleted(UUID id, String objectKey, String fileName, long storedSize) {
        downloads.findById(id).ifPresent(download -> {
            if (download.markCompleted(objectKey, fileName, storedSize)) {
                downloads.save(download);
            }
        });
    }

    private void markFailed(UUID id, String detail) {
        downloads.findById(id).ifPresent(download -> {
            if (download.markFailed(detail)) {
                downloads.save(download);
            }
        });
    }

    private void markCancelled(UUID id) {
        downloads.findById(id).ifPresent(download -> {
            if (download.markCancelled()) {
                downloads.save(download);
            }
        });
    }

    /** Requests cancellation of an in-flight job. */
    public void requestCancel(UUID id) {
        cancelRequests.add(id);
    }

    private static String fileNameFor(String sourceUrl, ResolveService.ResolvedMedia media) {
        String base = media.title() == null || media.title().isBlank()
                ? "clip"
                : media.title().strip();
        String sanitised = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_{2,}", "_");
        // Bounded because filesystem names cap at 255 bytes and the title is
        // attacker-influenced.
        if (sanitised.length() > 80) {
            sanitised = sanitised.substring(0, 80);
        }
        return sanitised + extensionOf(sourceUrl, media);
    }

    private static String extensionOf(String sourceUrl, ResolveService.ResolvedMedia media) {
        if (media.formatId() != null) {
            int dot = media.formatId().lastIndexOf('.');
            if (dot > 0) {
                String candidate = media.formatId().substring(dot + 1).toLowerCase(Locale.ROOT);
                if (candidate.matches("[a-z0-9]{2,5}")) {
                    return "." + candidate;
                }
            }
        }
        int dot = sourceUrl.lastIndexOf('.');
        if (dot > 0 && dot < sourceUrl.length() - 1) {
            String candidate = sourceUrl.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{2,5}")) {
                return "." + candidate;
            }
        }
        return ".mp4";
    }

    private static String rootMessage(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}