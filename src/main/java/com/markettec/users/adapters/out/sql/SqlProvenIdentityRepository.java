package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.ProvenIdentityRepository;
import com.markettec.users.domain.model.ExternalIdentityKey;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SqlProvenIdentityRepository implements ProvenIdentityRepository {
    private final JdbcTemplate jdbc;
    public SqlProvenIdentityRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void record(String attemptId, ExternalIdentityKey key) {
        requireTransaction();
        try {
            jdbc.update("INSERT INTO dbo.recent_attempt_identities(attempt_id,issuer,subject) VALUES (?,?,?)",
                    attemptId, key.issuer(), key.subject());
        } catch (org.springframework.dao.DuplicateKeyException replay) {
            // Only a repeated primary key means the proof was already presented; nothing else is translated.
            throw new com.markettec.users.application.session.SessionRejectedException(
                    com.markettec.users.application.session.SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        }
    }

    @Override public Optional<ExternalIdentityKey> find(String attemptId) {
        requireTransaction();
        return jdbc.query("SELECT issuer,subject FROM dbo.recent_attempt_identities WHERE attempt_id=?",
                (rs, row) -> new ExternalIdentityKey(rs.getString(1), rs.getString(2)), attemptId)
                .stream().findFirst();
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Proven identity transaction required");
    }
}
