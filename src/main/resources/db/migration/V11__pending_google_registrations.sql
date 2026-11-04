-- Additive evidence for an already consumed REGISTRATION Google intent.
-- No Google ID token or raw link secret is retained.
CREATE TABLE dbo.pending_google_registrations (
    attempt_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    correlation_id varchar(128) COLLATE Latin1_General_100_BIN2 NOT NULL,
    issuer nvarchar(255) COLLATE Latin1_General_100_BIN2 NOT NULL,
    subject nvarchar(255) COLLATE Latin1_General_100_BIN2 NOT NULL,
    recipient varchar(254) COLLATE Latin1_General_100_BIN2 NOT NULL,
    verifier char(64) COLLATE Latin1_General_100_BIN2 NULL,
    expires_ms bigint NOT NULL,
    state varchar(16) NOT NULL,
    version bigint NOT NULL CONSTRAINT df_pending_google_version DEFAULT 0,
    CONSTRAINT pk_pending_google_registrations PRIMARY KEY (attempt_id),
    CONSTRAINT fk_pending_google_intent FOREIGN KEY (attempt_id) REFERENCES dbo.google_login_intents(attempt_id),
    CONSTRAINT ck_pending_google_state CHECK ((state='PENDING' AND verifier IS NULL)
        OR (state IN ('LINK_ISSUED','CONFIRMED') AND verifier IS NOT NULL)),
    CONSTRAINT ck_pending_google_version CHECK (version >= 0)
);
CREATE INDEX ix_pending_google_expiry ON dbo.pending_google_registrations(expires_ms);
