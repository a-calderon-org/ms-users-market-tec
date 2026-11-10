package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.PasswordCredential;
import com.markettec.users.application.session.PasswordCredentialRepository;
import com.markettec.users.domain.model.UserId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SqlPasswordCredentialRepository implements PasswordCredentialRepository {
    private final JdbcTemplate jdbc;
    public SqlPasswordCredentialRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public Optional<PasswordCredential> findLocked(UserId userId) {
        requireTransaction();
        return jdbc.query("""
            SELECT user_id,password_hash,hash_version,created_ms,updated_ms,version
            FROM dbo.password_credentials WITH (UPDLOCK,HOLDLOCK) WHERE user_id=?
            """, (rs, row) -> new PasswordCredential(new UserId(rs.getString("user_id").trim()),
                rs.getString("password_hash"), rs.getInt("hash_version"),
                Instant.ofEpochMilli(rs.getLong("created_ms")), Instant.ofEpochMilli(rs.getLong("updated_ms")),
                rs.getLong("version")), userId.value()).stream().findFirst();
    }

    @Override public void insert(PasswordCredential c) {
        requireTransaction();
        jdbc.update("INSERT INTO dbo.password_credentials(user_id,password_hash,hash_version,created_ms,updated_ms,version) "
                + "VALUES (?,?,?,?,?,?)", c.userId().value(), c.hash(), c.hashVersion(), c.createdAt().toEpochMilli(),
                c.updatedAt().toEpochMilli(), c.version());
    }

    @Override public void replace(PasswordCredential before, PasswordCredential after) {
        requireTransaction();
        if (!before.userId().equals(after.userId()) || after.version() != before.version() + 1)
            throw new UserPersistenceException("Invalid password credential update");
        int changed = jdbc.update("UPDATE dbo.password_credentials SET password_hash=?,hash_version=?,updated_ms=?,version=? "
                + "WHERE user_id=? AND version=?", after.hash(), after.hashVersion(), after.updatedAt().toEpochMilli(),
                after.version(), before.userId().value(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent password credential update");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Password credential transaction required");
    }
}
