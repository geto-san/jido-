package com.geto.jido.server.download;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

/**
 * One requested download, from enqueue to terminal state.
 *
 * <p>Progress lives on the entity rather than in a separate table: progress is
 * a single latest-value field, and writing a row per percentage tick would
 * make the write path the bottleneck long before the network is.
 */
@Entity
@Table(name = "downloads")
public class Download {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    private String sourceUrl;

    @Column(nullable = false, length = 24)
    private String platform;

    /**
     * NAMED_ENUM is required because the column is a native Postgres enum
     * type, not varchar. Without it Hibernate binds the value as a string and
     * Postgres rejects it with "column status is of type download_status but
     * expression is of type character varying".
     */
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DownloadStatus status = DownloadStatus.PENDING;

    @Column(name = "media_url", columnDefinition = "text")
    private String mediaUrl;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "content_type", length = 127)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "error_detail", columnDefinition = "text")
    private String errorDetail;

    /** Storage key of the completed media; null until the transfer finishes. */
    @Column(name = "storage_key", length = 1024)
    private String storageKey;

    /** Bytes written so far, so a client polling progress sees movement. */
    @Column(name = "transferred_bytes", nullable = false)
    private long transferredBytes;

    @Column(name = "last_progress_at")
    private Instant lastProgressAt;

    /** High-water mark for the flush throttle; not persisted. */
    @Transient
    private long lastFlushedBytes;

    private static final long PROGRESS_STEP_BYTES = 1 << 20;

    /** Idempotency key supplied by the client; unique per user when present. */
    @Column(name = "client_ref", length = 128)
    private String clientRef;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Download() {
        // for JPA
    }

    public Download(UUID userId, String sourceUrl, String platform, String clientRef) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.sourceUrl = sourceUrl;
        this.platform = platform;
        this.clientRef = clientRef;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    /** @return true when the state actually changed, false when already terminal */
    public boolean markResolving(String mediaUrl, String fileName, String contentType, Long sizeBytes) {
        if (isTerminal()) {
            return false;
        }
        this.status = DownloadStatus.RESOLVING;
        this.mediaUrl = mediaUrl;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.startedAt = this.startedAt == null ? Instant.now() : this.startedAt;
        return true;
    }

    public boolean markDownloading() {
        if (isTerminal()) {
            return false;
        }
        this.status = DownloadStatus.DOWNLOADING;
        this.startedAt = this.startedAt == null ? Instant.now() : this.startedAt;
        return true;
    }

    /**
     * Marks the job done and records where the media landed, in one step.
     *
     * <p>Storage key and size are set together with the status deliberately:
     * the {@code downloads_completed_has_key} constraint makes a COMPLETED row
     * without media impossible, so the two can never be persisted apart.
     *
     * @param storedSize measured bytes, preferred over the resolver's declared
     *     size, which is an estimate for DASH formats and would otherwise leave
     *     a progress bar short of 100%.
     */
    public boolean markCompleted(String storageKey, String fileName, long storedSize) {
        if (isTerminal()) {
            return false;
        }
        this.storageKey = storageKey;
        if (fileName != null && !fileName.isBlank()) {
            this.fileName = fileName;
        }
        this.sizeBytes = storedSize;
        this.transferredBytes = storedSize;
        this.status = DownloadStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.errorDetail = null;
        return true;
    }

    /**
     * Records how much has been written.
     *
     * <p>Progress arrives per read chunk, so persisting every call would turn
     * one download into hundreds of row updates. The stored value advances on
     * every call so the in-memory object stays accurate, but the caller is only
     * asked to flush once a meaningful step has passed.
     *
     * @return true when the caller should persist this change
     */
    boolean updateProgress(long transferred) {
        if (isTerminal() || transferred <= this.transferredBytes) {
            return false;
        }
        this.transferredBytes = transferred;
        long total = this.sizeBytes == null ? 0L : this.sizeBytes;
        long step = total <= 0 ? PROGRESS_STEP_BYTES : Math.max(PROGRESS_STEP_BYTES, total / 100);
        if (transferred - this.lastFlushedBytes < step && (total <= 0 || transferred < total)) {
            return false;
        }
        this.lastFlushedBytes = transferred;
        this.lastProgressAt = Instant.now();
        return true;
    }

    public boolean markFailed(String detail) {
        if (isTerminal()) {
            return false;
        }
        this.status = DownloadStatus.FAILED;
        this.errorDetail = detail;
        this.completedAt = Instant.now();
        return true;
    }

    public boolean markCancelled() {
        if (isTerminal()) {
            return false;
        }
        this.status = DownloadStatus.CANCELLED;
        this.completedAt = Instant.now();
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getPlatform() {
        return platform;
    }

    public DownloadStatus getStatus() {
        return status;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getErrorDetail() {
        return errorDetail;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public long getTransferredBytes() {
        return transferredBytes;
    }

    public Instant getLastProgressAt() {
        return lastProgressAt;
    }

    public String getClientRef() {
        return clientRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
