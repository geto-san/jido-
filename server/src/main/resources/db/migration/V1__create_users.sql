-- Baseline migration establishing the users table that every later feature
-- (downloads, tiles, preferences) hangs off. Every table in this schema is
-- user-scoped from day one so multi-tenancy never has to be retrofitted.

CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    -- BCrypt hash; never the raw password.
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(120),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT users_email_lower_uniq UNIQUE (email)
);

-- Case-insensitive login lookup: unique on the raw value alone would let
-- "A@b.com" and "a@b.com" both register, then collide on compareToIgnoreCase.
CREATE UNIQUE INDEX users_email_ci_uniq ON users (lower(email));
