package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.EmailProofChallengeRepository;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class SqlEmailProofChallengeRepository implements EmailProofChallengeRepository {
    private final JdbcTemplate jdbc;
    public SqlEmailProofChallengeRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void insert(String attemptId, String verifier) {
        requireTransaction();
        try {
            jdbc.update("INSERT INTO dbo.email_proof_challenges(attempt_id,verifier,consumed) VALUES (?,?,0)",
                    attemptId, verifier);
        } catch (org.springframework.dao.DuplicateKeyException replay) {
            // The attempt already has its single link; the original challenge is left untouched.
            throw new com.markettec.users.application.session.SessionRejectedException(
                    com.markettec.users.application.session.SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        }
    }

    @Override public Optional<String> loadOpenLocked(String attemptId) {
        requireTransaction();
        return jdbc.query("SELECT verifier FROM dbo.email_proof_challenges WITH (UPDLOCK,HOLDLOCK) "
                + "WHERE attempt_id=? AND consumed=0", (rs, row) -> rs.getString(1).trim(), attemptId)
                .stream().findFirst();
    }

    @Override public void markConsumed(String attemptId) {
        requireTransaction();
        int changed = jdbc.update("UPDATE dbo.email_proof_challenges SET consumed=1 WHERE attempt_id=? AND consumed=0",
                attemptId);
        if (changed != 1) throw new UserPersistenceException("Email proof challenge already consumed");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Email proof transaction required");
    }
}
