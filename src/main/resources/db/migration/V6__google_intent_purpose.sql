ALTER TABLE dbo.google_login_intents
ADD purpose varchar(16) NOT NULL
    CONSTRAINT df_google_intent_purpose DEFAULT 'LOGIN';
GO
ALTER TABLE dbo.google_login_intents
ADD CONSTRAINT ck_google_intent_purpose CHECK (purpose IN ('LOGIN', 'REGISTRATION'));
