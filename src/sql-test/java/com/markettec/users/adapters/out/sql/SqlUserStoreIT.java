package com.markettec.users.adapters.out.sql;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.in.UserUseCases;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.session.*;
import com.markettec.users.application.ports.out.IdentityLinkAuthorization;
import com.markettec.users.application.ports.out.ExternalIdentityRepository;
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Properties;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL Server only; opt in with -Psql-integration. No H2 and no disabled tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class SqlUserStoreIT {
    private static final String HISTORICAL_USER = "usr_00000000-0000-4000-8000-000000000000";
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static CsrfKeys csrfKeys() {
        return CsrfKeys.fromJson("{\"sql-test\":\"" + TEST_KEY + "\"}", "sql-test");
    }
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
            Flyway throughV9 = Flyway.configure().dataSource(source).defaultSchema("dbo")
                    .target("9").baselineOnMigrate(false).cleanDisabled(true).load();
            assertEquals(9, throughV9.migrate().migrationsExecuted);
            long created = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli();
            jdbc.update("INSERT INTO dbo.users(user_id,status) VALUES (?,'ACTIVE')", HISTORICAL_USER);
            jdbc.update("""
                INSERT INTO dbo.profiles(user_id,nombre_usuario,nombre,apellidos,correo,foto_perfil,
                    registered_second,registered_nano,updated_second,updated_nano,verificado)
                VALUES (?,?,?,?,?,?,0,0,0,0,0)
                """, HISTORICAL_USER, "historical", "Historical", "Session",
                    "historical@example.invalid", "https://example.invalid/historical");
            for (int version = 0; version <= 1; version++) {
                jdbc.update("""
                    INSERT INTO dbo.sessions(session_id,user_id,created_ms,renewable_until_ms,
                        last_activity_ms,idle_until_ms,absolute_until_ms,revoked,version)
                    VALUES (?,?,?,?,?,?,?,0,?)
                    """, version == 0 ? "a".repeat(32) : "b".repeat(32), HISTORICAL_USER, created,
                        created + 3_600_000, created, created + 1_800_000,
                        created + 28_800_000, version);
            }
            Flyway flyway = Flyway.configure().dataSource(source).defaultSchema("dbo")
                    .baselineOnMigrate(false).cleanDisabled(true).load();
            assertEquals(4, flyway.migrate().migrationsExecuted);
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
        assertEquals("13", flyway.info().current().getVersion().getVersion());
        assertEquals(13, flyway.info().applied().length);
        assertEquals(0, flyway.info().pending().length);
        assertEquals(13, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.flyway_schema_history", Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='1' AND script='V1__initial_users_schema.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='2' AND script='V2__sessions.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='3' AND script='V3__recent_authentication_attempts.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='4' AND script='V4__google_login_intents.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='5' AND script='V5__external_identity_authorization.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='6' AND script='V6__google_intent_purpose.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='7' AND script='V7__session_credentials.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='8' AND script='V8__session_credential_generations.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='9' AND script='V9__session_csrf_key_id.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.flyway_schema_history
                WHERE [version]='11' AND script='V11__pending_google_registrations.sql' AND success=1 AND checksum IS NOT NULL
                """, Integer.class));
    }

    @Test
    void migrationRetainsUnknownHistoricalRenewalUntilExplicitRenewal() {
        var repository = new SqlSessionRepository(jdbc);
        String neverRenewed = "a".repeat(32);
        String uncertain = "b".repeat(32);
        Session known = store.execute(() -> repository.loadLocked(neverRenewed).orElseThrow());
        Session unknown = store.execute(() -> repository.loadLocked(uncertain).orElseThrow());
        assertEquals(known.createdAt(), known.requireRenewedAt());
        assertNull(unknown.renewedAt());
        assertThrows(SessionRejectedException.class, unknown::requireRenewedAt);
        assertEquals(0L, known.version());
        assertEquals(1L, unknown.version());
        Instant now = unknown.createdAt().plusSeconds(60);
        store.execute(() -> {
            Session before = repository.loadLocked(uncertain).orElseThrow();
            repository.replace(before, before.activityAt(now));
            return null;
        });
        assertNull(store.execute(() -> repository.loadLocked(uncertain).orElseThrow()).renewedAt());
        store.execute(() -> {
            Session before = repository.loadLocked(uncertain).orElseThrow();
            repository.replace(before, before.renewAt(now.plusSeconds(1)));
            return null;
        });
        assertEquals(now.plusSeconds(1), store.execute(() -> repository.loadLocked(uncertain).orElseThrow())
                .requireRenewedAt());
        assertThrows(IllegalStateException.class, () -> store.execute(() -> {
            Session before = repository.loadLocked(uncertain).orElseThrow();
            repository.replace(before, before.renewAt(now.plusSeconds(2)));
            throw new IllegalStateException("rollback");
        }));
        assertEquals(now.plusSeconds(1), store.execute(() -> repository.loadLocked(uncertain).orElseThrow())
                .requireRenewedAt());
    }

    @Test
    void sessionCreationRenewalAndExpiryPersistOnRealSqlServer() {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        seedGoogle(proof, Instant.parse("2026-11-03T12:00:00Z"));
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Instant start = Instant.parse("2026-11-03T12:00:00Z");
        java.util.function.Function<Instant, SessionAuthority> authorityAt = instant -> new SessionAuthority(
                new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(instant, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());

        Session renewed = authorityAt.apply(start).create(proof);
        assertEquals(start, renewed.requireRenewedAt());
        assertEquals(start.toEpochMilli(), jdbc.queryForObject("SELECT renewed_ms FROM dbo.sessions WHERE session_id=?",
                Long.class, renewed.sessionId()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, renewed.sessionId()));
        authorityAt.apply(start.plusSeconds(20 * 60)).recordAdmissibleActivity(renewed.sessionId());
        assertEquals(start, authorityAt.apply(start.plusSeconds(20 * 60)).current(renewed.sessionId())
                .orElseThrow().requireRenewedAt());
        Session afterRenewal = authorityAt.apply(start.plusSeconds(25 * 60)).renew(renewed.sessionId());
        assertEquals(start.plusSeconds(25 * 60), afterRenewal.requireRenewedAt());
        assertEquals(afterRenewal.requireRenewedAt(), store.execute(() -> new SqlSessionRepository(jdbc)
                .loadLocked(renewed.sessionId()).orElseThrow()).requireRenewedAt());
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
        seedGoogle(proof, Instant.parse("2026-11-03T12:00:00Z"));
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Clock sessionClock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
        Supplier<SessionAuthority> authority = () -> new SessionAuthority(
                new SqlSessionRepository(jdbc), store, store, store, sessionClock,
                new java.security.SecureRandom(), permit, csrfKeys());
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
    void opaqueRefreshRotatesOnceAcrossConcurrentSqlTransactionsAndRevocation() throws Exception {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Supplier<SessionAuthority> authority = () -> new SessionAuthority(new SqlSessionRepository(jdbc),
                store, store, store, Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.get().issue(authority.get().createAuthenticated(proof), a -> "jwt");
        String stored = jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                String.class, issued.sessionId());
        assertEquals(64, stored.trim().length());
        assertFalse(stored.contains(issued.refreshToken()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.session_credential_generations
                WHERE session_id=? AND generation=0 AND consumed_ms IS NULL
                """, Integer.class, issued.sessionId()));
        assertEquals("sql-test", jdbc.queryForObject("""
                SELECT csrf_key_id FROM dbo.session_credential_generations
                WHERE session_id=? AND generation=0
                """, String.class, issued.sessionId()));
        var beforeRecovery = authority.get().current(issued.sessionId()).orElseThrow();
        assertEquals(issued.csrfToken(), authority.get().recoverCsrf(issued.refreshToken()));
        assertEquals(beforeRecovery, authority.get().current(issued.sessionId()).orElseThrow());
        authority.get().requireCsrf(issued.sessionId(), new UserId(issued.userId()), issued.csrfToken());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<SessionAuthority.RefreshedSession> action = () -> {
                gate.await();
                try { return authority.get().refreshWithSnapshot(issued.refreshToken(), issued.csrfToken(), a -> "jwt2"); }
                catch (SessionRejectedException rejected) { return null; }
            };
            var a = pool.submit(action); var b = pool.submit(action);
            gate.countDown();
            var first = a.get(30, TimeUnit.SECONDS);
            var second = b.get(30, TimeUnit.SECONDS);
            assertEquals(1, (first == null ? 0 : 1) + (second == null ? 0 : 1));
            var winner = (first == null ? second : first).tokens();
            assertEquals(2, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=?
                    """, Integer.class, issued.sessionId()));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM dbo.session_credential_generations
                    WHERE session_id=? AND generation=0 AND consumed_ms IS NOT NULL
                    """, Integer.class, issued.sessionId()));
            assertEquals(1L, jdbc.queryForObject("""
                    SELECT current_credential_generation FROM dbo.sessions WHERE session_id=?
                    """, Long.class, issued.sessionId()));
            assertEquals("sql-test", jdbc.queryForObject("""
                    SELECT csrf_key_id FROM dbo.session_credential_generations
                    WHERE session_id=? AND generation=1
                    """, String.class, issued.sessionId()));
            assertEquals(winner.csrfToken(), authority.get().recoverCsrf(winner.refreshToken()));
            assertEquals(beforeRecovery, authority.get().current(issued.sessionId()).orElseThrow());
            assertThrows(SessionRejectedException.class, () -> authority.get().recoverCsrf(issued.refreshToken()));
            assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                    assertThrows(SessionRejectedException.class, () -> authority.get().refresh(
                            issued.refreshToken(), issued.csrfToken(), a2 -> "jwt3")).reason());
            assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                    assertThrows(SessionRejectedException.class, () -> authority.get().refreshWithSnapshot(
                            issued.refreshToken(), issued.csrfToken(), a2 -> "jwt3")).reason());
            assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                    assertThrows(SessionRejectedException.class, () -> authority.get().refresh(
                            issued.refreshToken(), winner.csrfToken(), a2 -> "jwt3")).reason());
            assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                    assertThrows(SessionRejectedException.class, () -> authority.get().refresh(
                            "0".repeat(64), issued.csrfToken(), a2 -> "jwt3")).reason());
            assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                    assertThrows(SessionRejectedException.class, () -> authority.get().refreshWithSnapshot(
                            issued.refreshToken(), winner.csrfToken(), a2 -> "jwt3")).reason());
            assertEquals(SessionRejectedException.Reason.PROOF_INVALID,
                    assertThrows(SessionRejectedException.class, () -> authority.get().refreshWithSnapshot(
                            "0".repeat(64), issued.csrfToken(), a2 -> "jwt3")).reason());
            assertThrows(SessionRejectedException.class,
                    () -> authority.get().requireCsrf(issued.sessionId(), new UserId(issued.userId()), issued.csrfToken()));
            authority.get().requireCsrf(winner.sessionId(), new UserId(winner.userId()), winner.csrfToken());
            authority.get().revoke(winner.sessionId());
            assertNull(jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                    String.class, winner.sessionId()));
            assertThrows(SessionRejectedException.class,
                    () -> authority.get().refresh(winner.refreshToken(), winner.csrfToken(), x -> "jwt"));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void independentJvmRefreshRotationReplayRollbackAndRevocation() throws Exception {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        seedGoogle(proof, now);
        var permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.issue(authority.createAuthenticated(proof), a -> "synthetic-jwt");
        var before = authority.current(issued.sessionId()).orElseThrow();
        assertEquals(Session.WINDOW, java.time.Duration.between(before.renewedAt(), before.renewableUntil()));
        assertEquals(Session.IDLE, java.time.Duration.between(before.lastActivityAt(), before.idleUntil()));
        assertEquals(Session.ABSOLUTE, java.time.Duration.between(before.createdAt(), before.absoluteUntil()));

        // A: Both child JVMs connect before the parent releases either attempt.
        try (var first = worker("refresh", issued); var second = worker("refresh", issued)) {
            assertNotEquals(first.pid(), second.pid());
            System.out.println("MTEC40 independent worker PIDs: " + first.pid() + ", " + second.pid());
            first.release(); second.release();
            String a = first.result(); String b = second.result();
            assertEquals(1, (a.startsWith("ROTATED ") ? 1 : 0) + (b.startsWith("ROTATED ") ? 1 : 0));
            assertTrue(a.equals("REJECTED ATTEMPT_CONSUMED") || b.equals("REJECTED ATTEMPT_CONSUMED"));
            String[] winner = (a.startsWith("ROTATED ") ? a : b).split(" ");
            assertEquals(3, winner.length);
            assertEquals(1L, jdbc.queryForObject("SELECT current_credential_generation FROM dbo.sessions WHERE session_id=?",
                    Long.class, issued.sessionId()));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=?",
                    Integer.class, issued.sessionId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=? AND consumed_ms IS NULL",
                    Integer.class, issued.sessionId()));
            assertEquals(before, authority.current(issued.sessionId()).orElseThrow());
            assertEquals(winner[2], authority.recoverCsrf(winner[1]));

            // B: A consumed pair cannot mint again or revoke the winning session.
            try (var replay = worker("refresh", issued)) {
                replay.release();
                assertEquals("REJECTED ATTEMPT_CONSUMED", replay.result());
            }
            assertEquals(before, authority.current(issued.sessionId()).orElseThrow());
            assertEquals(winner[2], authority.recoverCsrf(winner[1]));
            try (var invalidCsrf = worker("refresh", issued.refreshToken(), winner[2], issued.sessionId());
                 var unknown = worker("refresh", "0".repeat(64), issued.csrfToken(), issued.sessionId())) {
                invalidCsrf.release(); unknown.release();
                assertEquals("REJECTED PROOF_INVALID", invalidCsrf.result());
                assertEquals("REJECTED PROOF_INVALID", unknown.result());
            }
        }

        // C: A signer failure after SQL rotation must roll back both generation rows and pointers.
        var rollbackProof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        seedGoogle(rollbackProof, now);
        var rollbackPair = authority.issue(authority.createAuthenticated(rollbackProof), a -> "synthetic-jwt");
        try (var failed = worker("fail-sign", rollbackPair)) {
            failed.release(); assertEquals("SIGNER_FAILED", failed.result());
        }
        assertEquals(0L, jdbc.queryForObject("SELECT current_credential_generation FROM dbo.sessions WHERE session_id=?",
                Long.class, rollbackPair.sessionId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=? AND consumed_ms IS NULL",
                Integer.class, rollbackPair.sessionId()));
        try (var retry = worker("refresh", rollbackPair)) {
            retry.release(); assertTrue(retry.result().startsWith("ROTATED "));
        }

        // D: Revocation and rotation use the same session lock and must yield a closed session.
        var revokeProof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        seedGoogle(revokeProof, now);
        var revokePair = authority.issue(authority.createAuthenticated(revokeProof), a -> "synthetic-jwt");
        try (var refresh = worker("refresh", revokePair); var revoke = worker("revoke", revokePair)) {
            refresh.release(); revoke.release();
            String rotated = refresh.result();
            assertTrue(rotated.startsWith("ROTATED ") || rotated.equals("REJECTED PROOF_INVALID"));
            assertEquals("REVOKED", revoke.result());
            assertTrue(authority.current(revokePair.sessionId()).isEmpty());
            assertNull(jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                    String.class, revokePair.sessionId()));
            if (rotated.startsWith("ROTATED ")) {
                String[] result = rotated.split(" ");
                assertThrows(SessionRejectedException.class, () -> authority.recoverCsrf(result[1]));
            }
        }
    }

    @Test
    void independentHttpsListenersShareOneSqlRefreshGeneration() throws Exception {
        var now = Instant.now();
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        seedGoogle(proof, now);
        var permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.systemUTC(), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.issue(authority.createAuthenticated(proof), a -> "setup-only");
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        String jwtKeys = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(Map.of(
                "kid", "sql-test", "publicKeyPem", pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                "privateKeyPem", pem("PRIVATE KEY", pair.getPrivate().getEncoded()))));
        var config = server.workerConnection();
        config.setProperty("csrfKey", TEST_KEY);
        config.setProperty("jwtKeys", jwtKeys);
        config.setProperty("operation", "http");
        var trust = KeyStore.getInstance("PKCS12");
        try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(config.getProperty("trustStore")))) {
            trust.load(input, config.getProperty("trustPassword").toCharArray());
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(null, managers.getTrustManagers(), null);
        try (var client = HttpClient.newBuilder().sslContext(ssl).build();
             var first = new RefreshHttpChild(config); var second = new RefreshHttpChild(config)) {
            assertNotEquals(first.pid, second.pid);
            assertNotEquals(first.port, second.port);
            System.out.println("MTEC40 HTTPS worker PIDs/listeners: " + first.pid + "/" + first.port
                    + ", " + second.pid + "/" + second.port + "; shared SQL=" + IsolatedSqlServer.DATABASE);
            var a = client.sendAsync(refreshRequest(first.port, issued.refreshToken(), issued.csrfToken(),
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString());
            var b = client.sendAsync(refreshRequest(second.port, issued.refreshToken(), issued.csrfToken(),
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString());
            var responses = List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(r -> r.statusCode() == 200).count());
            assertEquals(1, responses.stream().filter(r -> r.statusCode() == 401).count());
            var winner = responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow();
            var loser = responses.stream().filter(r -> r.statusCode() == 401).findFirst().orElseThrow();
            assertInvalidHttp(loser, issued.refreshToken(), issued.csrfToken());
            var cookie = winner.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(cookie.startsWith("__Host-mtec-refresh="));
            for (String attribute : List.of("; Path=/", "; Secure", "; HttpOnly", "; SameSite=Lax"))
                assertTrue(cookie.contains(attribute));
            assertFalse(cookie.toLowerCase().contains("domain="));
            assertEquals("https://app.markettec.example",
                    winner.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertEquals("true", winner.headers().firstValue("Access-Control-Allow-Credentials").orElseThrow());
            var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(winner.body());
            assertTrue(body.path("token").asText().split("\\.").length == 3);
            assertFalse(body.has("refreshToken"));
            String next = cookie.substring("__Host-mtec-refresh=".length(), cookie.indexOf(';'));
            String csrf = body.path("csrfToken").asText();
            assertEquals(csrf, authority.recoverCsrf(next));
            assertEquals(1L, jdbc.queryForObject("SELECT current_credential_generation FROM dbo.sessions WHERE session_id=?",
                    Long.class, issued.sessionId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=? AND consumed_ms IS NULL",
                    Integer.class, issued.sessionId()));
            assertInvalidHttp(client.send(refreshRequest(first.port, issued.refreshToken(), issued.csrfToken(),
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString()),
                    issued.refreshToken(), issued.csrfToken());
            assertInvalidHttp(client.send(refreshRequest(second.port, issued.refreshToken(), csrf,
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString()),
                    issued.refreshToken(), csrf);
            assertInvalidHttp(client.send(refreshRequest(first.port, "0".repeat(64), issued.csrfToken(),
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString()),
                    "0".repeat(64), issued.csrfToken());
            assertInvalidHttp(client.send(refreshRequest(second.port, next, "0".repeat(64),
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString()),
                    next, "0".repeat(64));
            assertEquals(csrf, authority.recoverCsrf(next));
            assertEquals(1L, jdbc.queryForObject("SELECT current_credential_generation FROM dbo.sessions WHERE session_id=?",
                    Long.class, issued.sessionId()));
            var forbidden = client.send(refreshRequest(second.port, next, csrf, "https://evil.example"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forbidden.statusCode());
            assertTrue(forbidden.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertTrue(forbidden.headers().firstValue("Set-Cookie").isEmpty());
            assertFalse(forbidden.body().contains(next));
            assertFalse(forbidden.body().contains(csrf));
            var preflight = HttpRequest.newBuilder(URI.create("https://localhost:" + first.port + "/v1/auth/refresh"))
                    .header("Origin", "https://app.markettec.example")
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "Content-Type,X-MTEC-Client,X-CSRF-Token")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(204, client.send(preflight, HttpResponse.BodyHandlers.discarding()).statusCode());
            var deniedPreflight = HttpRequest.newBuilder(preflight.uri())
                    .header("Origin", "https://app.markettec.example")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Content-Type,X-MTEC-Client,X-CSRF-Token")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            assertEquals(403, client.send(deniedPreflight, HttpResponse.BodyHandlers.discarding()).statusCode());
            var usable = client.send(refreshRequest(second.port, next, csrf, "https://app.markettec.example"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, usable.statusCode());
            assertEquals(2L, jdbc.queryForObject("SELECT current_credential_generation FROM dbo.sessions WHERE session_id=?",
                    Long.class, issued.sessionId()));
            assertInvalidHttp(client.send(refreshRequest(first.port, issued.refreshToken(), issued.csrfToken(),
                    "https://app.markettec.example"), HttpResponse.BodyHandlers.ofString()),
                    issued.refreshToken(), issued.csrfToken());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=? AND consumed_ms IS NULL",
                    Integer.class, issued.sessionId()));
        }
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getEncoder().encodeToString(encoded)
                + "\n-----END " + type + "-----";
    }

    private static HttpRequest refreshRequest(int port, String refresh, String csrf, String origin) {
        return HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/v1/auth/refresh"))
                .header("Origin", origin).header("X-MTEC-Client", "web")
                .header("X-CSRF-Token", csrf).header("Cookie", "__Host-mtec-refresh=" + refresh)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"rotate\"}"))
                .build();
    }

    private static void assertInvalidHttp(HttpResponse<String> response, String refresh, String csrf) throws Exception {
        assertEquals(401, response.statusCode());
        assertEquals("REFRESH_INVALID", new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.body()).path("error").path("code").asText());
        assertTrue(response.headers().firstValue("Set-Cookie").isEmpty());
        assertFalse(response.body().contains("\"token\""));
        assertFalse(response.body().contains(refresh));
        assertFalse(response.body().contains(csrf));
        assertFalse(response.body().contains("SQLException"));
    }

    private static final class RefreshHttpChild implements AutoCloseable {
        final Process process;
        final BufferedReader output;
        final OutputStreamWriter input;
        final long pid;
        final int port;

        RefreshHttpChild(Properties config) throws Exception {
            String javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            process = new ProcessBuilder(javaBinary, "-cp", System.getProperty("java.class.path"), RefreshWorker.class.getName())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            try {
                var bytes = new ByteArrayOutputStream();
                config.store(bytes, "synthetic isolated SQL QA");
                input.write(Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\n");
                input.flush();
                String ready = CompletableFuture.supplyAsync(() -> {
                    try {
                        String line;
                        while ((line = output.readLine()) != null) if (line.startsWith("READY HTTP ")) return line;
                        return null;
                    } catch (Exception failure) { throw new CompletionException(failure); }
                }).get(45, TimeUnit.SECONDS);
                assertNotNull(ready, "HTTPS child exited before readiness");
                assertTrue(ready.matches("READY HTTP [0-9]+ [0-9]+"));
                String[] parts = ready.split(" ");
                pid = Long.parseLong(parts[2]);
                port = Integer.parseInt(parts[3]);
                assertNotEquals(ProcessHandle.current().pid(), pid);
            } catch (Exception | Error failure) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw failure;
            }
        }

        @Override public void close() throws Exception {
            if (process.isAlive()) { input.write("STOP\n"); input.flush(); }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS);
            }
            input.close(); output.close();
            assertEquals(0, process.exitValue(), "HTTPS child failed");
        }
    }

    private RefreshChild worker(String operation, SessionTokens tokens) throws Exception {
        return worker(operation, tokens.refreshToken(), tokens.csrfToken(), tokens.sessionId());
    }

    private RefreshChild worker(String operation, String refresh, String csrf, String sessionId) throws Exception {
        var config = server.workerConnection();
        config.setProperty("csrfKey", TEST_KEY);
        config.setProperty("operation", operation);
        config.setProperty("refresh", refresh);
        config.setProperty("csrf", csrf);
        config.setProperty("sessionId", sessionId);
        return new RefreshChild(config);
    }

    private static final class RefreshChild implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final OutputStreamWriter input;
        private final long pid;

        RefreshChild(Properties config) throws Exception {
            String javaBinary = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
            process = new ProcessBuilder(javaBinary, "-cp", System.getProperty("java.class.path"), RefreshWorker.class.getName())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            try {
                var bytes = new ByteArrayOutputStream();
                config.store(bytes, "synthetic isolated SQL QA");
                input.write(Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\n");
                input.flush();
                String ready = readProtocol("READY ", 30);
                assertNotNull(ready, "Child JVM exited before SQL connection was ready");
                assertTrue(ready.matches("READY [0-9]+"), "Child JVM did not announce readiness");
                pid = Long.parseLong(ready.substring(6));
                assertNotEquals(ProcessHandle.current().pid(), pid);
            } catch (Exception | Error failure) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw failure;
            }
        }

        long pid() { return pid; }
        void release() throws Exception { input.write("GO\n"); input.flush(); }
        String result() throws Exception {
            String line = readProtocol("RESULT ", 40);
            assertNotNull(line, "Child JVM exited without a result");
            assertTrue(line.startsWith("RESULT "), "Child JVM returned an unexpected response");
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child JVM did not exit");
            assertEquals(0, process.exitValue(), "Child JVM failed");
            return line.substring(7);
        }

        private String readProtocol(String prefix, int seconds) throws Exception {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = output.readLine()) != null) {
                        if (line.startsWith(prefix)) return line;
                    }
                    return null;
                } catch (Exception e) { throw new CompletionException(e); }
            }).get(seconds, TimeUnit.SECONDS);
        }

        @Override public void close() throws Exception {
            input.close(); output.close();
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
        }
    }

    @Test
    void sqlLogoutKeepsOnlyLastProofForIdempotentCleanup() {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var old = authority.issue(authority.createAuthenticated(proof), a -> "jwt");
        var winner = authority.refresh(old.refreshToken(), old.csrfToken(), a -> "jwt");
        assertThrows(SessionRejectedException.class, () -> authority.logout(old.refreshToken(), old.csrfToken()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, winner.sessionId()));
        authority.logout(winner.refreshToken(), winner.csrfToken());
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, winner.sessionId()));
        authority.logout(winner.refreshToken(), winner.csrfToken());
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, winner.sessionId()));
        assertThrows(SessionRejectedException.class, () -> authority.logout(old.refreshToken(), old.csrfToken()));
        assertThrows(SessionRejectedException.class, () -> authority.logout(winner.refreshToken(), old.csrfToken()));
        assertNull(jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                String.class, winner.sessionId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.session_credential_generations
                WHERE session_id=? AND consumed_ms IS NULL
                """, Integer.class, winner.sessionId()));
    }

    @Test
    void concurrentSqlLogoutCommitsOneRevocation() throws Exception {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Supplier<SessionAuthority> authority = () -> new SessionAuthority(new SqlSessionRepository(jdbc),
                store, store, store, Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.get().issue(authority.get().createAuthenticated(proof), a -> "jwt");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Void> action = () -> {
                gate.await();
                authority.get().logout(issued.refreshToken(), issued.csrfToken());
                return null;
            };
            var first = pool.submit(action); var second = pool.submit(action);
            gate.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                    Long.class, issued.sessionId()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void sqlRefreshLogoutRaceCannotRevokeRotatedWinner() throws Exception {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        Supplier<SessionAuthority> authority = () -> new SessionAuthority(new SqlSessionRepository(jdbc),
                store, store, store, Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.get().issue(authority.get().createAuthenticated(proof), a -> "jwt");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var refresh = pool.submit(() -> {
                gate.await();
                try { return authority.get().refresh(issued.refreshToken(), issued.csrfToken(), a -> "jwt2"); }
                catch (SessionRejectedException rejected) { return null; }
            });
            var logout = pool.submit(() -> {
                gate.await();
                try { authority.get().logout(issued.refreshToken(), issued.csrfToken()); return true; }
                catch (SessionRejectedException rejected) { return false; }
            });
            gate.countDown();
            var rotated = refresh.get(30, TimeUnit.SECONDS);
            boolean closed = logout.get(30, TimeUnit.SECONDS);
            assertNotEquals(closed, rotated != null);
            Session state = authority.get().current(issued.sessionId()).orElse(null);
            if (rotated != null) {
                assertNotNull(state);
                assertEquals(rotated.csrfToken(), authority.get().recoverCsrf(rotated.refreshToken()));
            } else {
                assertNull(state);
                assertThrows(SessionRejectedException.class,
                        () -> authority.get().refresh(issued.refreshToken(), issued.csrfToken(), a -> "jwt3"));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void failedSqlLogoutRollsBackRevocationAndVerifierRemoval() {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.issue(authority.createAuthenticated(proof), a -> "jwt");
        assertThrows(IllegalStateException.class, () -> store.execute(() -> {
            authority.logout(issued.refreshToken(), issued.csrfToken());
            throw new IllegalStateException("abort outer transaction");
        }));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, issued.sessionId()));
        assertNotNull(jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                String.class, issued.sessionId()));
        authority.logout(issued.refreshToken(), issued.csrfToken());
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, issued.sessionId()));
    }

    @Test
    void failedSqlRefreshRollsBackVerifierAndSessionDeadline() {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.issue(authority.createAuthenticated(proof), a -> "jwt");
        String beforeHash = jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                String.class, issued.sessionId());
        long beforeVersion = jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, issued.sessionId());
        assertThrows(IllegalStateException.class, () -> authority.refreshWithSnapshot(issued.refreshToken(), issued.csrfToken(),
                a -> { throw new IllegalStateException("signer unavailable"); }));
        assertEquals(beforeHash, jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                String.class, issued.sessionId()));
        assertEquals(beforeVersion, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, issued.sessionId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.session_credential_generations
                WHERE session_id=? AND consumed_ms IS NULL
                """, Integer.class, issued.sessionId()));
        var repository = new SqlSessionRepository(jdbc);
        assertThrows(RuntimeException.class, () -> store.execute(() -> {
            repository.loadLocked(issued.sessionId()).orElseThrow();
            var current = repository.verifiers(issued.sessionId()).orElseThrow();
            // Duplicate refresh verifier fails on insert after the old generation was marked consumed.
            repository.rotateVerifiers(issued.sessionId(), current,
                    new SessionVerifiers(current.refreshHash(), current.csrfHash(), current.generation() + 1,
                            current.keyId()), now);
            return null;
        }));
        assertEquals(beforeHash, jdbc.queryForObject("SELECT refresh_hash FROM dbo.sessions WHERE session_id=?",
                String.class, issued.sessionId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.session_credential_generations
                WHERE session_id=? AND generation=0 AND consumed_ms IS NULL
                """, Integer.class, issued.sessionId()));
        assertEquals(issued.csrfToken(), authority.recoverCsrf(issued.refreshToken()));
        authority.refreshWithSnapshot(issued.refreshToken(), issued.csrfToken(), a -> "jwt2");
    }

    @Test
    void sqlCsrfRecoverySurvivesRestartAndRejectsMissingLegacyAndExpiredKeys() {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var repository = new SqlSessionRepository(jdbc);
        var old = new SessionAuthority(repository, store, store, store, Clock.fixed(now, ZoneOffset.UTC),
                new java.security.SecureRandom(), permit, csrfKeys());
        var issued = old.issue(old.createAuthenticated(proof), a -> "jwt");
        var restarted = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        assertEquals(issued.csrfToken(), restarted.recoverCsrf(issued.refreshToken()));

        String nextKey = Base64.getEncoder().encodeToString(new byte[48]);
        var nextOnly = CsrfKeys.fromJson("{\"next\":\"" + nextKey + "\"}", "next");
        var retired = new SessionAuthority(repository, store, store, store, Clock.fixed(now, ZoneOffset.UTC),
                new java.security.SecureRandom(), permit, nextOnly);
        assertThrows(SessionRejectedException.class, () -> retired.recoverCsrf(issued.refreshToken()));

        jdbc.update("UPDATE dbo.session_credential_generations SET csrf_key_id=NULL WHERE session_id=? AND generation=0",
                issued.sessionId());
        assertThrows(SessionRejectedException.class, () -> restarted.recoverCsrf(issued.refreshToken()));
        jdbc.update("UPDATE dbo.session_credential_generations SET csrf_key_id='sql-test' WHERE session_id=? AND generation=0",
                issued.sessionId());

        var both = CsrfKeys.fromJson("{\"sql-test\":\"" + TEST_KEY + "\",\"next\":\"" + nextKey + "\"}", "next");
        var rotated = new SessionAuthority(repository, store, store, store, Clock.fixed(now, ZoneOffset.UTC),
                new java.security.SecureRandom(), permit, both);
        assertEquals(issued.csrfToken(), rotated.recoverCsrf(issued.refreshToken()));
        var next = rotated.refresh(issued.refreshToken(), issued.csrfToken(), a -> "jwt2");
        assertEquals("next", jdbc.queryForObject("""
                SELECT csrf_key_id FROM dbo.session_credential_generations
                WHERE session_id=? AND generation=1
                """, String.class, issued.sessionId()));
        assertEquals(next.csrfToken(), retired.recoverCsrf(next.refreshToken()));
        assertThrows(SessionRejectedException.class, () -> old.recoverCsrf(next.refreshToken()));
        assertThrows(SessionRejectedException.class, () -> rotated.recoverCsrf(issued.refreshToken()));
        var expired = new SessionAuthority(repository, store, store, store,
                Clock.fixed(now.plusSeconds(30 * 60), ZoneOffset.UTC), new java.security.SecureRandom(), permit, both);
        assertThrows(SessionRejectedException.class, () -> expired.recoverCsrf(next.refreshToken()));
    }

    @Test
    void sensitiveProfileCommitAndRevocationSerializeOnRealSqlServer() throws Exception {
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        User owner = seedGoogle(proof, Instant.parse("2026-11-03T12:00:00Z"));
        Clock sessionClock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC);
        Clock profileClock = Clock.fixed(sessionClock.instant().plusSeconds(1), ZoneOffset.UTC);
        UserService profileService = new UserService(store, store, new UuidUserIdGenerator(), store, deny(), profileClock);
        Supplier<SessionAuthority> authority = () -> new SessionAuthority(
                new SqlSessionRepository(jdbc), store, store, store, sessionClock,
                new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        Session session = authority.get().create(proof);
        long versionBefore = jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                Long.class, session.sessionId());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revoking = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var changed = new ProfileDetails("serial", "Serial", "Owner");
            var operation = pool.submit(() -> authority.get().withCurrentSession(session.sessionId(), owner.userId(), () -> {
                entered.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("operation timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                return profileService.updateOwnProfile(owner::userId, owner.userId(), changed);
            }));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var revocation = pool.submit(() -> {
                revoking.countDown();
                authority.get().revoke(session.sessionId());
                return null;
            });
            assertTrue(revoking.await(15, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revocation.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            UserProfile updated = operation.get(30, TimeUnit.SECONDS);
            assertEquals(changed, updated.details());
            assertEquals(profileClock.instant(), updated.fechaActualizacion());
            revocation.get(30, TimeUnit.SECONDS);
            assertEquals(changed, store.findById(owner.userId()).orElseThrow().profile().details());
            assertThrows(SessionRejectedException.class, () -> authority.get().withCurrentSession(
                    session.sessionId(), owner.userId(), () -> service().deactivateUser(owner::userId, owner.userId())));
            assertEquals(AccountStatus.ACTIVE, store.findById(owner.userId()).orElseThrow().status());
            assertEquals(versionBefore + 1, jdbc.queryForObject("SELECT version FROM dbo.sessions WHERE session_id=?",
                    Long.class, session.sessionId()));
            Session expired = authority.get().create(proof);
            SessionAuthority later = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                    Clock.fixed(sessionClock.instant().plusSeconds(30 * 60), ZoneOffset.UTC),
                    new java.security.SecureRandom(), denySessionActions(), csrfKeys());
            assertThrows(SessionRejectedException.class, () -> later.withCurrentSession(
                    expired.sessionId(), owner.userId(), () -> service().deactivateUser(owner::userId, owner.userId())));
            assertEquals(AccountStatus.ACTIVE, store.findById(owner.userId()).orElseThrow().status());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static SessionActionAuthorization denySessionActions() {
        return new SessionActionAuthorization() {
            public void requireRenewal(Session session) { throw new SessionRejectedException(); }
            public void requireAdmissibleActivity(Session session) { throw new SessionRejectedException(); }
        };
    }

    @Test
    void googleLoginIntentIsAtomicAcrossConcurrentSqlExchangesAndRollback() throws Exception {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        Email verifiedEmail = new Email("google-login@example.invalid");
        User owner = User.create(new UuidUserIdGenerator().nextId(), verifiedEmail,
                new ProfileDetails("google-login", "Google", "Login"), URI.create("https://example.invalid/a"), now);
        var key = new ExternalIdentityKey("https://accounts.google.com", "sql-google-login-sub");
        store.execute(() -> {
            store.insert(owner);
            store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, owner.userId(), verifiedEmail));
            return null;
        });
        var repo = new SqlGoogleLoginIntentRepository(jdbc);
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                fixed, new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        var verifier = (com.markettec.users.application.ports.out.GoogleIdentityVerifier) (token, context) -> {
            if (!token.equals(context.expectedNonce())) throw new SessionRejectedException();
            return new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(
                    key.issuer(), key.subject(), context.expectedAudience(), now, now.plusSeconds(3600));
        };
        var service = new GoogleLoginService(repo, store, verifier, authority,
                authenticated -> "signed-internal-jwt", "sql-client", fixed, new java.security.SecureRandom());
        var rollback = service.begin();
        var failing = new GoogleLoginService(repo, store, verifier, authority,
                authenticated -> { throw new IllegalStateException("signing failed"); },
                "sql-client", fixed, new java.security.SecureRandom());
        assertThrows(IllegalStateException.class,
                () -> failing.complete(rollback.attemptId(), rollback.browserSecret(), rollback.nonce()));
        assertFalse(store.execute(() -> repo.loadLocked(rollback.attemptId()).orElseThrow().consumed()));
        assertEquals("signed-internal-jwt",
                service.complete(rollback.attemptId(), rollback.browserSecret(), rollback.nonce()).jwt());

        var challenge = service.begin();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> exchange = () -> {
                gate.await();
                try { service.complete(challenge.attemptId(), challenge.browserSecret(), challenge.nonce()); return true; }
                catch (SessionRejectedException rejected) { return false; }
            };
            var first = pool.submit(exchange);
            var second = pool.submit(exchange);
            gate.countDown();
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.sessions WHERE user_id=?",
                    Integer.class, owner.userId().value()));
            assertTrue(store.execute(() -> repo.loadLocked(challenge.attemptId()).orElseThrow().consumed()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void immediateGoogleRegistrationCommitsOnceAcrossTwoSqlInstancesAndAllowsLaterLogin() throws Exception {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        var key = new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString());
        var recipient = new Email("mtec36-" + UUID.randomUUID() + "@tec.ac.cr");
        int beforeUsers = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users", Integer.class);
        var firstStore = newStore();
        var secondStore = newStore();
        var first = registrationForSql(firstStore, key, recipient, fixed, false);
        var second = registrationForSql(secondStore, key, recipient, fixed, false);
        var challenge = first.begin();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> a = () -> { gate.await();
                try { first.complete(challenge.attemptId(), challenge.browserSecret(), challenge.nonce()); return true; }
                catch (SessionRejectedException | UserPersistenceException rejected) { return false; } };
            Callable<Boolean> b = () -> { gate.await();
                try { second.complete(challenge.attemptId(), challenge.browserSecret(), challenge.nonce()); return true; }
                catch (SessionRejectedException | UserPersistenceException rejected) { return false; } };
            var x = pool.submit(a); var y = pool.submit(b); gate.countDown();
            assertEquals(1, (x.get(20, TimeUnit.SECONDS) ? 1 : 0) + (y.get(20, TimeUnit.SECONDS) ? 1 : 0));
        } finally { pool.shutdownNow(); }
        assertEquals(beforeUsers + 1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users", Integer.class));
        String owner = jdbc.queryForObject("SELECT user_id FROM dbo.external_identities WHERE subject=?",
                String.class, key.subject());
        assertNotNull(owner);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.sessions WHERE user_id=?",
                Integer.class, owner));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.pending_google_registrations WHERE attempt_id=?",
                Integer.class, challenge.attemptId()));
        assertEquals(true, jdbc.queryForObject("SELECT consumed FROM dbo.google_login_intents WHERE attempt_id=?",
                Boolean.class, challenge.attemptId()));

        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), firstStore, firstStore, firstStore,
                fixed, new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        var verifier = (com.markettec.users.application.ports.out.GoogleIdentityVerifier) (token, context) -> {
            if (!token.equals(context.expectedNonce())) throw new SessionRejectedException();
            return new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(
                    key.issuer(), key.subject(), context.expectedAudience(), fixed.instant(),
                    fixed.instant().plusSeconds(3600), recipient);
        };
        var login = new GoogleLoginService(new SqlGoogleLoginIntentRepository(jdbc), firstStore,
                verifier, authority, ignored -> "jwt", "sql-client", fixed, new java.security.SecureRandom());
        var loginChallenge = login.begin();
        assertEquals(owner, login.complete(loginChallenge.attemptId(), loginChallenge.browserSecret(),
                loginChallenge.nonce()).userId());
    }

    @Test
    void immediateRegistrationJwtFailureAndExistingEmailLeaveNoSqlPartialWrites() {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        var key = new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString());
        var recipient = new Email("mtec36-" + UUID.randomUUID() + "@itcr.ac.cr");
        var service = registrationForSql(newStore(), key, recipient, fixed, true);
        var challenge = service.begin();
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users", Integer.class);
        assertThrows(IllegalStateException.class, () ->
                service.complete(challenge.attemptId(), challenge.browserSecret(), challenge.nonce()));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.external_identities WHERE subject=?",
                Integer.class, key.subject()));
        assertEquals(false, jdbc.queryForObject("SELECT consumed FROM dbo.google_login_intents WHERE attempt_id=?",
                Boolean.class, challenge.attemptId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.sessions WHERE user_id IN "
                + "(SELECT user_id FROM dbo.profiles WHERE correo=?)", Integer.class, recipient.address()));

        var existing = User.create(new UuidUserIdGenerator().nextId(), recipient,
                details, photo, now);
        store.insert(existing);
        var safe = registrationForSql(newStore(), key, recipient, fixed, false);
        var next = safe.begin();
        assertThrows(SessionRejectedException.class, () ->
                safe.complete(next.attemptId(), next.browserSecret(), next.nonce()));
        assertEquals(before + 1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.users", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.external_identities WHERE subject=?",
                Integer.class, key.subject()));
    }

    private GoogleRegistrationService registrationForSql(SqlUserStore unit, ExternalIdentityKey key,
            Email recipient, Clock fixed, boolean failJwt) {
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), unit, unit, unit,
                fixed, new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        var verifier = (com.markettec.users.application.ports.out.GoogleIdentityVerifier) (token, context) -> {
            if (!token.equals(context.expectedNonce())) throw new SessionRejectedException();
            return new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(
                    key.issuer(), key.subject(), context.expectedAudience(), fixed.instant(),
                    fixed.instant().plusSeconds(3600), recipient);
        };
        return new GoogleRegistrationService(new SqlGoogleLoginIntentRepository(jdbc),
                new SqlPendingGoogleRegistrationRepository(jdbc), unit, verifier, unit, unit,
                new UuidUserIdGenerator(), authority,
                ignored -> { if (failJwt) throw new IllegalStateException("JWT failure"); return "jwt"; },
                "sql-client", fixed, new java.security.SecureRandom());
    }

    @Test
    void googleLoginRequiresActivePersistedLinkAndAccount() {
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        User owner = User.create(new UuidUserIdGenerator().nextId(), email,
                details, photo, now);
        var key = key();
        store.execute(() -> {
            store.insert(owner);
            store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, key, owner.userId(), email));
            return null;
        });
        var authority = new SessionAuthority(new SqlSessionRepository(jdbc), store, store, store,
                Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        var proof = new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(
                key.issuer(), key.subject(), "sql-client", now, now.plusSeconds(3600));
        assertNotNull(authority.createGoogle(proof));
        var legacyKey = key();
        jdbc.update("""
                INSERT INTO dbo.external_identities(issuer, subject, user_id, provider, verified_email)
                VALUES (?, ?, ?, 'GOOGLE', ?)
                """, legacyKey.issuer(), legacyKey.subject(), owner.userId().value(), email.address());
        assertEquals(IdentityLinkStatus.PENDING, store.findByKey(legacyKey).orElseThrow().status());
        var legacyProof = new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(
                legacyKey.issuer(), legacyKey.subject(), "sql-client", now, now.plusSeconds(3600));
        assertThrows(SessionRejectedException.class, () -> authority.createGoogle(legacyProof));
        for (IdentityLinkStatus status : List.of(IdentityLinkStatus.DISABLED, IdentityLinkStatus.REVOKED,
                IdentityLinkStatus.PENDING, IdentityLinkStatus.CONFLICT)) {
            store.replaceStatus(store.findByKey(key).orElseThrow(), status);
            assertEquals(status, newStore().findByKey(key).orElseThrow().status());
            assertThrows(SessionRejectedException.class, () -> authority.createGoogle(proof));
            store.replaceStatus(store.findByKey(key).orElseThrow(), IdentityLinkStatus.ACTIVE);
        }
        store.replace(owner, owner.deactivate());
        assertThrows(UserDomainException.class, () -> authority.createGoogle(proof));
    }

    private record RecentFixture(User user, Session session, RecentAttempt attempt,
                                 SessionAuthority sessions, RecentAuthenticationAuthority recent) { }

    private RecentFixture recentFixture() {
        User owner = user();
        store.insert(owner);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        Clock fixed = Clock.fixed(now, ZoneOffset.UTC);
        var sessionRepo = new SqlSessionRepository(jdbc);
        var attemptRepo = new SqlRecentAttemptRepository(jdbc);
        Session session = Session.start(UUID.randomUUID().toString().replace("-", ""), owner.userId(), now);
        RecentAttempt attempt = new RecentAttempt(UUID.randomUUID().toString().replace("-", ""), owner.userId(),
                session.sessionId(), RecentAttempt.Method.INSTITUTIONAL_EMAIL, "DEACTIVATE_USER",
                owner.userId().value(), "a".repeat(64), now, now.plusSeconds(300), null, false, 0);
        store.execute(() -> {
            sessionRepo.insert(session);
            attemptRepo.insert(attempt);
            attemptRepo.replace(attempt, attempt.verifiedAt(now));
            return null;
        });
        var authority = new SessionAuthority(sessionRepo, store, store, store, fixed,
                new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        return new RecentFixture(owner, session, attempt, authority,
                new RecentAuthenticationAuthority(authority, attemptRepo, fixed, new java.security.SecureRandom()));
    }

    private int consumeRecent(RecentFixture f) {
        return f.recent().consume(f.session().sessionId(), f.user().userId(), f.attempt().attemptId(),
                f.attempt().method(), f.attempt().operation(), f.attempt().target(), () ->
                        jdbc.update("UPDATE dbo.users SET version=version+1 WHERE user_id=?", f.user().userId().value()));
    }

    @Test void recentProofCommitsOnceWithSensitiveMutation() {
        RecentFixture f = recentFixture();
        assertEquals(1, consumeRecent(f));
        assertThrows(SessionRejectedException.class, () -> consumeRecent(f));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM dbo.users WHERE user_id=?",
                Long.class, f.user().userId().value()));
        assertTrue(jdbc.queryForObject("SELECT consumed FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Boolean.class, f.attempt().attemptId()));
    }

    @Test void concurrentRecentProofConsumersHaveOneCommit() throws Exception {
        RecentFixture f = recentFixture();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> task = () -> {
                gate.await();
                try { return consumeRecent(f) == 1; }
                catch (SessionRejectedException rejected) { return false; }
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertEquals(1, (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM dbo.users WHERE user_id=?",
                    Long.class, f.user().userId().value()));
        } finally { pool.shutdownNow(); }
    }

    @Test void recentProofAndMutationRollbackTogether() {
        RecentFixture f = recentFixture();
        assertThrows(IllegalStateException.class, () -> f.recent().consume(f.session().sessionId(),
                f.user().userId(), f.attempt().attemptId(), f.attempt().method(), f.attempt().operation(),
                f.attempt().target(), () -> {
                    jdbc.update("UPDATE dbo.users SET version=version+1 WHERE user_id=?", f.user().userId().value());
                    throw new IllegalStateException("fail operation");
                }));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Boolean.class, f.attempt().attemptId()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM dbo.users WHERE user_id=?",
                Long.class, f.user().userId().value()));
        assertEquals(1, consumeRecent(f));
    }

    @Test void recentProofSerializesWithRevocation() throws Exception {
        RecentFixture f = recentFixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var operation = pool.submit(() -> f.recent().consume(f.session().sessionId(), f.user().userId(),
                    f.attempt().attemptId(), f.attempt().method(), f.attempt().operation(), f.attempt().target(), () -> {
                        entered.countDown();
                        try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        return jdbc.update("UPDATE dbo.users SET version=version+1 WHERE user_id=?", f.user().userId().value());
                    }));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var revoke = pool.submit(() -> { f.sessions().revoke(f.session().sessionId()); return null; });
            assertThrows(TimeoutException.class, () -> revoke.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(1, operation.get(30, TimeUnit.SECONDS));
            revoke.get(30, TimeUnit.SECONDS);
            assertThrows(SessionRejectedException.class, () -> consumeRecent(f));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void failedRecentPersistenceRollsBackSensitiveUnit() {
        RecentFixture f = recentFixture();
        assertThrows(UserPersistenceException.class, () -> f.recent().consume(f.session().sessionId(),
                f.user().userId(), f.attempt().attemptId(), f.attempt().method(), f.attempt().operation(),
                f.attempt().target(), () -> {
                    jdbc.update("UPDATE dbo.users SET version=version+1 WHERE user_id=?", f.user().userId().value());
                    jdbc.update("INSERT INTO dbo.missing_recent_table (x) VALUES (1)");
                    return null;
                }));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Boolean.class, f.attempt().attemptId()));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM dbo.users WHERE user_id=?",
                Long.class, f.user().userId().value()));
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

    @Test
    void restrictedRuntimeCanRotateRefreshWithoutDeleteOrDdlPermissions() throws Exception {
        var runtime = server.restrictedRuntimeSource();
        var restrictedJdbc = new JdbcTemplate(runtime);
        var restricted = new SqlUserStore(restrictedJdbc, new DataSourceTransactionManager(runtime));
        var proof = new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()), email);
        Instant now = Instant.parse("2026-11-03T12:00:00Z");
        seedGoogle(proof, now);
        SessionActionAuthorization permit = new SessionActionAuthorization() {
            public void requireRenewal(Session session) { }
            public void requireAdmissibleActivity(Session session) { }
        };
        var authority = new SessionAuthority(new SqlSessionRepository(restrictedJdbc), restricted, restricted,
                restricted, Clock.fixed(now, ZoneOffset.UTC), new java.security.SecureRandom(), permit, csrfKeys());
        var issued = authority.issue(authority.createAuthenticated(proof), a -> "jwt");
        var before = authority.current(issued.sessionId()).orElseThrow();
        var rotated = authority.refresh(issued.refreshToken(), issued.csrfToken(), a -> "jwt2");
        assertEquals(before, authority.current(issued.sessionId()).orElseThrow());
        assertEquals(issued.sessionId(), rotated.sessionId());
        assertEquals(2, restrictedJdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.session_credential_generations WHERE session_id=?
                """, Integer.class, issued.sessionId()));
        assertEquals(1, restrictedJdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.session_credential_generations
                WHERE session_id=? AND generation=0 AND consumed_ms IS NOT NULL
                """, Integer.class, issued.sessionId()));
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                assertThrows(SessionRejectedException.class, () -> authority.refresh(
                        issued.refreshToken(), issued.csrfToken(), a -> "jwt3")).reason());
        assertEquals(0, restrictedJdbc.queryForObject("""
                SELECT HAS_PERMS_BY_NAME('dbo.session_credential_generations', 'OBJECT', 'DELETE')
                """, Integer.class));
        assertEquals(0, restrictedJdbc.queryForObject("SELECT IS_MEMBER('db_owner')", Integer.class));
        assertEquals(0, restrictedJdbc.queryForObject(
                "SELECT HAS_PERMS_BY_NAME('dbo', 'SCHEMA', 'ALTER')", Integer.class));
    }

    private AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles("sql-integration");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated", Map.of(
                "users.sql.database", IsolatedSqlServer.DATABASE, "spring.application.name", "ms-users",
                "users.csrf.active-kid", "sql-test", "users.csrf.keys-json", "{\"sql-test\":\"" + TEST_KEY + "\"}")));
        context.registerBean(DataSource.class, () -> source);
        context.register(ApplicationConfig.class, SqlUserConfig.class);
        context.refresh();
        return context;
    }

    private User user() {
        return User.create(new UuidUserIdGenerator().nextId(), email, details, photo, clock.instant());
    }

    private ExternalIdentityKey key() { return new ExternalIdentityKey("https://accounts.google.com", UUID.randomUUID().toString()); }
    private User seedGoogle(VerifiedExternalIdentity proof, Instant now) {
        return store.execute(() -> {
            var user = User.create(new UuidUserIdGenerator().nextId(), proof.email(), details, photo, now);
            store.insert(user);
            store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, proof.key(), user.userId(), proof.email()));
            return user;
        });
    }
    private VerifiedExternalIdentity verified() { return new VerifiedExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key(), email); }
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

    // ---- MTEC-19/36: V12 -> V13 and exact two-proof identity linking -------------------------------

    @Test
    void flywayUpgradesAppliedV12ToV13WithoutTouchingTheV12ChecksumAndInvalidatesLegacyLinks() throws Exception {
        String database = "mtec_upgrade_v12";
        new JdbcTemplate(server.source("master")).execute("CREATE DATABASE " + database
                + " COLLATE SQL_Latin1_General_CP1_CI_AS");
        DataSource upgrade = server.source(database);
        var upgradeJdbc = new JdbcTemplate(upgrade);
        Flyway through12 = Flyway.configure().dataSource(upgrade).defaultSchema("dbo").target("12")
                .baselineOnMigrate(false).cleanDisabled(true).load();
        assertEquals(12, through12.migrate().migrationsExecuted);
        Integer checksum = upgradeJdbc.queryForObject(
                "SELECT checksum FROM dbo.flyway_schema_history WHERE [version]='12'", Integer.class);
        long now = Instant.parse("2026-11-03T12:00:00Z").toEpochMilli();
        upgradeJdbc.update("""
                INSERT INTO dbo.email_login_attempts(attempt_id,recipient,verifier,created_ms,expires_ms,failures,consumed,version)
                VALUES (?,?,?,?,?,0,0,0)
                """, "a".repeat(32), "ana@tec.ac.cr", "b".repeat(64), now, now + 600_000);
        Flyway latest = Flyway.configure().dataSource(upgrade).defaultSchema("dbo")
                .baselineOnMigrate(false).cleanDisabled(true).load();
        assertEquals(1, latest.migrate().migrationsExecuted);
        latest.validate();
        assertEquals(checksum, upgradeJdbc.queryForObject(
                "SELECT checksum FROM dbo.flyway_schema_history WHERE [version]='12'", Integer.class));
        assertEquals("13", latest.info().current().getVersion().getVersion());
        assertEquals(0, upgradeJdbc.queryForObject("SELECT COUNT(*) FROM dbo.email_login_attempts", Integer.class));
        assertEquals(1, upgradeJdbc.queryForObject("""
                SELECT COUNT(*) FROM sys.columns WHERE object_id=OBJECT_ID('dbo.email_login_attempts')
                  AND name='browser_hash' AND is_nullable=0
                """, Integer.class));
        assertEquals(1, upgradeJdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.tables WHERE name='recent_attempt_identities'", Integer.class));
        assertEquals(0, latest.migrate().migrationsExecuted);
    }

    private record LinkFixture(User owner, Session session, RecentAttempt current, RecentAttempt added,
                               SessionAuthority sessions, RecentAuthenticationAuthority recent,
                               ExternalIdentityKey held, java.util.concurrent.atomic.AtomicReference<Instant> time,
                               ExternalIdentityKey googleKey) { }

    private static final Instant LINK_NOW = Instant.parse("2026-11-03T12:00:00Z");

    private LinkFixture linkFixture() {
        User owner = user();
        store.insert(owner);
        var time = new java.util.concurrent.atomic.AtomicReference<>(LINK_NOW);
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var sessionRepo = new SqlSessionRepository(jdbc);
        var attemptRepo = new SqlRecentAttemptRepository(jdbc);
        var provenRepo = new SqlProvenIdentityRepository(jdbc);
        Session session = Session.start(UUID.randomUUID().toString().replace("-", ""), owner.userId(), LINK_NOW);
        var held = new ExternalIdentityKey(EmailLoginService.ISSUER, "held-" + UUID.randomUUID() + "@tec.ac.cr");
        store.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, held, owner.userId(),
                new Email("held@tec.ac.cr")));
        RecentAttempt current = new RecentAttempt(UUID.randomUUID().toString().replace("-", ""), owner.userId(),
                session.sessionId(), RecentAttempt.Method.INSTITUTIONAL_EMAIL, RecentProofService.LINK_CURRENT,
                "GOOGLE", "a".repeat(64), LINK_NOW, LINK_NOW.plusSeconds(300), null, false, 0);
        RecentAttempt added = new RecentAttempt(UUID.randomUUID().toString().replace("-", ""), owner.userId(),
                session.sessionId(), RecentAttempt.Method.GOOGLE, RecentProofService.LINK_NEW,
                "GOOGLE", "c".repeat(64), LINK_NOW, LINK_NOW.plusSeconds(300), null, false, 0);
        store.execute(() -> {
            sessionRepo.insert(session);
            attemptRepo.insert(current);
            attemptRepo.insert(added);
            attemptRepo.replace(current, current.verifiedAt(LINK_NOW));
            provenRepo.record(current.attemptId(), held);
            return null;
        });
        var authority = new SessionAuthority(sessionRepo, store, store, store, moving,
                new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        var recent = new RecentAuthenticationAuthority(authority, attemptRepo, moving, new java.security.SecureRandom());
        return new LinkFixture(owner, session, current, added, authority, recent, held, time,
                new ExternalIdentityKey("https://accounts.google.com", "sub-" + UUID.randomUUID()));
    }

    private AuthMethodsService linkService(LinkFixture f, Runnable duringVerification) {
        com.markettec.users.application.ports.out.GoogleIdentityVerifier google = (token, context) -> {
            duringVerification.run();
            return new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(f.googleKey().issuer(),
                    f.googleKey().subject(), "aud", LINK_NOW, LINK_NOW.plusSeconds(3600), new Email("ana@tec.ac.cr"));
        };
        return new AuthMethodsService(f.sessions(), f.recent(), store, store, google, "aud",
                new SqlProvenIdentityRepository(jdbc));
    }

    private ExternalIdentity link(LinkFixture f, AuthMethodsService service) {
        return service.link(f.session().sessionId(), f.owner().userId(), IdentityProvider.GOOGLE,
                f.current().attemptId(), f.added().attemptId(), "credential");
    }

    private boolean consumed(RecentAttempt a) {
        return jdbc.queryForObject("SELECT consumed FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Boolean.class, a.attemptId());
    }

    @Test
    void concurrentLinksWithTheSameProofPairHaveExactlyOneWinner() throws Exception {
        LinkFixture f = linkFixture();
        var service = linkService(f, () -> { });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<Boolean> task = () -> {
                gate.await();
                try { return link(f, service) != null; }
                catch (SessionRejectedException rejected) { return false; }
            };
            var first = pool.submit(task);
            var second = pool.submit(task);
            gate.countDown();
            assertEquals(1, (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0));
        } finally { pool.shutdownNow(); }
        assertEquals(f.owner().userId(), store.findByKey(f.googleKey()).orElseThrow().userId());
        assertTrue(consumed(f.current()));
        assertTrue(consumed(f.added()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dbo.external_identities WHERE user_id=? AND provider='GOOGLE'",
                Integer.class, f.owner().userId().value()));
    }

    @Test
    void identityCollisionKeepsTheOriginalOwnerAndRollsBackBothProofs() {
        LinkFixture f = linkFixture();
        User other = User.create(new UuidUserIdGenerator().nextId(), new Email("beto@tec.ac.cr"),
                new ProfileDetails("beto", "Beto", ""), photo, LINK_NOW);
        store.insert(other);
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, f.googleKey(), other.userId(), new Email("beto@tec.ac.cr")));
        reason(IDENTITY_CONFLICT, () -> link(f, linkService(f, () -> { })));
        assertEquals(other.userId(), store.findByKey(f.googleKey()).orElseThrow().userId());
        assertFalse(consumed(f.current()));
        assertFalse(consumed(f.added()));
        assertNull(jdbc.queryForObject("SELECT authenticated_ms FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Long.class, f.added().attemptId()));
    }

    @Test
    void proofExpiringBeforeTheInsertCommitsNothing() {
        LinkFixture f = linkFixture();
        var service = linkService(f, () -> f.time().set(LINK_NOW.plusSeconds(300)));
        assertThrows(SessionRejectedException.class, () -> link(f, service));
        assertTrue(store.findByKey(f.googleKey()).isEmpty());
        assertFalse(consumed(f.current()));
        assertFalse(consumed(f.added()));
    }

    @Test
    void disabledOrReassignedProvenIdentityIsRejected() {
        LinkFixture f = linkFixture();
        var service = linkService(f, () -> { });
        var held = store.findByKey(f.held()).orElseThrow();
        store.replaceStatus(held, IdentityLinkStatus.DISABLED);
        assertThrows(SessionRejectedException.class, () -> link(f, service));
        assertTrue(store.findByKey(f.googleKey()).isEmpty());
        assertFalse(consumed(f.current()));
        store.replaceStatus(store.findByKey(f.held()).orElseThrow(), IdentityLinkStatus.ACTIVE);
        assertNotNull(link(f, service));
    }

    // ---- Replayed recent proofs: domain errors, never integrity exceptions ----------------------------

    private record ProofFixture(User owner, Session session, RecentProofService proofs, RecentAttempt email,
                                RecentAttempt google, List<String[]> sent) { }

    private ProofFixture proofFixture() {
        Email tec = new Email("p" + UUID.randomUUID().toString().substring(0, 8) + "@tec.ac.cr");
        User owner = User.create(new UuidUserIdGenerator().nextId(), tec, details, photo, LINK_NOW);
        store.insert(owner);
        var googleKey = new ExternalIdentityKey("https://accounts.google.com", "sub-" + UUID.randomUUID());
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, owner.userId(), tec));
        Clock fixed = Clock.fixed(LINK_NOW, ZoneOffset.UTC);
        var sessionRepo = new SqlSessionRepository(jdbc);
        var attemptRepo = new SqlRecentAttemptRepository(jdbc);
        Session session = Session.start(UUID.randomUUID().toString().replace("-", ""), owner.userId(), LINK_NOW);
        store.execute(() -> { sessionRepo.insert(session); return null; });
        var authority = new SessionAuthority(sessionRepo, store, store, store, fixed,
                new java.security.SecureRandom(), denySessionActions(), csrfKeys());
        var recent = new RecentAuthenticationAuthority(authority, attemptRepo, fixed, new java.security.SecureRandom());
        var sent = java.util.Collections.synchronizedList(new java.util.ArrayList<String[]>());
        com.markettec.users.application.ports.out.GoogleIdentityVerifier verifier = (token, context) ->
                new com.markettec.users.application.ports.out.VerifiedGoogleIdentity(googleKey.issuer(),
                        googleKey.subject(), "aud", LINK_NOW, LINK_NOW.plusSeconds(3600), tec);
        var proofs = new RecentProofService(authority, recent, attemptRepo, new SqlEmailProofChallengeRepository(jdbc),
                store, store, verifier, "aud", (to, id, secret) -> sent.add(new String[]{to.address(), id}),
                new SqlProvenIdentityRepository(jdbc), fixed, new java.security.SecureRandom());
        var email = proofs.begin(session.sessionId(), owner.userId(), RecentAttempt.Method.INSTITUTIONAL_EMAIL,
                RecentProofService.LINK_CURRENT, "GOOGLE");
        var google = proofs.begin(session.sessionId(), owner.userId(), RecentAttempt.Method.GOOGLE,
                RecentProofService.LINK_CURRENT, "INSTITUTIONAL_EMAIL");
        return new ProofFixture(owner, session, proofs, email, google, sent);
    }

    private int rows(String table, String attemptId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo." + table + " WHERE attempt_id=?", Integer.class, attemptId);
    }

    @Test
    void sequentialReplaysOfAGoogleProofAndAnEmailRequestAreDomainErrorsThatKeepTheOriginals() {
        ProofFixture f = proofFixture();
        String sid = f.session().sessionId();
        UserId uid = f.owner().userId();
        f.proofs().proveGoogle(sid, uid, f.google().attemptId(), "credential");
        Long authenticated = jdbc.queryForObject("SELECT authenticated_ms FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Long.class, f.google().attemptId());
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> f.proofs().proveGoogle(sid, uid, f.google().attemptId(), "credential")).reason());
        assertEquals(1, rows("recent_attempt_identities", f.google().attemptId()));
        assertEquals(authenticated, jdbc.queryForObject("SELECT authenticated_ms FROM dbo.recent_authentication_attempts WHERE attempt_id=?",
                Long.class, f.google().attemptId()));

        f.proofs().requestEmail(sid, uid, f.email().attemptId());
        String verifier = jdbc.queryForObject("SELECT verifier FROM dbo.email_proof_challenges WHERE attempt_id=?",
                String.class, f.email().attemptId());
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> f.proofs().requestEmail(sid, uid, f.email().attemptId())).reason());
        assertEquals(1, rows("email_proof_challenges", f.email().attemptId()));
        assertEquals(verifier, jdbc.queryForObject("SELECT verifier FROM dbo.email_proof_challenges WHERE attempt_id=?",
                String.class, f.email().attemptId()));
        assertEquals(1, f.sent().size());
    }

    @Test
    void duplicateRepositoryInsertsAreTranslatedToTheDomainErrorAndNothingElse() {
        ProofFixture f = proofFixture();
        var proven = new SqlProvenIdentityRepository(jdbc);
        var challenges = new SqlEmailProofChallengeRepository(jdbc);
        var key = new ExternalIdentityKey("https://accounts.google.com", "dup-" + UUID.randomUUID());
        store.execute(() -> { proven.record(f.google().attemptId(), key); return null; });
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> store.execute(() -> { proven.record(f.google().attemptId(), key); return null; })).reason());
        store.execute(() -> { challenges.insert(f.email().attemptId(), "a".repeat(64)); return null; });
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> store.execute(() -> { challenges.insert(f.email().attemptId(), "b".repeat(64)); return null; })).reason());
        assertEquals("a".repeat(64), jdbc.queryForObject("SELECT verifier FROM dbo.email_proof_challenges WHERE attempt_id=?",
                String.class, f.email().attemptId()));
        // a different integrity failure (orphan attempt id) is NOT reported as a replay
        assertThrows(UserPersistenceException.class, () -> store.execute(() -> {
            proven.record("f".repeat(32), key); return null; }));
    }

    @Test
    void concurrentReplaysHaveOneWinnerAndTheLoserGetsTheDomainError() throws Exception {
        ProofFixture f = proofFixture();
        String sid = f.session().sessionId();
        UserId uid = f.owner().userId();
        for (int round = 0; round < 2; round++) {
            boolean email = round == 0;
            var pool = Executors.newFixedThreadPool(2);
            try {
                var gate = new CountDownLatch(1);
                Callable<Boolean> task = () -> {
                    gate.await();
                    try {
                        if (email) f.proofs().requestEmail(sid, uid, f.email().attemptId());
                        else f.proofs().proveGoogle(sid, uid, f.google().attemptId(), "credential");
                        return true;
                    } catch (SessionRejectedException replay) {
                        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, replay.reason());
                        return false;
                    }
                };
                var first = pool.submit(task);
                var second = pool.submit(task);
                gate.countDown();
                assertEquals(1, (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0));
            } finally { pool.shutdownNow(); }
        }
        assertEquals(1, rows("email_proof_challenges", f.email().attemptId()));
        assertEquals(1, rows("recent_attempt_identities", f.google().attemptId()));
        assertEquals(1, f.sent().size());
    }
}
