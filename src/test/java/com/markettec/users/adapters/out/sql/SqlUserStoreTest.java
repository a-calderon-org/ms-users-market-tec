package com.markettec.users.adapters.out.sql;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.domain.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Control-flow/mapping tests only. They do not claim SQL Server compatibility. */
class SqlUserStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final SqlUserStore store = new SqlUserStore(jdbc, manager);

    SqlUserStoreTest() {
        when(manager.getTransaction(any())).thenReturn(status);
    }

    @Test
    void commitsAtSerializableIsolation() {
        assertEquals("ok", store.execute(() -> "ok"));
        verify(manager).getTransaction(argThat(def -> def.getIsolationLevel() == TransactionDefinition.ISOLATION_SERIALIZABLE));
        verify(manager).commit(status);
        verify(manager, never()).rollback(any());
    }

    @Test
    void businessFailureRollsBackWithoutRetryOrTranslation() {
        var failure = new UserDomainException(UserDomainException.Reason.IDENTITY_CONFLICT);
        assertSame(failure, assertThrows(UserDomainException.class, () -> store.execute(() -> { throw failure; })));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void errorsAlsoRollBack() {
        assertThrows(AssertionError.class, () -> store.execute(() -> { throw new AssertionError(); }));
        verify(manager).rollback(status);
    }

    @Test
    void caughtPersistenceFailureCannotCommitHalfAnAggregate() {
        var rollbackOnly = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> { rollbackOnly.set(true); return null; }).when(status).setRollbackOnly();
        when(status.isRollbackOnly()).thenAnswer(invocation -> rollbackOnly.get());
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("private"));
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            try {
                store.insert(user());
            } catch (DataAccessResourceFailureException expected) {
                // Even a caller that catches a technical write failure cannot report success.
            }
            return "incorrect success";
        }));
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1205, 1222, 3960, 41302, 41305, 41325, 2601, 2627})
    void retriesWholeUnitAfterRollbackForDocumentedRaces(int code) {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(2, store.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new DuplicateKeyException("redacted", new SQLException("redacted", "40001", code));
            }
            return calls.get();
        }));
        var order = inOrder(manager);
        order.verify(manager).getTransaction(any());
        order.verify(manager).rollback(status);
        order.verify(manager).getTransaction(any());
        order.verify(manager).commit(status);
    }

    @Test
    void retriesAreBoundedAndDoNotExposeDriverMessages() {
        AtomicInteger calls = new AtomicInteger();
        var failure = assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            calls.incrementAndGet();
            throw new DuplicateKeyException("private account", new SQLException("private", "40001", 1205));
        }));
        assertEquals(4, calls.get());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private"));
        verify(manager, times(4)).rollback(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 18456, 40613, 2628})
    void connectivityAuthenticationAvailabilityAndDataFailuresAreNotReplayed(int code) {
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            throw new DataAccessResourceFailureException("private", new SQLException("private", "08S01", code));
        }));
        verify(manager).rollback(status);
        verify(manager, times(1)).getTransaction(any());
    }

    @Test
    void uncertainCommitIsNotReplayed() {
        doThrow(new TransactionSystemException("private")).when(manager).commit(status);
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> 1));
        verify(manager, times(1)).getTransaction(any());
    }

    @Test
    void translatedCommitFailureIsNeverReplayedEvenWithARetryableCode() {
        doThrow(new DuplicateKeyException("private", new SQLException("private", "40001", 1205)))
                .when(manager).commit(status);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(UserPersistenceException.class, () -> store.execute(calls::incrementAndGet));
        assertEquals(1, calls.get(), "A completed callback must not be replayed after a commit failure");
        verify(manager, times(1)).getTransaction(any());
    }

    @Test
    void caughtNestedSqlFailureCannotCommitTheOuterUnit() {
        TransactionStatus inner = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status, inner);
        var rollbackOnly = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> { rollbackOnly.set(true); return null; }).when(status).setRollbackOnly();
        when(status.isRollbackOnly()).thenAnswer(invocation -> rollbackOnly.get());
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            try {
                store.execute(() -> {
                    throw new DuplicateKeyException("deadlock", new SQLException("private", "40001", 1205));
                });
            } catch (org.springframework.dao.DataAccessException expected) {
                // The server may have rolled back every outer write, not just the nested scope.
            }
            return "unsafe success";
        }));
        verify(manager).rollback(inner);
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
    }

    @Test
    void failedNestedRollbackPoisonsOuterUnitEvenWhenCallerCatchesIt() {
        TransactionStatus inner = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status, inner);
        var rollbackOnly = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> { rollbackOnly.set(true); return null; }).when(status).setRollbackOnly();
        when(status.isRollbackOnly()).thenAnswer(invocation -> rollbackOnly.get());
        doThrow(new TransactionSystemException("savepoint rollback failed")).when(manager).rollback(inner);
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            try {
                store.execute(() -> { throw new IllegalStateException("inner write failed"); });
            } catch (RuntimeException expected) {
                // A failed savepoint rollback leaves the inner writes unaccounted for.
            }
            return "unsafe success";
        }));
        verify(manager).rollback(status);
        verify(manager, never()).commit(status);
    }

    @Test
    void mapsEveryDomainFieldIncludingNanosecondsAndOriginalEmail() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UserId id = new UuidUserIdGenerator().nextId();
        when(rs.getString("user_id")).thenReturn(id.value());
        when(rs.getString("status")).thenReturn("DEACTIVATED");
        when(rs.getString("nombre_usuario")).thenReturn("ana😀");
        when(rs.getString("nombre")).thenReturn("Ana");
        when(rs.getString("apellidos")).thenReturn("");
        when(rs.getString("correo")).thenReturn("Ana@Example.COM");
        when(rs.getString("foto_perfil")).thenReturn("https://example.invalid/a");
        when(rs.getLong("registered_second")).thenReturn(1L);
        when(rs.getInt("registered_nano")).thenReturn(123456789);
        when(rs.getLong("updated_second")).thenReturn(2L);
        when(rs.getInt("updated_nano")).thenReturn(987654321);
        when(rs.getBoolean("verificado")).thenReturn(true);
        User found = SqlUserStore.readUser(rs);
        assertEquals(AccountStatus.DEACTIVATED, found.status());
        assertEquals(id, found.profile().userId());
        assertEquals("Ana@Example.COM", found.profile().correo().address());
        assertEquals(new ProfileDetails("ana😀", "Ana", ""), found.profile().details());
        assertEquals(Instant.ofEpochSecond(1, 123456789), found.profile().fechaRegistro());
        assertEquals(Instant.ofEpochSecond(2, 987654321), found.profile().fechaActualizacion());
        assertTrue(found.profile().verificado());
    }

    @Test
    void keyDigestsPreserveCaseUnicodeAndPairBoundaries() throws Exception {
        String value = "subject😀é";
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_16LE)),
                SqlIdentityKey.hash(value));
        assertFalse(java.util.Arrays.equals(SqlIdentityKey.hash("Subject"), SqlIdentityKey.hash("subject")));
        assertFalse(java.util.Arrays.equals(SqlIdentityKey.hash("é"), SqlIdentityKey.hash("e\u0301")));
        assertEquals(32, SqlIdentityKey.hash("x".repeat(10000)).length);
    }

    @Test
    void cannotChangeOwnershipBeforeAccessingSql() {
        User first = user();
        assertThrows(IllegalArgumentException.class, () -> store.replace(first, user()));
        verifyNoInteractions(jdbc, manager);
    }

    @Test
    void invalidArgumentsDoNotStartTransactions() {
        assertThrows(NullPointerException.class, () -> store.findById(null));
        assertThrows(NullPointerException.class, () -> store.insert(null));
        assertThrows(NullPointerException.class, () -> store.findByKey(null));
        assertThrows(NullPointerException.class, () -> store.insertOrGet(null));
        verifyNoInteractions(jdbc, manager);
    }

    private User user() {
        return User.create(new UuidUserIdGenerator().nextId(), new Email("ana@example.com"),
                new ProfileDetails("ana", "Ana", ""), URI.create("https://example.invalid"), Instant.EPOCH);
    }
}
