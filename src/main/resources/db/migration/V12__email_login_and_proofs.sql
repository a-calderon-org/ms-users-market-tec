-- Additive: institutional Magic Link attempts and email recent-proof challenges.
-- Only SHA-256 verifiers are stored; link secrets are never persisted.
CREATE TABLE dbo.email_login_attempts (
    attempt_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    recipient varchar(254) COLLATE Latin1_General_100_BIN2 NOT NULL,
    verifier char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    browser_hash char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_ms bigint NOT NULL,
    expires_ms bigint NOT NULL,
    failures int NOT NULL CONSTRAINT df_email_login_failures DEFAULT 0,
    consumed bit NOT NULL CONSTRAINT df_email_login_consumed DEFAULT 0,
    version bigint NOT NULL CONSTRAINT df_email_login_version DEFAULT 0,
    CONSTRAINT pk_email_login_attempts PRIMARY KEY (attempt_id),
    CONSTRAINT ck_email_login_lifetime CHECK (created_ms >= 0 AND expires_ms > created_ms),
    CONSTRAINT ck_email_login_state CHECK (failures >= 0 AND version >= 0)
);
CREATE INDEX ix_email_login_recipient ON dbo.email_login_attempts(recipient, created_ms);
GO
CREATE TABLE dbo.email_proof_challenges (
    attempt_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    verifier char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    consumed bit NOT NULL CONSTRAINT df_email_proof_consumed DEFAULT 0,
    CONSTRAINT pk_email_proof_challenges PRIMARY KEY (attempt_id),
    CONSTRAINT fk_email_proof_attempt FOREIGN KEY (attempt_id)
        REFERENCES dbo.recent_authentication_attempts(attempt_id)
);
