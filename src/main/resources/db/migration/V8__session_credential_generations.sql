-- Keep only verifier history; rows belong to the session and its absolute lifetime.
ALTER TABLE dbo.sessions ADD current_credential_generation bigint NULL;
GO
CREATE TABLE dbo.session_credential_generations (
    session_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    generation bigint NOT NULL,
    refresh_hash char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    csrf_hash char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    consumed_ms bigint NULL,
    CONSTRAINT pk_session_credential_generations PRIMARY KEY (session_id, generation),
    CONSTRAINT fk_session_credential_generations_session FOREIGN KEY (session_id) REFERENCES dbo.sessions(session_id),
    CONSTRAINT ck_session_credential_generations_number CHECK (generation >= 0),
    CONSTRAINT ck_session_credential_generations_consumed CHECK (consumed_ms IS NULL OR consumed_ms >= 0)
);
CREATE UNIQUE INDEX ux_session_credential_generations_refresh
    ON dbo.session_credential_generations(refresh_hash);
CREATE UNIQUE INDEX ux_session_credential_generations_csrf
    ON dbo.session_credential_generations(csrf_hash);
CREATE UNIQUE INDEX ux_session_credential_generations_active
    ON dbo.session_credential_generations(session_id) WHERE consumed_ms IS NULL;
GO
INSERT INTO dbo.session_credential_generations(session_id,generation,refresh_hash,csrf_hash)
SELECT session_id,0,refresh_hash,csrf_hash FROM dbo.sessions WHERE refresh_hash IS NOT NULL;
UPDATE dbo.sessions SET current_credential_generation=0 WHERE refresh_hash IS NOT NULL;
GO
ALTER TABLE dbo.sessions ADD CONSTRAINT fk_sessions_current_credential_generation
    FOREIGN KEY (session_id,current_credential_generation)
    REFERENCES dbo.session_credential_generations(session_id,generation);
ALTER TABLE dbo.sessions ADD CONSTRAINT ck_sessions_credential_generation_pair CHECK
    ((refresh_hash IS NULL AND current_credential_generation IS NULL) OR
     (refresh_hash IS NOT NULL AND current_credential_generation IS NOT NULL));
-- A maintenance job must delete expired session families and their generation rows no later
-- than 24 hours after absolute_until_ms. This migration does not authorize old credentials.
