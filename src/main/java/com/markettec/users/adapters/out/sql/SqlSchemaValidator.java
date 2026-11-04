package com.markettec.users.adapters.out.sql;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Startup validation only. Missing or incompatible schemas fail closed, never migrate. */
public final class SqlSchemaValidator {
    private SqlSchemaValidator() { }

    public static void validate(DataSource source, String expectedDatabase) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        if (!expectedDatabase.equals(jdbc.queryForObject("SELECT DB_NAME()", String.class))) {
            throw new UserPersistenceException("Unexpected SQL database");
        }
        Flyway.configure().dataSource(source).defaultSchema("dbo").cleanDisabled(true)
                .baselineOnMigrate(false).load().validate();
        Map<String, List<String>> primaryKeys = Map.of("users", List.of("user_id"),
                "profiles", List.of("user_id"), "external_identities", List.of("issuer_hash", "subject_hash"),
                "sessions", List.of("session_id"),
                "recent_authentication_attempts", List.of("attempt_id"));
        primaryKeys.forEach((table, expected) -> {
            var actual = jdbc.queryForList("""
                    SELECT c.name FROM sys.indexes i
                    JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id
                    JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id
                    WHERE i.object_id=OBJECT_ID(?) AND i.is_primary_key=1 AND i.is_disabled=0
                    ORDER BY ic.key_ordinal
                    """, String.class, "dbo." + table);
            if (!actual.equals(expected)) {
                throw new UserPersistenceException("Missing SQL primary key: " + table);
            }
        });
        var foreignKeys = Set.copyOf(jdbc.queryForList("""
                SELECT name FROM sys.foreign_keys WHERE referenced_object_id=OBJECT_ID('dbo.users')
                    AND is_disabled=0 AND is_not_trusted=0 AND delete_referential_action=0
                """, String.class));
        if (!foreignKeys.containsAll(Set.of("fk_profiles_user", "fk_external_identities_user", "fk_sessions_user",
                "fk_recent_attempt_user"))) {
            throw new UserPersistenceException("Missing SQL referential constraints");
        }
        Integer sessionForeignKey = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.foreign_keys WHERE name='fk_recent_attempt_session'
                    AND parent_object_id=OBJECT_ID('dbo.recent_authentication_attempts')
                    AND referenced_object_id=OBJECT_ID('dbo.sessions')
                    AND is_disabled=0 AND is_not_trusted=0 AND delete_referential_action=0
                """, Integer.class);
        if (sessionForeignKey == null || sessionForeignKey != 1)
            throw new UserPersistenceException("Missing recent attempt session constraint");
        var checks = Set.copyOf(jdbc.queryForList("""
                SELECT name FROM sys.check_constraints WHERE is_disabled=0 AND is_not_trusted=0
                    AND parent_object_id IN (OBJECT_ID('dbo.users'), OBJECT_ID('dbo.profiles'),
                        OBJECT_ID('dbo.external_identities'), OBJECT_ID('dbo.sessions'),
                        OBJECT_ID('dbo.recent_authentication_attempts'))
                """, String.class));
        if (!checks.containsAll(Set.of("ck_users_status", "ck_users_version", "ck_profiles_registered",
                "ck_profiles_updated", "ck_external_identities_provider", "ck_sessions_version",
                "ck_sessions_deadlines", "ck_recent_attempt_lifetime", "ck_recent_attempt_state",
                "ck_recent_attempt_method"))) {
            throw new UserPersistenceException("Missing SQL check constraints");
        }
        // Verify names, types, lengths and NOT NULL using engine metadata. No DDL is issued.
        Map<String, String> columns = jdbc.query("""
                SELECT OBJECT_NAME(c.object_id) AS table_name, c.name, TYPE_NAME(c.user_type_id) AS type_name,
                    c.max_length, c.is_nullable FROM sys.columns c
                WHERE c.object_id IN (OBJECT_ID('dbo.users'), OBJECT_ID('dbo.profiles'),
                    OBJECT_ID('dbo.external_identities'), OBJECT_ID('dbo.sessions'),
                    OBJECT_ID('dbo.recent_authentication_attempts'))
                """, (rs, row) -> Map.entry(rs.getString("table_name") + "." + rs.getString("name"),
                rs.getString("type_name") + ":" + rs.getInt("max_length") + ":" + rs.getBoolean("is_nullable")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        require(columns, "users.user_id", "varchar:40:false");
        require(columns, "users.status", "varchar:16:false");
        require(columns, "users.version", "bigint:8:false");
        require(columns, "profiles.user_id", "varchar:40:false");
        require(columns, "profiles.nombre_usuario", "nvarchar:320:false");
        require(columns, "profiles.nombre", "nvarchar:400:false");
        require(columns, "profiles.apellidos", "nvarchar:640:false");
        require(columns, "profiles.correo", "varchar:254:false");
        require(columns, "profiles.foto_perfil", "nvarchar:-1:false");
        for (String prefix : List.of("registered", "updated")) {
            require(columns, "profiles." + prefix + "_second", "bigint:8:false");
            require(columns, "profiles." + prefix + "_nano", "int:4:false");
        }
        require(columns, "profiles.verificado", "bit:1:false");
        require(columns, "external_identities.issuer", "nvarchar:-1:false");
        require(columns, "external_identities.subject", "nvarchar:-1:false");
        require(columns, "external_identities.issuer_hash", "binary:32:false");
        require(columns, "external_identities.subject_hash", "binary:32:false");
        require(columns, "external_identities.user_id", "varchar:40:false");
        require(columns, "external_identities.provider", "varchar:24:false");
        require(columns, "external_identities.verified_email", "varchar:254:false");
        require(columns, "sessions.session_id", "char:32:false");
        require(columns, "sessions.user_id", "varchar:40:false");
        for (String name : List.of("created_ms", "renewable_until_ms", "last_activity_ms",
                "idle_until_ms", "absolute_until_ms", "version")) {
            require(columns, "sessions." + name, "bigint:8:false");
        }
        require(columns, "sessions.revoked", "bit:1:false");
        for (String name : List.of("attempt_id", "session_id"))
            require(columns, "recent_authentication_attempts." + name, "char:32:false");
        require(columns, "recent_authentication_attempts.user_id", "varchar:40:false");
        require(columns, "recent_authentication_attempts.method", "varchar:24:false");
        require(columns, "recent_authentication_attempts.operation", "varchar:80:false");
        require(columns, "recent_authentication_attempts.target", "nvarchar:640:false");
        require(columns, "recent_authentication_attempts.nonce", "char:64:false");
        for (String name : List.of("created_ms", "expires_ms", "version"))
            require(columns, "recent_authentication_attempts." + name, "bigint:8:false");
        require(columns, "recent_authentication_attempts.authenticated_ms", "bigint:8:true");
        require(columns, "recent_authentication_attempts.consumed", "bit:1:false");
        var hashes = jdbc.queryForList("""
                SELECT name FROM sys.computed_columns WHERE object_id=OBJECT_ID('dbo.external_identities')
                    AND is_persisted=1 AND LOWER(definition) LIKE '%hashbytes%sha2_256%'
                """, String.class);
        if (!Set.copyOf(hashes).equals(Set.of("issuer_hash", "subject_hash"))) {
            throw new UserPersistenceException("Missing persisted identity digests");
        }
        Integer ownerIndex = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.indexes i JOIN sys.index_columns ic
                    ON ic.object_id=i.object_id AND ic.index_id=i.index_id
                JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id
                WHERE i.object_id=OBJECT_ID('dbo.external_identities') AND i.name='ix_external_identities_user'
                    AND i.is_disabled=0 AND c.name='user_id' AND ic.key_ordinal=1
                """, Integer.class);
        if (ownerIndex == null || ownerIndex != 1) {
            throw new UserPersistenceException("Missing identity owner index");
        }
    }

    private static void require(Map<String, String> columns, String column, String definition) {
        if (!definition.equals(columns.get(column))) {
            throw new UserPersistenceException("Incompatible SQL column: " + column);
        }
    }
}
