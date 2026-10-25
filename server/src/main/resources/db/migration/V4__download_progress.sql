-- Progress and storage reference for the download worker.
--
-- Added after V3 rather than folded into it: V3 had already been applied to the
-- development database, and editing a shipped migration makes Flyway's checksum
-- validation fail on every existing environment.

ALTER TABLE downloads
    ADD COLUMN storage_key       VARCHAR(1024),
    ADD COLUMN transferred_bytes BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN last_progress_at  TIMESTAMPTZ;

-- Progress reads are always "my active downloads", filtered by user and status.
CREATE INDEX downloads_active_idx
    ON downloads (user_id)
    WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING');

-- Guard the invariant that a completed job has media behind it.
ALTER TABLE downloads
    ADD CONSTRAINT downloads_completed_has_key
    CHECK (status <> 'COMPLETED' OR storage_key IS NOT NULL);