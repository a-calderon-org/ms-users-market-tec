CREATE TABLE dbo.recent_authentication_attempts (
    attempt_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    session_id char(32) COLLATE Latin1_General_100_BIN2 NOT NULL,
    method varchar(24) COLLATE Latin1_General_100_BIN2 NOT NULL,
    operation varchar(80) COLLATE Latin1_General_100_BIN2 NOT NULL,
    target nvarchar(320) COLLATE Latin1_General_100_BIN2 NOT NULL,
    nonce char(64) COLLATE Latin1_General_100_BIN2 NOT NULL,
    created_ms bigint NOT NULL,
    expires_ms bigint NOT NULL,
    authenticated_ms bigint NULL,
    consumed bit NOT NULL CONSTRAINT df_recent_attempt_consumed DEFAULT 0,
    version bigint NOT NULL CONSTRAINT df_recent_attempt_version DEFAULT 0,
    CONSTRAINT pk_recent_authentication_attempts PRIMARY KEY (attempt_id),
    CONSTRAINT fk_recent_attempt_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT fk_recent_attempt_session FOREIGN KEY (session_id) REFERENCES dbo.sessions(session_id),
    CONSTRAINT ck_recent_attempt_lifetime CHECK (created_ms >= 0 AND expires_ms > created_ms
        AND expires_ms <= created_ms + 300000),
    CONSTRAINT ck_recent_attempt_state CHECK (version >= 0 AND (consumed = 0 OR authenticated_ms IS NOT NULL)),
    CONSTRAINT ck_recent_attempt_method CHECK (method IN ('INSTITUTIONAL_EMAIL','GOOGLE','PASSKEY'))
);
CREATE INDEX ix_recent_attempt_session ON dbo.recent_authentication_attempts(session_id);
