-- Additive: institutional password credentials, Magic Link purpose, one-use password setup grants
-- and a uniform (account-existence independent) password attempt throttle.
-- Only adaptive hashes are stored (self-describing Argon2id strings); no plaintext, no reversible secret.
CREATE TABLE dbo.password_credentials (
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    password_hash varchar(255) COLLATE Latin1_General_100_BIN2 NOT NULL,
    hash_version int NOT NULL,
    created_ms bigint NOT NULL,
    updated_ms bigint NOT NULL,
    version bigint NOT NULL CONSTRAINT df_password_credentials_version DEFAULT 0,
    CONSTRAINT pk_password_credentials PRIMARY KEY (user_id),
    CONSTRAINT fk_password_credentials_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT ck_password_credentials_state CHECK (hash_version >= 1 AND version >= 0
        AND created_ms >= 0 AND updated_ms >= created_ms)
);
GO
-- Existing rows are ordinary sign-in/sign-up links. Recovery links are a distinct, non-interchangeable purpose.
ALTER TABLE dbo.email_login_attempts ADD purpose varchar(16) COLLATE Latin1_General_100_BIN2 NOT NULL
    CONSTRAINT df_email_login_purpose DEFAULT 'LOGIN';
GO
ALTER TABLE dbo.email_login_attempts ADD CONSTRAINT ck_email_login_purpose CHECK (purpose IN ('LOGIN', 'RECOVERY'));
GO
-- Issued only by a confirmed Magic Link for an account that has no password yet; bound to that session.
CREATE TABLE dbo.password_setup_grants (
    session_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_ms bigint NOT NULL,
    expires_ms bigint NOT NULL,
    consumed bit NOT NULL CONSTRAINT df_password_setup_consumed DEFAULT 0,
    CONSTRAINT pk_password_setup_grants PRIMARY KEY (session_id),
    CONSTRAINT fk_password_setup_session FOREIGN KEY (session_id) REFERENCES dbo.sessions(session_id),
    CONSTRAINT fk_password_setup_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT ck_password_setup_lifetime CHECK (created_ms >= 0 AND expires_ms > created_ms)
);
GO
-- Keyed by a SHA-256 of the normalized address (or of a user id for password changes), so unknown
-- addresses are throttled exactly like known ones and nothing about account existence leaks.
CREATE TABLE dbo.password_attempt_throttle (
    key_hash char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    failures int NOT NULL,
    window_start_ms bigint NOT NULL,
    locked_until_ms bigint NOT NULL CONSTRAINT df_password_throttle_locked DEFAULT 0,
    CONSTRAINT pk_password_attempt_throttle PRIMARY KEY (key_hash),
    CONSTRAINT ck_password_throttle_state CHECK (failures >= 0 AND window_start_ms >= 0 AND locked_until_ms >= 0)
);
