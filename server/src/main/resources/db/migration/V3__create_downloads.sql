-- Download jobs and their per-format progress records.
--
-- The queue is a status column plus a partial index rather than a separate
-- broker: the workload is single-node to start, and adding Redis before the
-- queue is known to be a bottleneck is a cost with no measured benefit.

CREATE TYPE download_status AS ENUM (
    'PENDING', 'RESOLVING', 'DOWNLOADING', 'COMPLETED', 'FAILED', 'CANCELLED'
);

CREATE TABLE downloads (
    id                UUID             PRIMARY KEY,
    user_id           UUID             NOT NULL,
    source_url        TEXT             NOT NULL,
    platform          VARCHAR(24)      NOT NULL,
    status            download_status  NOT NULL DEFAULT 'PENDING',
    -- Populated once resolution succeeds.
    media_url         TEXT,
    file_name         VARCHAR(512),
    content_type      VARCHAR(127),
    size_bytes        BIGINT,
    error_detail      TEXT,
    -- Client-supplied idempotency key. Lets a retrying app avoid enqueuing
    -- the same URL twice when the first response was lost in flight.
    client_ref        VARCHAR(128),
    created_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,

    CONSTRAINT downloads_user_fk      FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT downloads_size_nonneg CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT downloads_client_ref_uniq UNIQUE (user_id, client_ref)
);

-- The worker's claim query: oldest pending job first, partial so the index
-- stays small as completed rows accumulate.
CREATE INDEX downloads_pending_idx
    ON downloads (created_at)
    WHERE status IN ('PENDING', 'RESOLVING', 'DOWNLOADING');

-- History screens always filter by user and sort newest first.
CREATE INDEX downloads_user_created_idx ON downloads (user_id, created_at DESC);
