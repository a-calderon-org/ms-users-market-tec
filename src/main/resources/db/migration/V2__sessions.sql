CREATE TABLE dbo.sessions (
    session_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_ms bigint NOT NULL,
    renewable_until_ms bigint NOT NULL,
    last_activity_ms bigint NOT NULL,
    idle_until_ms bigint NOT NULL,
    absolute_until_ms bigint NOT NULL,
    revoked bit NOT NULL CONSTRAINT df_sessions_revoked DEFAULT 0,
    version bigint NOT NULL CONSTRAINT df_sessions_version DEFAULT 0,
    CONSTRAINT pk_sessions PRIMARY KEY (session_id),
    CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT ck_sessions_version CHECK (version >= 0),
    CONSTRAINT ck_sessions_deadlines CHECK (created_ms >= 0 AND last_activity_ms >= created_ms
        AND renewable_until_ms > created_ms AND idle_until_ms > last_activity_ms
        AND absolute_until_ms > created_ms)
);
CREATE INDEX ix_sessions_user ON dbo.sessions(user_id);
