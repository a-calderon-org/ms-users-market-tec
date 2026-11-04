-- Preexisting random CSRF generations have no derivation key and cannot be recovered.
ALTER TABLE dbo.session_credential_generations ADD csrf_key_id varchar(32) NULL;
GO
ALTER TABLE dbo.session_credential_generations ADD CONSTRAINT ck_session_csrf_key_id
    CHECK (csrf_key_id IS NULL OR (csrf_key_id <> '' AND csrf_key_id NOT LIKE '%[^A-Za-z0-9_-]%'));
