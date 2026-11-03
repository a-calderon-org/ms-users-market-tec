-- Existing links require an explicit authorization decision before Google login.
ALTER TABLE dbo.external_identities ADD link_status varchar(16) NOT NULL
    CONSTRAINT df_external_identities_link_status DEFAULT 'PENDING';
GO
ALTER TABLE dbo.external_identities ADD CONSTRAINT ck_external_identities_link_status
    CHECK (link_status IN ('PENDING', 'ACTIVE', 'DISABLED', 'REVOKED', 'CONFLICT'));
