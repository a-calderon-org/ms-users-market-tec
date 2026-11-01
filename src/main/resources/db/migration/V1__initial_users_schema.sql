-- Additive initial schema. No baseline, data rewriting or destructive operations.
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET ARITHABORT ON;
SET NUMERIC_ROUNDABORT OFF;

CREATE TABLE dbo.users (
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    status varchar(16) NOT NULL,
    version bigint NOT NULL CONSTRAINT df_users_version DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DEACTIVATED')),
    CONSTRAINT ck_users_version CHECK (version >= 0)
);

CREATE TABLE dbo.profiles (
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    -- Domain limits count Unicode code points; NVARCHAR counts UTF-16 code units.
    nombre_usuario nvarchar(160) NOT NULL,
    nombre nvarchar(200) NOT NULL,
    apellidos nvarchar(320) NOT NULL,
    correo varchar(254) COLLATE Latin1_General_100_BIN2 NOT NULL,
    foto_perfil nvarchar(max) NOT NULL,
    -- Preserve Instant nanoseconds exactly (datetime2 would round to 100 ns).
    registered_second bigint NOT NULL,
    registered_nano int NOT NULL,
    updated_second bigint NOT NULL,
    updated_nano int NOT NULL,
    verificado bit NOT NULL,
    CONSTRAINT pk_profiles PRIMARY KEY (user_id),
    CONSTRAINT fk_profiles_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT ck_profiles_registered CHECK (registered_second >= 0 AND registered_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_profiles_updated CHECK (updated_nano BETWEEN 0 AND 999999999 AND
        (updated_second > registered_second OR
        (updated_second = registered_second AND updated_nano >= registered_nano)))
);

CREATE TABLE dbo.external_identities (
    -- Opaque keys have no domain length bound. Do not truncate them to fit an index.
    issuer nvarchar(max) COLLATE Latin1_General_100_BIN2 NOT NULL,
    subject nvarchar(max) COLLATE Latin1_General_100_BIN2 NOT NULL,
    issuer_hash AS CONVERT(binary(32), HASHBYTES('SHA2_256', CONVERT(varbinary(max), issuer))) PERSISTED NOT NULL,
    subject_hash AS CONVERT(binary(32), HASHBYTES('SHA2_256', CONVERT(varbinary(max), subject))) PERSISTED NOT NULL,
    user_id varchar(40) COLLATE Latin1_General_100_BIN2 NOT NULL,
    provider varchar(24) NOT NULL,
    verified_email varchar(254) COLLATE Latin1_General_100_BIN2 NOT NULL,
    CONSTRAINT pk_external_identities PRIMARY KEY (issuer_hash, subject_hash),
    CONSTRAINT fk_external_identities_user FOREIGN KEY (user_id) REFERENCES dbo.users(user_id),
    CONSTRAINT ck_external_identities_provider CHECK (provider IN ('GOOGLE', 'INSTITUTIONAL_EMAIL'))
);

-- The primary key enforces effective issuer/subject uniqueness across providers.
-- A theoretical SHA-256 collision is detected by the adapter and fails closed.
CREATE INDEX ix_external_identities_user ON dbo.external_identities(user_id);
