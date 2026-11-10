package com.markettec.users.application.session;

import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.session.PasswordOperationException.Code;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit tests (in-memory ports, mocked session authority): no database, no real hash, no network. */
class PasswordCredentialServiceTest {
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");
    private static final String SESSION = "a".repeat(32);
    private final AtomicReference<Instant> time = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private final InMemoryUserStore store = new InMemoryUserStore();
    private final PasswordFakes.Credentials credentials = new PasswordFakes.Credentials();
    private final PasswordFakes.Grants grants = new PasswordFakes.Grants();
    private final PasswordFakes.Throttle throttle = new PasswordFakes.Throttle();
    private final PasswordFakes.Hasher hasher = new PasswordFakes.Hasher();
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final PasswordCredentialService service;
    private final UserId ana = new UserId("usr_00000000-0000-4000-8000-000000000001");
    private static final String GOOD = "correct horse battery";

    @SuppressWarnings("unchecked")
    PasswordCredentialServiceTest() {
        when(sessions.withCurrentSession(any(), any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(sessions.createAuthenticated(any())).thenReturn(new AuthenticatedSession(SESSION));
        when(sessions.issue(any(), any())).thenAnswer(call -> new SessionTokens("jwt", "b".repeat(64), "c".repeat(64),
                SESSION, ana.value(), START.plusSeconds(1800)));
        service = new PasswordCredentialService(store, store, store, credentials, grants, throttle, hasher, sessions,
                ignored -> "jwt", clock);
        institutional(ana, "ana@estudiantec.cr", IdentityLinkStatus.ACTIVE);
    }

    private void institutional(UserId id, String address, IdentityLinkStatus status) {
        var email = new Email(address);
        var user = User.create(id, email, new ProfileDetails(id.value(), "Ana", "Perez"), URI.create("about:blank"), START);
        store.insert(user);
        store.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized()), id, email, status));
    }

    private void setPassword(String password) {
        grants.issue(SESSION, ana, START, START.plus(Duration.ofMinutes(10)));
        service.setInitial(SESSION, ana, password);
    }

    private Code codeOf(Runnable action) {
        return assertThrows(PasswordOperationException.class, action::run).code();
    }

    @Test void loginWithEmailAndPasswordIssuesTheSameResultWithoutAnyMagicLink() {
        setPassword(GOOD);
        var result = service.login(new Email("ana@estudiantec.cr"), GOOD);
        assertEquals("jwt", result.jwt());
        assertEquals(ana.value(), result.userId(), "same stable userId");
        verify(sessions).issue(any(), any());
    }

    @Test void wrongPasswordIsRejectedAndNothingIsIssued() {
        setPassword(GOOD);
        assertEquals(Code.INVALID_CREDENTIALS, codeOf(() -> service.login(new Email("ana@estudiantec.cr"), "not the one 1")));
        verify(sessions, never()).issue(any(), any());
    }

    @Test void unknownGoogleOnlyIneligibleDeactivatedAndPasswordlessAccountsAreIndistinguishable() {
        setPassword(GOOD);
        // Google-only owner of an address: no institutional identity at all.
        var gid = new UserId("usr_00000000-0000-4000-8000-000000000002");
        var gmail = new Email("goo@estudiantec.cr");
        store.insert(User.create(gid, gmail, new ProfileDetails(gid.value(), "G", "G"), URI.create("about:blank"), START));
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, new ExternalIdentityKey("https://accounts.google.com", "sub-1"),
                gid, gmail, IdentityLinkStatus.ACTIVE));
        // Registered through Magic Link but never set a password.
        institutional(new UserId("usr_00000000-0000-4000-8000-000000000003"), "nopass@tec.ac.cr", IdentityLinkStatus.ACTIVE);
        // Deactivated owner with a password.
        var dead = new UserId("usr_00000000-0000-4000-8000-000000000004");
        institutional(dead, "dead@tec.ac.cr", IdentityLinkStatus.ACTIVE);
        credentials.insert(new PasswordCredential(dead, hasher.hash(GOOD), 1, START, START, 0));
        store.replace(store.findById(dead).orElseThrow(), store.findById(dead).orElseThrow().deactivate());
        for (String address : List.of("nobody@estudiantec.cr", "goo@estudiantec.cr", "nopass@tec.ac.cr",
                "dead@tec.ac.cr", "outsider@gmail.com")) {
            assertEquals(Code.INVALID_CREDENTIALS, codeOf(() -> service.login(new Email(address), GOOD)), address);
        }
        verify(sessions, never()).issue(any(), any());
        assertTrue(hasher.burns.get() >= 4, "unknown accounts still spend hashing work");
    }

    @Test void fiveFailuresLockTheAddressUniformlyEvenForTheCorrectPasswordAndForUnknownAddresses() {
        setPassword(GOOD);
        for (int n = 0; n < 5; n++) { final int i = n; codeOf(() -> service.login(new Email("ana@estudiantec.cr"), "wrong password " + i)); }
        var locked = assertThrows(PasswordOperationException.class, () -> service.login(new Email("ana@estudiantec.cr"), GOOD));
        assertEquals(Code.THROTTLED, locked.code());
        assertTrue(locked.retryAfterSeconds() > 0);
        for (int n = 0; n < 5; n++) { final int i = n; codeOf(() -> service.login(new Email("ghost@estudiantec.cr"), "wrong password " + i)); }
        assertEquals(Code.THROTTLED, codeOf(() -> service.login(new Email("ghost@estudiantec.cr"), GOOD)));
        time.set(START.plus(Duration.ofMinutes(16)));
        assertEquals(ana.value(), service.login(new Email("ana@estudiantec.cr"), GOOD).userId(), "lock expires");
    }

    @Test void successfulLoginResetsTheFailureCounter() {
        setPassword(GOOD);
        for (int n = 0; n < 4; n++) { final int i = n; codeOf(() -> service.login(new Email("ana@estudiantec.cr"), "wrong password " + i)); }
        service.login(new Email("ana@estudiantec.cr"), GOOD);
        for (int n = 0; n < 4; n++) { final int i = n; codeOf(() -> service.login(new Email("ana@estudiantec.cr"), "wrong password " + i)); }
        assertEquals(ana.value(), service.login(new Email("ana@estudiantec.cr"), GOOD).userId());
    }

    @Test void stalePasswordHashIsUpgradedOnSuccessfulLogin() {
        setPassword(GOOD);
        hasher.stale = true;
        var before = credentials.rows.get(ana);
        service.login(new Email("ana@estudiantec.cr"), GOOD);
        var after = credentials.rows.get(ana);
        assertEquals(before.version() + 1, after.version());
        assertEquals(before.createdAt(), after.createdAt());
    }

    @Test void oversizedOrEmptyPasswordsAreRejectedBeforeAnyHashing() {
        for (String bad : new String[]{"", null, "x".repeat(PasswordCredentialService.MAX_INPUT + 1)})
            assertEquals(Code.INVALID_CREDENTIALS, codeOf(() -> service.login(new Email("ana@estudiantec.cr"), bad)));
    }

    @Test void firstPasswordNeedsTheOneUseGrantOfThisSession() {
        assertEquals(Code.SETUP_NOT_AUTHORIZED, codeOf(() -> service.setInitial(SESSION, ana, GOOD)));
        assertTrue(credentials.rows.isEmpty());
        grants.issue(SESSION, ana, START, START.plus(Duration.ofMinutes(10)));
        assertTrue(service.status(SESSION, ana).setupAvailable());
        service.setInitial(SESSION, ana, GOOD);
        var stored = credentials.rows.get(ana);
        assertNotEquals(GOOD, stored.hash());
        assertFalse(stored.hash().contains(GOOD));
        assertFalse(stored.toString().contains(stored.hash()), "toString redacts the hash");
        assertEquals(new PasswordCredentialService.Status(true, false), service.status(SESSION, ana));
        assertEquals(Code.PASSWORD_ALREADY_SET, codeOf(() -> service.setInitial(SESSION, ana, "another passphrase 2")));
    }

    @Test void grantsExpireAndAreBoundToTheirSessionAndUser() {
        grants.issue(SESSION, ana, START, START.plus(Duration.ofMinutes(10)));
        assertEquals(Code.SETUP_NOT_AUTHORIZED, codeOf(() -> service.setInitial("d".repeat(32), ana, GOOD)));
        time.set(START.plus(Duration.ofMinutes(11)));
        assertEquals(Code.SETUP_NOT_AUTHORIZED, codeOf(() -> service.setInitial(SESSION, ana, GOOD)));
        assertTrue(credentials.rows.isEmpty());
    }

    @Test void aWeakPasswordIsRejectedWithoutSpendingTheGrant() {
        grants.issue(SESSION, ana, START, START.plus(Duration.ofMinutes(10)));
        assertEquals(Code.WEAK_PASSWORD, codeOf(() -> service.setInitial(SESSION, ana, "short")));
        assertEquals(Code.WEAK_PASSWORD, codeOf(() -> service.setInitial(SESSION, ana, "ana@estudiantec.cr")));
        service.setInitial(SESSION, ana, GOOD);
    }

    @Test void googleOnlyAndInactiveInstitutionalAccountsCannotHavePasswords() {
        var gid = new UserId("usr_00000000-0000-4000-8000-000000000002");
        var gmail = new Email("goo@estudiantec.cr");
        store.insert(User.create(gid, gmail, new ProfileDetails(gid.value(), "G", "G"), URI.create("about:blank"), START));
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, new ExternalIdentityKey("https://accounts.google.com", "sub-1"),
                gid, gmail, IdentityLinkStatus.ACTIVE));
        grants.issue(SESSION, gid, START, START.plus(Duration.ofMinutes(10)));
        assertEquals(Code.SETUP_NOT_AUTHORIZED, codeOf(() -> service.setInitial(SESSION, gid, GOOD)));
        assertEquals(new PasswordCredentialService.Status(false, false), service.status(SESSION, gid));
        assertTrue(credentials.rows.isEmpty());
    }

    @Test void changeRequiresTheCurrentPasswordAndRevokesEveryOtherSession() {
        setPassword(GOOD);
        assertEquals(Code.INVALID_CREDENTIALS, codeOf(() -> service.change(SESSION, ana, "wrong current 1", "brand new passphrase")));
        assertTrue(hasher.matches(GOOD, credentials.rows.get(ana).hash()));
        service.change(SESSION, ana, GOOD, "brand new passphrase");
        assertTrue(hasher.matches("brand new passphrase", credentials.rows.get(ana).hash()));
        verify(sessions).revokeOtherSessions(ana, SESSION);
        assertEquals(Code.INVALID_CREDENTIALS, codeOf(() -> service.login(new Email("ana@estudiantec.cr"), GOOD)));
        assertEquals(ana.value(), service.login(new Email("ana@estudiantec.cr"), "brand new passphrase").userId());
    }

    @Test void failedChangeAttemptsAreThrottledAndNothingIsRevoked() {
        setPassword(GOOD);
        for (int n = 0; n < 5; n++) { final int i = n; codeOf(() -> service.change(SESSION, ana, "wrong current " + i, "brand new passphrase")); }
        assertEquals(Code.THROTTLED, codeOf(() -> service.change(SESSION, ana, GOOD, "brand new passphrase")));
        verify(sessions, never()).revokeOtherSessions(any(), any());
    }

    @Test void changeRejectsReuseWeakAndMissingCredential() {
        assertEquals(Code.PASSWORD_NOT_SET, codeOf(() -> service.change(SESSION, ana, GOOD, "brand new passphrase")));
        setPassword(GOOD);
        assertEquals(Code.WEAK_PASSWORD, codeOf(() -> service.change(SESSION, ana, GOOD, GOOD)));
        assertEquals(Code.WEAK_PASSWORD, codeOf(() -> service.change(SESSION, ana, GOOD, "short")));
        verify(sessions, never()).revokeOtherSessions(any(), any());
    }
}
