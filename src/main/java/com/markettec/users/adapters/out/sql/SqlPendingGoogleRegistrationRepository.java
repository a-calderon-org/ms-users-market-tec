package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.session.PendingGoogleRegistration;
import com.markettec.users.application.session.PendingGoogleRegistrationRepository;
import com.markettec.users.domain.model.Email;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Row locks serialize link issue and confirmation across service instances. */
public final class SqlPendingGoogleRegistrationRepository implements PendingGoogleRegistrationRepository {
    private final JdbcTemplate jdbc;
    public SqlPendingGoogleRegistrationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void insert(PendingGoogleRegistration p) {
        requireTransaction();
        jdbc.update("""
            INSERT INTO dbo.pending_google_registrations
                (attempt_id,correlation_id,issuer,subject,recipient,verifier,expires_ms,state,version)
            VALUES (?,?,?,?,?,?,?,?,?)
            """, p.attemptId(), p.correlationId(), p.issuer(), p.subject(), p.recipient().address(), p.verifier(),
                p.expiresAt().toEpochMilli(), p.state().name(), p.version());
    }

    @Override public Optional<PendingGoogleRegistration> loadLocked(String id) {
        requireTransaction();
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return jdbc.query("""
            SELECT attempt_id,correlation_id,issuer,subject,recipient,verifier,expires_ms,state,version
            FROM dbo.pending_google_registrations WITH (UPDLOCK,HOLDLOCK) WHERE attempt_id=?
            """, (rs, row) -> new PendingGoogleRegistration(rs.getString("attempt_id").trim(),
                rs.getString("correlation_id"),
                rs.getString("issuer"), rs.getString("subject"), new Email(rs.getString("recipient")),
                rs.getString("verifier") == null ? null : rs.getString("verifier").trim(),
                Instant.ofEpochMilli(rs.getLong("expires_ms")),
                PendingGoogleRegistration.State.valueOf(rs.getString("state")), rs.getLong("version")), id)
                .stream().findFirst();
    }

    @Override public void replace(PendingGoogleRegistration before, PendingGoogleRegistration after) {
        requireTransaction();
        if (!before.attemptId().equals(after.attemptId()) || !before.correlationId().equals(after.correlationId())
                || !before.issuer().equals(after.issuer())
                || !before.subject().equals(after.subject()) || !before.recipient().equals(after.recipient())
                || !before.expiresAt().equals(after.expiresAt()) || after.version() != before.version() + 1
                || !(before.state() == PendingGoogleRegistration.State.PENDING
                    && after.state() == PendingGoogleRegistration.State.LINK_ISSUED
                    && before.verifier() == null && after.verifier() != null
                    || before.state() == PendingGoogleRegistration.State.LINK_ISSUED
                    && after.state() == PendingGoogleRegistration.State.CONFIRMED
                    && before.verifier().equals(after.verifier())))
            throw new UserPersistenceException("Invalid pending registration update");
        int changed = jdbc.update("""
            UPDATE dbo.pending_google_registrations SET verifier=?,state=?,version=?
            WHERE attempt_id=? AND state=? AND version=?
            """, after.verifier(), after.state().name(), after.version(), before.attemptId(),
                before.state().name(), before.version());
        if (changed != 1) throw new UserPersistenceException("Concurrent pending registration update");
    }

    @Override public boolean historicalEmailExists(String email) {
        requireTransaction();
        // Conservative comparison: ambiguity in local-part casing blocks a new account.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.profiles WITH (UPDLOCK,HOLDLOCK) "
                + "WHERE LOWER(correo)=LOWER(?)", Integer.class, email);
        return count != null && count > 0;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new UserPersistenceException("Pending registration transaction required");
    }
}
