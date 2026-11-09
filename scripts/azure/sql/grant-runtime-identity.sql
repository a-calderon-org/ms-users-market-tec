-- ms-users runtime identity: least privilege on Azure SQL.
-- Run AFTER migrations, connected to the ms-users database as the Entra administrator
-- (operator), never as the runtime identity. The staging script supplies the App
-- Service name and the client ID of its system-assigned identity.
--
--   ms-users-staging.sh --config <file> sql-grant --execute
--
-- Runtime needs: SELECT/INSERT/UPDATE on dbo tables (no DELETE, no DDL), SELECT on
-- flyway_schema_history and catalog metadata for SqlSchemaValidator. No role membership.
SET NOCOUNT ON;
SET XACT_ABORT ON;

IF OBJECT_ID(N'dbo.flyway_schema_history', N'U') IS NULL
    THROW 50001, 'flyway_schema_history missing: run the controlled migration first.', 1;

BEGIN TRANSACTION;

DECLARE @client_id uniqueidentifier = CONVERT(uniqueidentifier, N'$(RUNTIME_CLIENT_ID)');
DECLARE @sid binary(16) = CONVERT(binary(16), @client_id);
DECLARE @principal_id int = DATABASE_PRINCIPAL_ID(N'$(RUNTIME_PRINCIPAL)');

IF @principal_id IS NULL
BEGIN
    DECLARE @create_user nvarchar(400) = N'CREATE USER ' + QUOTENAME(N'$(RUNTIME_PRINCIPAL)')
        + N' WITH SID = ' + CONVERT(nvarchar(34), @sid, 1) + N', TYPE = E;';
    EXEC sys.sp_executesql @create_user;
END
ELSE
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE principal_id = @principal_id AND type = 'E' AND sid = @sid)
        THROW 50002, 'Existing runtime user has a different identity.', 1;
    IF EXISTS (SELECT 1 FROM sys.database_role_members WHERE member_principal_id = @principal_id)
        THROW 50003, 'Existing runtime user belongs to a database role.', 1;
    IF EXISTS (SELECT 1 FROM sys.schemas WHERE principal_id = @principal_id)
        OR EXISTS (SELECT 1 FROM sys.objects WHERE principal_id = @principal_id)
        OR EXISTS (
            SELECT 1 FROM sys.database_permissions
            WHERE grantee_principal_id = @principal_id AND NOT (
                (class = 0 AND permission_name = 'CONNECT' AND state = 'G')
                OR (class = 3 AND major_id = SCHEMA_ID(N'dbo') AND state = 'G'
                    AND permission_name IN ('SELECT', 'INSERT', 'UPDATE'))
                OR (class = 1 AND major_id = OBJECT_ID(N'dbo.flyway_schema_history') AND minor_id = 0 AND state = 'D'
                    AND permission_name IN ('INSERT', 'UPDATE', 'DELETE', 'ALTER'))
            )
        )
        THROW 50004, 'Existing runtime user has unexpected ownership or permissions.', 1;
END;

GRANT SELECT, INSERT, UPDATE ON SCHEMA::dbo TO [$(RUNTIME_PRINCIPAL)];
-- Migration history stays readable (Flyway validate) but never writable by the runtime.
DENY INSERT, UPDATE, DELETE, ALTER ON OBJECT::dbo.flyway_schema_history TO [$(RUNTIME_PRINCIPAL)];

COMMIT TRANSACTION;

-- Evidence: role memberships must be empty; permissions limited to the rows above.
SELECT r.name AS role_name
FROM sys.database_role_members m
JOIN sys.database_principals r ON r.principal_id = m.role_principal_id
WHERE m.member_principal_id = DATABASE_PRINCIPAL_ID(N'$(RUNTIME_PRINCIPAL)');

SELECT p.state_desc, p.permission_name, p.class_desc,
       CASE p.class WHEN 0 THEN DB_NAME() WHEN 1 THEN OBJECT_NAME(p.major_id) WHEN 3 THEN SCHEMA_NAME(p.major_id) END AS securable
FROM sys.database_permissions p
WHERE p.grantee_principal_id = DATABASE_PRINCIPAL_ID(N'$(RUNTIME_PRINCIPAL)')
ORDER BY p.class_desc, securable, p.permission_name;
