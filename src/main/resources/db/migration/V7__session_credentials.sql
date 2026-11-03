ALTER TABLE dbo.sessions ADD refresh_hash char(64) COLLATE Latin1_General_100_BIN2 NULL,
    csrf_hash char(64) COLLATE Latin1_General_100_BIN2 NULL;
GO
ALTER TABLE dbo.sessions ADD CONSTRAINT ck_sessions_credential_pair CHECK
    ((refresh_hash IS NULL AND csrf_hash IS NULL) OR
     (refresh_hash IS NOT NULL AND csrf_hash IS NOT NULL));
CREATE UNIQUE INDEX ux_sessions_refresh_hash ON dbo.sessions(refresh_hash) WHERE refresh_hash IS NOT NULL;
