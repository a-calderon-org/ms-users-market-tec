-- Legacy Magic Link attempts carry no browser binding and must not remain redeemable.
DELETE FROM dbo.email_login_attempts;
GO
ALTER TABLE dbo.email_login_attempts ADD browser_hash char(64) COLLATE Latin1_General_100_BIN2 NOT NULL;
GO
-- The exact identity proven by a "current method" recent proof (revalidated when linking).
CREATE TABLE dbo.recent_attempt_identities (
    attempt_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    issuer nvarchar(max) COLLATE Latin1_General_100_BIN2 NOT NULL,
    subject nvarchar(max) COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT pk_recent_attempt_identities PRIMARY KEY (attempt_id),
    CONSTRAINT fk_recent_attempt_identity_attempt FOREIGN KEY (attempt_id)
        REFERENCES dbo.recent_authentication_attempts(attempt_id)
);
