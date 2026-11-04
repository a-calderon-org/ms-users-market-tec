CREATE TABLE dbo.google_login_intents (
    attempt_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    nonce char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    browser_hash char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_ms bigint NOT NULL,
    expires_ms bigint NOT NULL,
    consumed bit NOT NULL CONSTRAINT df_google_login_consumed DEFAULT 0,
    version bigint NOT NULL CONSTRAINT df_google_login_version DEFAULT 0,
    CONSTRAINT pk_google_login_intents PRIMARY KEY (attempt_id),
    CONSTRAINT ck_google_login_lifetime CHECK (created_ms >= 0 AND expires_ms > created_ms
        AND expires_ms <= created_ms + 300000),
    CONSTRAINT ck_google_login_version CHECK (version >= 0)
);
CREATE INDEX ix_google_login_expiry ON dbo.google_login_intents(expires_ms);
