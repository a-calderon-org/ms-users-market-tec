package com.markettec.users.adapters.out.sql;

import com.markettec.users.application.ports.out.ExternalIdentityRepository;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;

/** Both repositories and all aggregate writes share one serializable transaction. */
public final class SqlUserStore implements UserRepository, ExternalIdentityRepository, UserTransactions {
    private static final int MAX_ATTEMPTS = 4;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransactionTemplate nested;
    private final ThreadLocal<TransactionStatus> active = new ThreadLocal<>();

    public SqlUserStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        transaction.setTimeout(30);
        nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work);
        if (active.get() != null) {
            // Savepoint rollback matches the nested scope of the in-memory adapter.
            try {
                return nested.execute(status -> within(status, work));
            } catch (DataAccessException | UserPersistenceException failure) {
                // SQL Server errors such as a deadlock can abort the entire transaction,
                // even when the JDBC savepoint rollback subsequently reports success.
                active.get().setRollbackOnly();
                throw failure;
            } catch (org.springframework.transaction.TransactionException failure) {
                // A failed savepoint rollback cannot safely leave the enclosing unit committable.
                active.get().setRollbackOnly();
                throw new UserPersistenceException("SQL nested transaction could not complete");
            }
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("UserTransactions must own the transaction boundary");
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            var failedWork = new AtomicReference<DataAccessException>();
            try {
                return transaction.execute(status -> {
                    try {
                        return within(status, work);
                    } catch (DataAccessException failure) {
                        failedWork.set(failure);
                        throw failure;
                    }
                });
            } catch (DataAccessException failure) {
                // Only the same callback error, after successful rollback, may be replayed.
                // A translated begin/commit/rollback error has no known safe replay boundary.
                if (failedWork.get() != failure || !retryable(failure)) {
                    throw new UserPersistenceException("SQL persistence operation failed");
                }
                if (attempt == MAX_ATTEMPTS) {
                    throw new UserPersistenceException("SQL transaction retry limit reached");
                }
                pause(attempt);
            } catch (org.springframework.transaction.TransactionException failure) {
                // A commit/connection failure has an uncertain outcome; never blindly replay it.
                throw new UserPersistenceException("SQL transaction could not complete");
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private <T> T inUnit(Supplier<T> work) {
        if (active.get() == null) {
            return execute(work);
        }
        try {
            return work.get();
        } catch (DataAccessException | UserPersistenceException failure) {
            // A caller cannot swallow a failed multi-table write and commit its earlier half.
            active.get().setRollbackOnly();
            throw failure;
        }
    }

    private <T> T within(TransactionStatus status, Supplier<T> work) {
        TransactionStatus previous = active.get();
        active.set(status);
        try {
            T result = work.get();
            if (status.isRollbackOnly()) {
                throw new UserPersistenceException("SQL unit contains a failed persistence operation");
            }
            return result;
        } finally {
            if (previous == null) {
                active.remove();
            } else {
                active.set(previous);
            }
        }
    }

    @Override
    public Optional<User> findById(UserId id) {
        Objects.requireNonNull(id);
        return inUnit(() -> load(id).map(StoredUser::user));
    }

    private Optional<StoredUser> load(UserId id) {
        return jdbc.query("""
                SELECT u.user_id, u.status, u.version, p.nombre_usuario, p.nombre, p.apellidos,
                    p.correo, p.foto_perfil, p.registered_second, p.registered_nano,
                    p.updated_second, p.updated_nano, p.verificado
                FROM dbo.users u JOIN dbo.profiles p ON p.user_id = u.user_id
                WHERE u.user_id = ?
                """, (rs, row) -> new StoredUser(readUser(rs), rs.getLong("version")), id.value())
                .stream().findFirst();
    }

    static User readUser(ResultSet rs) throws SQLException {
        UserId id = new UserId(rs.getString("user_id"));
        UserProfile profile = new UserProfile(id,
                new ProfileDetails(rs.getString("nombre_usuario"), rs.getString("nombre"), rs.getString("apellidos")),
                new Email(rs.getString("correo")), URI.create(rs.getString("foto_perfil")),
                Instant.ofEpochSecond(rs.getLong("registered_second"), rs.getInt("registered_nano")),
                Instant.ofEpochSecond(rs.getLong("updated_second"), rs.getInt("updated_nano")),
                rs.getBoolean("verificado"));
        return new User(id, AccountStatus.valueOf(rs.getString("status")), profile);
    }

    @Override
    public void insert(User user) {
        Objects.requireNonNull(user);
        inUnit(() -> {
            try {
                jdbc.update("INSERT INTO dbo.users(user_id, status, version) VALUES (?, ?, 0)",
                        user.userId().value(), user.status().name());
            } catch (DuplicateKeyException duplicate) {
                throw new UserDomainException(USER_ALREADY_EXISTS);
            }
            UserProfile p = user.profile();
            jdbc.update("""
                    INSERT INTO dbo.profiles(user_id, nombre_usuario, nombre, apellidos, correo, foto_perfil,
                        registered_second, registered_nano, updated_second, updated_nano, verificado)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, user.userId().value(), p.details().nombreUsuario(), p.details().nombre(), p.details().apellidos(),
                    p.correo().address(), p.fotoPerfil().toString(), p.fechaRegistro().getEpochSecond(),
                    p.fechaRegistro().getNano(), p.fechaActualizacion().getEpochSecond(),
                    p.fechaActualizacion().getNano(), p.verificado());
            return null;
        });
    }

    @Override
    public void replace(User expected, User replacement) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(replacement);
        if (!expected.userId().equals(replacement.userId())) {
            throw new IllegalArgumentException("Cannot change account ownership");
        }
        inUnit(() -> {
            StoredUser current = load(expected.userId())
                    .filter(stored -> stored.user().equals(expected))
                    .orElseThrow(() -> new UserDomainException(CONCURRENT_MODIFICATION));
            int changed = jdbc.update("""
                    UPDATE dbo.users SET status = ?, version = version + 1 WHERE user_id = ? AND version = ?
                    """, replacement.status().name(), expected.userId().value(), current.version());
            if (changed != 1) {
                throw new UserDomainException(CONCURRENT_MODIFICATION);
            }
            UserProfile p = replacement.profile();
            int profiles = jdbc.update("""
                    UPDATE dbo.profiles SET nombre_usuario = ?, nombre = ?, apellidos = ?, correo = ?, foto_perfil = ?,
                        registered_second = ?, registered_nano = ?, updated_second = ?, updated_nano = ?, verificado = ?
                    WHERE user_id = ?
                    """, p.details().nombreUsuario(), p.details().nombre(), p.details().apellidos(), p.correo().address(),
                    p.fotoPerfil().toString(), p.fechaRegistro().getEpochSecond(), p.fechaRegistro().getNano(),
                    p.fechaActualizacion().getEpochSecond(), p.fechaActualizacion().getNano(), p.verificado(),
                    expected.userId().value());
            if (profiles != 1) {
                throw new UserPersistenceException("SQL aggregate profile is missing");
            }
            return null;
        });
    }

    @Override
    public Optional<ExternalIdentity> findByKey(ExternalIdentityKey key) {
        Objects.requireNonNull(key);
        return inUnit(() -> {
            var found = jdbc.query("""
                    SELECT issuer, subject, user_id, provider, verified_email FROM dbo.external_identities
                    WHERE issuer_hash = ? AND subject_hash = ?
                    """, (rs, row) -> new ExternalIdentity(IdentityProvider.valueOf(rs.getString("provider")),
                    new ExternalIdentityKey(rs.getString("issuer"), rs.getString("subject")),
                    new UserId(rs.getString("user_id")), new Email(rs.getString("verified_email"))),
                    SqlIdentityKey.hash(key.issuer()), SqlIdentityKey.hash(key.subject())).stream().findFirst();
            if (found.isPresent() && !found.get().key().equals(key)) {
                throw new UserPersistenceException("External identity digest collision");
            }
            return found;
        });
    }

    @Override
    public ExternalIdentity insertOrGet(ExternalIdentity identity) {
        Objects.requireNonNull(identity);
        return inUnit(() -> {
            var existing = findByKey(identity.key());
            if (existing.isPresent() && (!existing.get().userId().equals(identity.userId())
                    || existing.get().provider() != identity.provider())) {
                throw new UserDomainException(IDENTITY_CONFLICT);
            }
            User owner = findById(identity.userId()).orElseThrow(() -> new UserDomainException(USER_NOT_FOUND));
            owner.requireActive();
            if (existing.isPresent()) {
                return existing.get();
            }
            // Duplicate/range-lock/deadlock races escape to execute(), which rolls back and retries.
            jdbc.update("""
                    INSERT INTO dbo.external_identities(issuer, subject, user_id, provider, verified_email)
                    VALUES (?, ?, ?, ?, ?)
                    """, identity.key().issuer(), identity.key().subject(), identity.userId().value(),
                    identity.provider().name(), identity.verifiedEmail().address());
            return identity;
        });
    }

    static boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (SQLException next = sql; next != null; next = next.getNextException()) {
                    int code = next.getErrorCode();
                    if (code == 1205 || code == 1222 || code == 3960 || code == 41302
                            || code == 41305 || code == 41325 || code == 2601 || code == 2627) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(10L * attempt, 30L * attempt));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UserPersistenceException("SQL transaction retry interrupted");
        }
    }

    private record StoredUser(User user, long version) { }
}
