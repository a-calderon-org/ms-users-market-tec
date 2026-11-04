-- Version zero is the only state that proves no explicit renewal occurred.
ALTER TABLE dbo.sessions ADD renewed_ms bigint NULL;
GO
UPDATE dbo.sessions SET renewed_ms = created_ms WHERE version = 0;
GO
ALTER TABLE dbo.sessions ADD CONSTRAINT ck_sessions_renewed
    CHECK (renewed_ms IS NULL OR (renewed_ms >= created_ms AND renewed_ms <= absolute_until_ms));
