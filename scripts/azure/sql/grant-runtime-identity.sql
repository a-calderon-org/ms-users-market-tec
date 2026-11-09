-- ms-users runtime identity: least privilege on Azure SQL.
-- Run AFTER migrations, connected to the ms-users database as the Entra administrator
-- (operator), never as the runtime identity. sqlcmd variable RUNTIME_PRINCIPAL is the
-- App Service name (its system-assigned managed identity has the same display name).
--
--   sqlcmd -S <server>.database.windows.net -d <database> --authentication-method ActiveDirectoryDefault \
--          -v RUNTIME_PRINCIPAL=<webapp-name> -i scripts/azure/sql/grant-runtime-identity.sql
--
-- Runtime needs: SELECT/INSERT/UPDATE on dbo tables (no DELETE, no DDL), SELECT on
-- flyway_schema_history and catalog metadata for SqlSchemaValidator. No role membership.
SET NOCOUNT ON;
SET XACT_ABORT ON;

IF OBJECT_ID(N'dbo.flyway_schema_history', N'U') IS NULL
    THROW 50001, 'flyway_schema_history missing: run the controlled migration first.', 1;

BEGIN TRANSACTION;

IF DATABASE_PRINCIPAL_ID(N'$(RUNTIME_PRINCIPAL)') IS NULL
    CREATE USER [$(RUNTIME_PRINCIPAL)] FROM EXTERNAL PROVIDER;

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
