package com.markettec.users.adapters.out.sql;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.in.UserUseCases;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.session.*;
import com.markettec.users.application.ports.out.IdentityLinkAuthorization;
import com.markettec.users.application.ports.out.UserIdGenerator;
import com.markettec.users.application.usecases.UserService;
import com.markettec.users.config.ApplicationConfig;
import com.markettec.users.config.SqlUserConfig;
import com.markettec.users.domain.model.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL Server only; opt in with -Psql-integration. No H2 and no disabled tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class SqlUserStoreIT {
    private final IsolatedSqlServer server = new IsolatedSqlServer();
    private DataSource source;
    private JdbcTemplate jdbc;
    private SqlUserStore store;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1700000000, 123456789), ZoneOffset.UTC);
    private final ProfileDetails details = new ProfileDetails("ana", "Ana😀", "Calderón");
    private final Email email = new Email("Ana@Example.COM");
    private final URI photo = URI.create("https://example.invalid/avatar");

    @BeforeAll
    @Timeout(300)
    void migrateOnlyFreshIsolatedDatabase() throws Exception {
        try {
            source = server.start();
            jdbc = new JdbcTemplate(source);
            assertEquals(IsolatedSqlServer.DATABASE, jdbc.queryForObject("SELECT DB_NAME()", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped=0", Integer.class));
            assertEquals("SQL_Latin1_General_CP1_CI_AS", jdbc.queryForObject(
                    "SELECT CONVERT(varchar(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation'))", String.class));
            Flyway flyway = Flyway.configure().dataSource(source).defaultSchema("dbo")
                    .baselineOnMigrate(false).cleanDisabled(true).load();
            assertEquals(2, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            SqlSchemaValidator.validate(source, IsolatedSqlServer.DATABASE);
            store = newStore();
        } catch (Exception | Error failure) {
            server.close();
            throw failure;
        }
    }

    @AfterAll
    void stopOnlyTheDisposableContainer() throws Exception {
        server.close();
    }

    @Test
    void persistsAndFindsEveryUserAndProfileFieldLosslessly() {
        User user = user();
        store.insert(user);
        User loaded = newStore().findById(user.userId()).orElseThrow();
        assertEquals(user, loaded);
        assertEquals(email.address(), loaded.profile().correo().address());
        assertEquals(123456789, loaded.profile().fechaRegistro().getNano());
        assertEquals(0L, version(user));
    }

    @Test
    void missingIdsAndIdentitiesReturnEmpty() {
        assertTrue(store.findById(user().userId()).isEmpty());
        assertTrue(store.findByKey(key()).isEmpty());
    }

    @Test
    void duplicateIdCannotOverwriteUserOrProfile() {
        User user = user();
        store.insert(user);
        reason(USER_ALREADY_EXISTS, () -> store.insert(user.deactivate()));
        assertEquals(user, store.findById(user.userId()).orElseThrow());
    }

    @Test
    void updatesOnlyTheProfileDetailsAndIncrementsVersion() {
        User user = service().createUser(email, details, photo);
        var changed = new ProfileDetails("ana2", "Ana María", "");
        var result = service().updateOwnProfile(user::userId, user.userId(), changed);
        assertEquals(changed, newStore().findById(user.userId()).orElseThrow().profile().details());
        assertEquals(user.profile().correo().address(), result.correo().address());
        assertEquals(photo, result.fotoPerfil());
        assertEquals(user.profile().fechaRegistro(), result.fechaRegistro());
        assertEquals(1L, version(user));
    }

    @Test
    void registersAndFindsIdentityAndKeepsOriginalEvidenceOnRetry() {
        var identity = verified();
        User first = service().registerVerifiedIdentity(identity, details, photo);
        var repeat = new VerifiedExternalIdentity(identity.provider(), identity.key(), new Email("other@example.com"));
        assertEquals(first, service().registerVerifiedIdentity(repeat, new ProfileDetails("other", "Other", ""), photo));
        ExternalIdentity loaded = newStore().findByKey(identity.key()).orElseThrow();
        assertEquals(first.userId(), loaded.userId());
        assertEquals(email.address(), loaded.verifiedEmail().address());
        assertTrue(first.profile().verificado());
    }

    @Test
    void approvedLinkIsIdempotentAndDoesNotOverwriteEvidence() {
        User owner = service().createUser(email, details, photo);
        var identity = verified();
        var authorized = service(grant(owner.userId(), identity));
        ExternalIdentity first = authorized.linkExternalIdentity(owner::userId, owner.userId(), identity);
        ExternalIdentity again = authorized.linkExternalIdentity(owner::userId, owner.userId(),
                new VerifiedExternalIdentity(identity.provider(), identity.key(), new Email("other@example.com")));
        assertEquals(first, again);
        assertTrue(store.findById(owner.userId()).orElseThrow().profile().verificado());
    }

    @Test
    void unapprovedLinkDoesNotWriteAnything() {
        User owner = service().createUser(email, details, photo);
        var identity = verified();
        reason(UNAUTHORIZED, () -> service().linkExternalIdentity(owner::userId, owner.userId(), identity));
        assertTrue(store.findByKey(identity.key()).isEmpty());
        assertEquals(0L, version(owner));
    }

    @Test
    void rejectsOwnerAndProviderConflictsWithoutReassignment() {
        User one = user();
        User two = user();
        store.insert(one);
        store.insert(two);
        ExternalIdentity original = new ExternalIdentity(IdentityProvider.GOOGLE, key(), one.userId(), email);
        store.insertOrGet(original);
        reason(IDENTITY_CONFLICT, () -> store.insertOrGet(new ExternalIdentity(original.provider(), original.key(), two.userId(), email)));
        reason(IDENTITY_CONFLICT, () -> store.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                original.key(), one.userId(), email)));
        assertEquals(original, store.findByKey(original.key()).orElseThrow());
    }

    @Test
    void databaseEnforcesUniquenessAcrossProvidersWithoutAdapterLookup() {
        User user = user();
        store.insert(user);
        var identity = new ExternalIdentity(IdentityProvider.GOOGLE, key(), user.userId(), email);
        store.insertOrGet(identity);
        assertThrows(DataIntegrityViolationException.class, () -> rawIdentity(identity.key(), user.userId(), "INSTITUTIONAL_EMAIL"));
    }

    @Test
    void identityKeysAreExactCaseSensitiveAndHaveNoLengthTruncation() {
        User user = user();
        store.insert(user);
        String unique = UUID.randomUUID().toString();
        List<ExternalIdentityKey> keys = List.of(new ExternalIdentityKey("Issuer" + unique, "Subject"),
                new ExternalIdentityKey("issuer" + unique, "Subject"),
                new ExternalIdentityKey("Issuer" + unique, "subject"),
                new ExternalIdentityKey("Issuer" + unique, "é"),
                new ExternalIdentityKey("Issuer" + unique, "e\u0301"),
                new ExternalIdentityKey("Issuer😀" + unique, "x".repeat(10000) + "😀"));
        for (var key : keys) {
            var identity = new ExternalIdentity(IdentityProvider.GOOGLE, key, user.userId(), email);
            assertEquals(identity, store.insertOrGet(identity));
            assertEquals(identity, newStore().findByKey(key).orElseThrow());
        }
    }

    @Test
    void databaseRejectsOrphanProfilesAndIdentities() {
        UserId missing = user().userId();
        assertThrows(DataIntegrityViolationException.class, () -> rawIdentity(key(), missing, "GOOGLE"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO dbo.profiles(user_id,nombre_usuario,nombre,apellidos,correo,foto_perfil,
                    registered_second,registered_nano,updated_second,updated_nano,verificado)
                VALUES (?,'ana','Ana','','ana@example.com','https://example.invalid',0,0,0,0,0)
                """, missing.value()));
    }

    @Test
    void adapterRejectsMissingAndDeactivatedOwners() {
        User owner = user();
        ExternalIdentity identity = new ExternalIdentity(IdentityProvider.GOOGLE, key(), owner.userId(), email);
        reason(USER_NOT_FOUND, () -> store.insertOrGet(identity));
        store.insert(owner.deactivate());
        reason(USER_DEACTIVATED, () -> store.insertOrGet(identity));
    }

    @Test
    void commitsUserProfileAndIdentityAtomically() {
        User user = user();
        ExternalIdentity identity = new ExternalIdentity(IdentityProvider.GOOGLE, key(), user.userId(), email);
        store.execute(() -> {
            store.insert(user);
            store.insertOrGet(identity);
            store.replace(user, user.verifyProfileEmail(email));
            return null;
        });
        assertTrue(newStore().findById(user.userId()).orElseThrow().profile().verificado());
        assertEquals(identity, newStore().findByKey(identity.key()).orElseThrow());
    }

    @Test
    void rollsBackAllTablesAfterFailureFollowingAllWrites() {
        User user = user();
        var identity = new ExternalIdentity(IdentityProvider.GOOGLE, key(), user.userId(), email);
        assertThrows(IllegalStateException.class, () -> store.execute(() -> {
            store.insert(user);
            store.insertOrGet(identity);
            store.replace(user, user.deactivate());
            throw new IllegalStateException("injected failure");
        }));
        assertTrue(newStore().findById(user.userId()).isEmpty());
        assertTrue(newStore().findByKey(identity.key()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.profiles WHERE user_id=?", Integer.class, user.userId().value()));
    }

    @Test
    void failedAggregateUpdateRestoresProfileStatusAndVersion() {
        User user = user();
        store.insert(user);
        assertThrows(IllegalStateException.class, () -> store.execute(() -> {
            store.replace(user, user.updateProfile(new ProfileDetails("new", "New", ""), clock.instant()).deactivate());
            throw new IllegalStateException("injected failure");
        }));
        assertEquals(user, store.findById(user.userId()).orElseThrow());
        assertEquals(0L, version(user));
    }

    @Test
    void swallowedProfileInsertFailureStillRollsBackTheUserRow() {
        JdbcTemplate failingJdbc = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO dbo.profiles")) {
                    throw new DataAccessResourceFailureException("injected profile failure");
                }
                return super.update(sql, args);
            }
        };
        var failingStore = new SqlUserStore(failingJdbc, new DataSourceTransactionManager(source));
        User user = user();
        assertThrows(UserPersistenceException.class, () -> failingStore.execute(() -> {
            try { failingStore.insert(user); }
            catch (DataAccessException expected) { /* The outer unit must still fail closed. */ }
            return null;
        }));
        assertTrue(store.findById(user.userId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users WHERE user_id=?", Integer.class, user.userId().value()));
    }

    @Test
    void nestedUnitRollsBackToSavepointWithoutLosingOuterWrites() {
        User outer = user();
        User inner = user();
        store.execute(() -> {
            store.insert(outer);
            assertThrows(IllegalStateException.class, () -> store.execute(() -> {
                store.insert(inner);
                throw new IllegalStateException("nested failure");
            }));
            return null;
        });
        assertTrue(store.findById(outer.userId()).isPresent());
        assertTrue(store.findById(inner.userId()).isEmpty());
    }

    @Test
    void staleAggregateCannotOverwriteCommittedChanges() {
        User before = user();
        store.insert(before);
        User after = before.updateProfile(new ProfileDetails("after", "After", ""), clock.instant());
        store.replace(before, after);
        reason(CONCURRENT_MODIFICATION, () -> newStore().replace(before, before.deactivate()));
        assertEquals(after, store.findById(before.userId()).orElseThrow());
        assertEquals(1L, version(before));
    }

    @Test
    void concurrentUpdatesHaveOneWinnerAndOneStaleWriter() throws Exception {
        User before = user();
        store.insert(before);
        var gate = new CyclicBarrier(2);
        Supplier<String> update = () -> {
            await(gate);
            try {
                newStore().replace(before, before.updateProfile(new ProfileDetails(UUID.randomUUID().toString(), "Changed", ""), clock.instant()));
                return "saved";
            } catch (UserDomainException failure) {
                return failure.reason().name();
            }
        };
        List<String> results = concurrent(update, update);
        assertTrue(results.contains("saved"));
        assertTrue(results.contains(CONCURRENT_MODIFICATION.name()));
        assertEquals(1L, version(before));
    }

    @Test
    void concurrentRegistrationsRetryWholeWorkWithoutOrphanAccounts() throws Exception {
        var identity = verified();
        var gate = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        var generated = new ConcurrentLinkedQueue<UserId>();
        UserIdGenerator ids = () -> {
            if (calls.incrementAndGet() <= 2) {
                await(gate); // Both transactions have read an absent identity before either inserts.
            }
            UserId id = new UuidUserIdGenerator().nextId();
            generated.add(id);
            return id;
        };
        var firstStore = newStore();
        var first = new UserService(firstStore, firstStore, ids, firstStore, deny(), clock);
        var secondStore = newStore();
        var second = new UserService(secondStore, secondStore, ids, secondStore, deny(), clock);
        final UserService serviceOne = first;
        var results = concurrent(() -> serviceOne.registerVerifiedIdentity(identity, details, photo),
                () -> second.registerVerifiedIdentity(identity, details, photo));
        assertEquals(results.get(0), results.get(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.profiles WHERE correo=? AND verificado=1 AND user_id=?",
                Integer.class, email.address(), results.get(0).userId().value()));
        // Every generated loser ID must have rolled back, including its profile.
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.users u LEFT JOIN dbo.profiles p ON p.user_id=u.user_id WHERE p.user_id IS NULL
                """, Integer.class));
        assertTrue(calls.get() >= 2);
        for (UserId id : generated) {
            assertEquals(id.equals(results.get(0).userId()), store.findById(id).isPresent());
            assertEquals(id.equals(results.get(0).userId()) ? 1 : 0,
                    jdbc.queryForObject("SELECT COUNT(*) FROM dbo.profiles WHERE user_id=?", Integer.class, id.value()));
        }
    }

    @Test
    void deactivationIsIdempotentAndKeepsIdentityReserved() {
        var identity = verified();
        User user = service().registerVerifiedIdentity(identity, details, photo);
        User inactive = service().deactivateUser(user::userId, user.userId());
        assertEquals(inactive, service().deactivateUser(user::userId, user.userId()));
        reason(USER_DEACTIVATED, () -> service().registerVerifiedIdentity(identity, details, photo));
        reason(USER_DEACTIVATED, () -> service().getOwnProfile(user::userId, user.userId()));
        assertEquals(user.userId(), store.findByKey(identity.key()).orElseThrow().userId());
    }

    @Test
    void linkingAndDeactivationSerializeAcrossConnections() throws Exception {
        User owner = service().createUser(email, details, photo);
        var identity = verified();
        var gate = new CyclicBarrier(2);
        var linkStore = newStore();
        var deactivateStore = newStore();
        var linking = new UserService(linkStore, linkStore, new UuidUserIdGenerator(), linkStore,
                grant(owner.userId(), identity), clock);
        var deactivating = new UserService(deactivateStore, deactivateStore, new UuidUserIdGenerator(),
                deactivateStore, deny(), clock);
        var results = concurrent(() -> {
            await(gate);
            try {
                linking.linkExternalIdentity(owner::userId, owner.userId(), identity);
                return "linked";
            } catch (UserDomainException failure) {
                return failure.reason().name();
            }
        }, () -> {
            await(gate);
            deactivating.deactivateUser(owner::userId, owner.userId());
            return "deactivated";
        });
        assertEquals(AccountStatus.DEACTIVATED, store.findById(owner.userId()).orElseThrow().status());
        assertTrue(results.contains("deactivated"));
        assertTrue(results.contains("linked") || results.contains(USER_DEACTIVATED.name()));
        assertEquals(results.contains("linked"), store.findByKey(identity.key()).isPresent());
    }

    @Test
    void concurrentLinksToDifferentOwnersHaveExactlyOneWinner() throws Exception {
        User one = service().createUser(email, details, photo);
        User two = service().createUser(email, details, photo);
        var identity = verified();
        var gate = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        IdentityLinkAuthorization authorization = (principal, target, approved) -> {
            if (calls.incrementAndGet() <= 2) {
                await(gate);
            }
        };
        var firstStore = newStore();
        var secondStore = newStore();
        var first = new UserService(firstStore, firstStore, new UuidUserIdGenerator(), firstStore, authorization, clock);
        var second = new UserService(secondStore, secondStore, new UuidUserIdGenerator(), secondStore, authorization, clock);
        List<String> results = concurrent(() -> linkResult(first, one, identity), () -> linkResult(second, two, identity));
        assertEquals(1, results.stream().filter("linked"::equals).count());
        assertEquals(1, results.stream().filter(IDENTITY_CONFLICT.name()::equals).count());
        ExternalIdentity persisted = store.findByKey(identity.key()).orElseThrow();
        assertTrue(persisted.userId().equals(one.userId()) || persisted.userId().equals(two.userId()));
        assertEquals(persisted.userId().equals(one.userId()) ? 1L : 0L, version(one));
        assertEquals(persisted.userId().equals(two.userId()) ? 1L : 0L, version(two));
    }

    @Test
    void realSqlProfileFailureIsTranslatedAndRollsBackTheEarlierUserInsert() {
        JdbcTemplate rejectingProfile = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.contains("INSERT INTO dbo.profiles")) {
                    args = args.clone();
                    args[1] = "x".repeat(161); // Actual SQL Server truncation, after the user row was inserted.
                }
                return super.update(sql, args);
            }
        };
        var failing = new SqlUserStore(rejectingProfile, new DataSourceTransactionManager(source));
        User user = user();
        var failure = assertThrows(UserPersistenceException.class, () -> failing.insert(user));
        assertNull(failure.getCause());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users WHERE user_id=?", Integer.class, user.userId().value()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.profiles WHERE user_id=?", Integer.class, user.userId().value()));
    }

    @Test
    void caughtNestedSqlFailureCannotCommitEarlierOuterWrites() {
        User outer = user();
        UserId missing = user().userId();
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            store.insert(outer);
            try {
                store.execute(() -> {
                    rawIdentity(key(), missing, "GOOGLE"); // Real FK violation inside a savepoint.
                    return null;
                });
            } catch (DataAccessException expected) {
                // A technical nested failure conservatively invalidates the complete unit.
            }
            return null;
        }));
        assertTrue(newStore().findById(outer.userId()).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users WHERE user_id=?", Integer.class, outer.userId().value()));
    }

    @Test
    void accountAndIdentityContractMatchesTheInMemoryAdapter() {
        UserId id = new UuidUserIdGenerator().nextId();
        var identity = verified();
        var memory = new InMemoryUserStore();
        var inMemory = new UserService(memory, memory, () -> id, memory, deny(), clock);
        var sql = new UserService(store, store, () -> id, store, deny(), clock);
        User expected = inMemory.registerVerifiedIdentity(identity, details, photo);
        assertEquals(expected, sql.registerVerifiedIdentity(identity, details, photo));
        assertEquals(inMemory.registerVerifiedIdentity(identity, details, photo), sql.registerVerifiedIdentity(identity, details, photo));
        var changed = new ProfileDetails("other", "Other", "");
        assertEquals(inMemory.updateOwnProfile(() -> id, id, changed), sql.updateOwnProfile(() -> id, id, changed));
        assertEquals(inMemory.deactivateUser(() -> id, id), sql.deactivateUser(() -> id, id));
        assertEquals(memory.findById(id), store.findById(id));
        assertEquals(memory.findByKey(identity.key()), store.findByKey(identity.key()));
        reason(USER_DEACTIVATED, () -> inMemory.registerVerifiedIdentity(identity, details, photo));
        reason(USER_DEACTIVATED, () -> sql.registerVerifiedIdentity(identity, details, photo));
    }

    @Test
    void retainsCommittedAccountAndIdentityAfterDatabaseEngineRestart() throws Exception {
        var identity = verified();
        User committed = service().registerVerifiedIdentity(identity, details, photo);
        server.restart();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(60)).ignoreExceptions()
                .untilAsserted(() -> assertEquals(committed, newStore().findById(committed.userId()).orElseThrow()));
        assertEquals(committed.userId(), newStore().findByKey(identity.key()).orElseThrow().userId());
    }

    private String linkResult(UserService service, User owner, VerifiedExternalIdentity identity) {
        try {
            service.linkExternalIdentity(owner::userId, owner.userId(), identity);
            return "linked";
        } catch (UserDomainException failure) {
            return failure.reason().name();
        }
    }

    @Test
    void recoversInformationAfterSpringContextRestart() {
        User user;
        try (var first = context()) {
            user = first.getBean(UserUseCases.class).createUser(email, details, photo);
        }
        try (var second = context()) {
            var recovered = second.getBean(UserUseCases.class).getOwnProfile(user::userId, user.userId());
            assertEquals(user.profile(), recovered);
        }
    }

    @Test
    void schemaValidationRejectsWrongDatabaseWithoutWriting() {
        assertThrows(UserPersistenceException.class, () -> SqlSchemaValidator.validate(source, "wrong_database"));
    }

    @Test
    void flywayHistoryRecordsExactlyOneSuccessfulV1AndValidatesItsChecksum() {
        var flyway = Flyway.configure().dataSource(source).defaultSchema("dbo").cleanDisabled(true).load();
        flyway.validate();
        assertEquals("2", flyway.info().current().getVersion().getVersion());
        assertEquals(2, flyway.info().applied().length);
        assertEquals(0, flyway.info().pending().length);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.flyway_schema_history", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='1' AND script='V1__initial_users_schema.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='2' AND script='V2__sessions.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
    }

    @Test
    void sessionCreationRenewalAndExpiryPersistOnRealSqlServer() {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        service().registerVerifiedIdentity(proof, details, photo);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Instant start = Instant.parse("2026-11-03T12:00:00Z");
        java.util.function.Function<Instant, SessionAuthority> authorityAt = instant -> new SessionAuthority(
                new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom(), permit);

        Session renewed = authorityAt.apply(start).create(proof);
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, renewed.sessionId()));
        authorityAt.apply(start.plusSeconds(20 * 60)).recordAdmissibleActivity(renewed.sessionId());
        Session afterRenewal = authorityAt.apply(start.plusSeconds(25 * 60)).renew(renewed.sessionId());
        assertEquals(start.plusSeconds(85 * 60), afterRenewal.renewableUntil());
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, renewed.sessionId()));
        assertTrue(authorityAt.apply(start.plusSeconds(50 * 60 - 1)).current(renewed.sessionId()).isPresent());
        assertTrue(authorityAt.apply(start.plusSeconds(50 * 60)).current(renewed.sessionId()).isEmpty());
        assertThrows(SessionRejectedException.class,
                () -> authorityAt.apply(start.plusSeconds(50 * 60)).renew(renewed.sessionId()));

        Session window = authorityAt.apply(start).create(proof);
        authorityAt.apply(start.plusSeconds(20 * 60)).recordAdmissibleActivity(window.sessionId());
        authorityAt.apply(start.plusSeconds(40 * 60)).recordAdmissibleActivity(window.sessionId());
        assertTrue(authorityAt.apply(start.plusSeconds(60 * 60 - 1)).current(window.sessionId()).isPresent());
        assertTrue(authorityAt.apply(start.plusSeconds(60 * 60)).current(window.sessionId()).isEmpty());
        assertThrows(SessionRejectedException.class,
                () -> authorityAt.apply(start.plusSeconds(60 * 60)).recordAdmissibleActivity(window.sessionId()));
    }

    @Test
    void sessionRevocationAndRenewalSerializeOnRealSqlServer() throws Exception {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        service().registerVerifiedIdentity(proof, details, photo);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Clock sessionClock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
        Supplier<SessionAuthority> authority = () -> new SessionAuthority(
                new SqlSessionRepository(jdbc), store, store, store, sessionClock,
                new java.security.SecureRandom(), permit);
        Session session = authority.get().create(proof);
        assertTrue(authority.get().current(session.sessionId()).isPresent());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var renewal = pool.submit(() -> {
                gate.await();
                try { authority.get().renew(session.sessionId()); }
                catch (SessionRejectedException ignored) { }
                return null;
            });
            var revocation = pool.submit(() -> { gate.await(); authority.get().revoke(session.sessionId()); return null; });
            gate.countDown();
            renewal.get(30, TimeUnit.SECONDS);
            revocation.get(30, TimeUnit.SECONDS);
            assertTrue(authority.get().current(session.sessionId()).isEmpty());
            assertThrows(SessionRejectedException.class, () -> authority.get().renew(session.sessionId()));
            assertTrue(authority.get().current(session.sessionId()).isEmpty());
        } finally { pool.shutdownNow(); }
    }

    @Test
    void actualJdbcUnitUsesSerializableIsolationWithoutAnAmbientTransaction() {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        store.execute(() -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(4, jdbc.queryForObject("""
                    SELECT transaction_isolation_level FROM sys.dm_exec_sessions WHERE session_id=@@SPID
                    """, Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT XACT_STATE()", Integer.class));
            return null;
        });
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void realDeadlockRollsBackAndRetriesTheCompleteUnit() throws Exception {
        User one = user();
        User two = user();
        store.insert(one);
        store.insert(two);
        var gate = new CyclicBarrier(2);
        var calls = new AtomicInteger();
        var first = newStore();
        var second = newStore();
        var results = concurrent(() -> deadlockingUpdate(first, one, two, gate, calls),
                () -> deadlockingUpdate(second, two, one, gate, calls));
        assertEquals(List.of("saved", "saved"), results);
        assertTrue(calls.get() >= 3, "Opposing shared/exclusive locks must produce a rolled-back victim and retry");
        assertEquals(1L, version(one));
        assertEquals(1L, version(two));
        assertEquals(AccountStatus.DEACTIVATED, store.findById(one.userId()).orElseThrow().status());
        assertEquals(AccountStatus.DEACTIVATED, store.findById(two.userId()).orElseThrow().status());
    }

    private String deadlockingUpdate(SqlUserStore unit, User held, User target, CyclicBarrier gate, AtomicInteger calls) {
        return unit.execute(() -> {
            unit.findById(held.userId()).orElseThrow();
            if (calls.incrementAndGet() <= 2) { await(gate); }
            User current = unit.findById(target.userId()).orElseThrow();
            unit.replace(current, current.deactivate());
            return "saved";
        });
    }

    @Test
    void errorReportedAfterRealCommitNeverReplaysCommittedWork() {
        var manager = new DataSourceTransactionManager(source) {
            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new DataAccessResourceFailureException("injected failure after real commit",
                        new java.sql.SQLException("synthetic retryable code", "40001", 1205));
            }
        };
        var uncertain = new SqlUserStore(new JdbcTemplate(source), manager);
        var calls = new AtomicInteger();
        User user = user();
        assertThrows(UserPersistenceException.class, () -> uncertain.execute(() -> {
            calls.incrementAndGet();
            uncertain.insert(user);
            return null;
        }));
        assertEquals(1, calls.get());
        assertEquals(user, newStore().findById(user.userId()).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.profiles WHERE user_id=?", Integer.class, user.userId().value()));
    }

    @Test
    void restrictedRuntimeCanValidateAndPersistWithoutDdlOrDeletePermissions() throws Exception {
        var runtime = server.restrictedRuntimeSource();
        SqlSchemaValidator.validate(runtime, IsolatedSqlServer.DATABASE);
        var restrictedJdbc = new JdbcTemplate(runtime);
        var restricted = new SqlUserStore(restrictedJdbc, new DataSourceTransactionManager(runtime));
        var application = new UserService(restricted, restricted, new UuidUserIdGenerator(), restricted, deny(), clock);
        var identity = verified();
        User user = application.registerVerifiedIdentity(identity, details, photo);
        assertEquals(user, restricted.findById(user.userId()).orElseThrow());
        assertEquals(user.userId(), restricted.findByKey(identity.key()).orElseThrow().userId());
        application.updateOwnProfile(user::userId, user.userId(), new ProfileDetails("runtime", "Runtime", ""));
        application.deactivateUser(user::userId, user.userId());
        assertEquals(0, restrictedJdbc.queryForObject("SELECT IS_MEMBER('db_owner')", Integer.class));
        assertEquals(0, restrictedJdbc.queryForObject("SELECT HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'CREATE TABLE')", Integer.class));
        assertEquals(0, restrictedJdbc.queryForObject("SELECT HAS_PERMS_BY_NAME('dbo', 'SCHEMA', 'ALTER')", Integer.class));
        assertEquals(0, restrictedJdbc.queryForObject("SELECT HAS_PERMS_BY_NAME('dbo.users', 'OBJECT', 'DELETE')", Integer.class));
        assertThrows(DataAccessException.class, () -> restrictedJdbc.execute("CREATE TABLE dbo.forbidden_runtime_table(id int)"));
        assertThrows(DataAccessException.class, () -> restrictedJdbc.update("DELETE FROM dbo.users WHERE user_id=?", user.userId().value()));
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("sql-integration");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated", Map.of(
                "users.sql.database", IsolatedSqlServer.DATABASE, "spring.application.name", "ms-users")));
        context.registerBean(DataSource.class, () -> source);
        context.register(ApplicationConfig.class, SqlUserConfig.class);
        context.refresh();
        return context;
    }

    private User user() {
        return User.create(new UuidUserIdGenerator().nextId(), email, details, photo, clock.instant());
    }

    private ExternalIdentityKey key() { return new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()); }
    private VerifiedExternalIdentity verified() { return new VerifiedExternalIdentity(IdentityProvider.GOOGLE, key(), email); }
    private SqlUserStore newStore() { return new SqlUserStore(new JdbcTemplate(source), new DataSourceTransactionManager(source)); }
    private UserService service() { return service(deny()); }
    private UserService service(IdentityLinkAuthorization authorization) {
        return new UserService(store, store, new UuidUserIdGenerator(), store, authorization, clock);
    }
    private IdentityLinkAuthorization deny() {
        return (principal, target, identity) -> { throw new UserDomainException(UNAUTHORIZED); };
    }
    private IdentityLinkAuthorization grant(UserId owner, VerifiedExternalIdentity approved) {
        return (principal, target, identity) -> {
            if (!owner.equals(principal.userId()) || !owner.equals(target) || !approved.key().equals(identity.key())
                    || approved.provider() != identity.provider()) {
                throw new UserDomainException(UNAUTHORIZED);
            }
        };
    }
    private long version(User user) {
        return jdbc.queryForObject("SELECT version FROM dbo.users WHERE user_id=?", Long.class, user.userId().value());
    }
    private void rawIdentity(ExternalIdentityKey key, UserId owner, String provider) {
        jdbc.update("INSERT INTO dbo.external_identities(issuer,subject,user_id,provider,verified_email) VALUES (?,?,?,?,?)",
                key.issuer(), key.subject(), owner.value(), provider, email.address());
    }
    private void reason(UserDomainException.Reason reason, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(UserDomainException.class, operation).reason());
    }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(15, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static <T> List<T> concurrent(Supplier<T> first, Supplier<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(first::get);
            var two = executor.submit(second::get);
            return List.of(one.get(60, TimeUnit.SECONDS), two.get(60, TimeUnit.SECONDS));
        }
    }
}
