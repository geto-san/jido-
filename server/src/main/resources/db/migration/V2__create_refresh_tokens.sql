-- Server-side refresh sessions. Tokens are opaque random strings; only the
-- SHA-256 digest is stored so a database dump cannot be replayed as a login.

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,

    CONSTRAINT refresh_tokens_hash_uniq UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_user_fk   FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- The only query this table serves is "find a live token for this user", so
-- index exactly that. Partial index keeps revoked rows out of the index.
CREATE INDEX refresh_tokens_user_live_idx
    ON refresh_tokens (user_id)
    WHERE revoked_at IS NULL;

-- Supports the expiry sweep without a full scan.
CREATE INDEX refresh_tokens_expiry_idx ON refresh_tokens (expires_at);
