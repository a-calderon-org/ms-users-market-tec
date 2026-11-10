package com.markettec.users.application.session;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.session.PasswordOperationException.Code;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * B02 identity continuity for the password flows. A mailbox link proves control of the mailbox now, never
 * ownership of an account's history: it must not replace a credential, merge identities, link Google by address
 * or hand a historical account to a new holder of the address.
 */
class PasswordIdentityContinuityTest {
    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");
    private static final String GOOD = "correct horse battery";
    private final AtomicReference<Instant> time = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private final InMemoryUserStore store = new InMemoryUserStore();
    private final Map<String, EmailLoginAttempt> rows = new LinkedHashMap<>();
    private final List<String[]> loginMail = new ArrayList<>();
    private final List<String[]> recoveryMail = new ArrayList<>();
    private final PasswordFakes.Credentials credentials = new PasswordFakes.Credentials();
    private final PasswordFakes.Grants grants = new PasswordFakes.Grants();
    private final PasswordFakes.Throttle throttle = new PasswordFakes.Throttle();
    private final PasswordFakes.Hasher hasher = new PasswordFakes.Hasher();
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final Set<String> googleEmails = new HashSet<>();
    private final AtomicInteger sessionCounter = new AtomicInteger();
    private final AtomicInteger accountsCreated = new AtomicInteger();
    private final AtomicReference<String> lastUser = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean recoveryProviderDown = new java.util.concurrent.atomic.AtomicBoolean();
    private final EmailLoginService email;
    private final PasswordCredentialService passwords;
    private String browser;

    @SuppressWarnings("unchecked")
    PasswordIdentityContinuityTest() {
        when(sessions.createAuthenticated(any())).thenAnswer(call -> {
            VerifiedExternalIdentity evidence = call.getArgument(0);
            lastUser.set(store.findByKey(evidence.key()).orElseThrow(SessionRejectedException::new).userId().value());
            return new AuthenticatedSession(String.format("%032x", sessionCounter.incrementAndGet()));
        });
        doAnswer(call -> {
            AuthenticatedSession authenticated = call.getArgument(0);
            return new SessionTokens("jwt", "b".repeat(64), "c".repeat(64), authenticated.sessionId(),
                    lastUser.get(), START.plusSeconds(1800));
        }).when(sessions).issue(any(), any());
        when(sessions.withCurrentSession(any(), any(), any()))
                .thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        var attempts = new EmailLoginAttemptRepository() {
            public void insert(EmailLoginAttempt a) { rows.put(a.attemptId(), a); }
            public Optional<EmailLoginAttempt> loadLocked(String id) { return Optional.ofNullable(rows.get(id)); }
            public void replace(EmailLoginAttempt before, EmailLoginAttempt after) {
                if (!rows.replace(before.attemptId(), before, after)) throw new IllegalStateException("race");
            }
            public int countSince(String recipient, Instant since) {
                return (int) rows.values().stream().filter(a -> a.recipient().normalized().equals(recipient)
                        && !a.createdAt().isBefore(since)).count();
            }
        };
        var pending = new PendingGoogleRegistrationRepository() {
            public void insert(PendingGoogleRegistration p) { fail(); }
            public Optional<PendingGoogleRegistration> loadLocked(String id) { return Optional.empty(); }
            public void replace(PendingGoogleRegistration a, PendingGoogleRegistration b) { fail(); }
            public boolean historicalEmailExists(String address) {
                return googleEmails.contains(address) || store.findByKey(
                        new ExternalIdentityKey(EmailLoginService.ISSUER, address)).isPresent();
            }
        };
        email = new EmailLoginService(attempts, store, store, store, pending,
                () -> { accountsCreated.incrementAndGet(); return new UuidUserIdGenerator().nextId(); }, sessions,
                ignored -> "jwt", (to, id, secret) -> loginMail.add(new String[]{to.address(), id, secret}), clock,
                new SecureRandom(), credentials, grants,
                (to, id, secret) -> {
                    if (recoveryProviderDown.get()) throw new EmailDeliveryException();
                    recoveryMail.add(new String[]{to.address(), id, secret});
                });
        passwords = new PasswordCredentialService(store, store, store, credentials, grants, throttle, hasher,
                sessions, ignored -> "jwt", clock);
    }

    private GoogleLoginService.Result signInWithLink(String address) {
        browser = email.request(new Email(address));
        var link = loginMail.get(loginMail.size() - 1);
        return email.confirm(link[1], browser, link[2]);
    }

    private GoogleLoginService.Result recoverWithLink(String address) {
        int before = recoveryMail.size();
        browser = email.requestRecovery(new Email(address));
        assertEquals(before + 1, recoveryMail.size(), "a recovery link was sent");
        var link = recoveryMail.get(recoveryMail.size() - 1);
        return email.confirm(link[1], browser, link[2]);
    }

    private UserId userOf(String address) {
        return store.findByKey(new ExternalIdentityKey(EmailLoginService.ISSUER, new Email(address).normalized()))
                .orElseThrow().userId();
    }

    // 1 — new institutional account -------------------------------------------------------------------------

    @Test void newInstitutionalAccountCreatedByTheLinkMaySetItsFirstPasswordFromThatSession() {
        var result = signInWithLink("ana@estudiantec.cr");
        UserId id = userOf("ana@estudiantec.cr");
        assertEquals(id.value(), result.userId());
        assertTrue(grants.live(result.sessionId(), id, START), "the confirmed link authorizes exactly one setup");
        passwords.setInitial(result.sessionId(), id, GOOD);
        // Later: plain email + password, no new link.
        int mails = loginMail.size();
        var again = passwords.login(new Email("ana@estudiantec.cr"), GOOD);
        assertEquals(id.value(), again.userId());
        assertEquals(mails, loginMail.size(), "no Magic Link is requested for a normal sign-in");
        assertEquals(1, accountsCreated.get());
    }

    // 2 — existing Magic Link account configuring a password --------------------------------------------------

    @Test void existingMagicLinkOnlyAccountGetsAGrantOnTheNextConfirmedLinkAndKeepsItsUserId() {
        var first = signInWithLink("ana@estudiantec.cr");
        UserId id = userOf("ana@estudiantec.cr");
        time.set(START.plus(Duration.ofHours(1)));
        var second = signInWithLink("ana@estudiantec.cr");
        assertEquals(id.value(), second.userId(), "same stable userId");
        assertEquals(1, accountsCreated.get(), "no duplicate account");
        // The first session's unused grant has expired; the fresh one works.
        assertFalse(grants.live(first.sessionId(), id, time.get()));
        assertTrue(grants.live(second.sessionId(), id, time.get()));
        passwords.setInitial(second.sessionId(), id, GOOD);
        assertEquals(id.value(), passwords.login(new Email("ana@estudiantec.cr"), GOOD).userId());
    }

    // 3 — legitimate recovery ---------------------------------------------------------------------------------

    @Test void recoveryOfAnAccountThatNeverHadAPasswordPreservesTheOriginalUserId() {
        signInWithLink("ana@estudiantec.cr");
        UserId original = userOf("ana@estudiantec.cr");
        time.set(START.plus(Duration.ofHours(2)));
        var recovered = recoverWithLink("ana@estudiantec.cr");
        assertEquals(original.value(), recovered.userId());
        assertEquals(1, accountsCreated.get());
        assertTrue(grants.live(recovered.sessionId(), original, time.get()));
        passwords.setInitial(recovered.sessionId(), original, GOOD);
        assertEquals(original.value(), passwords.login(new Email("ana@estudiantec.cr"), GOOD).userId());
    }

    // 4/6 — recovery without continuity -----------------------------------------------------------------------

    @Test void recoveryNeverReplacesAnExistingPasswordAndIssuesNothing() {
        var result = signInWithLink("ana@estudiantec.cr");
        UserId id = userOf("ana@estudiantec.cr");
        passwords.setInitial(result.sessionId(), id, GOOD);
        String hashBefore = credentials.rows.get(id).hash();
        int sessionsBefore = sessionCounter.get();
        time.set(START.plus(Duration.ofDays(400))); // long gap: the mailbox may well belong to someone else now
        browser = email.requestRecovery(new Email("ana@estudiantec.cr"));
        var link = recoveryMail.get(recoveryMail.size() - 1);
        var blocked = assertThrows(SessionRejectedException.class, () -> email.confirm(link[1], browser, link[2]));
        assertEquals(SessionRejectedException.Reason.CONTINUITY_REQUIRED, blocked.reason());
        assertEquals(sessionsBefore, sessionCounter.get(), "no session is issued");
        assertEquals(hashBefore, credentials.rows.get(id).hash(), "the password is untouched");
        assertTrue(grants.rows.values().stream().allMatch(g -> g.consumed() || !g.expires().isAfter(time.get())),
                "no live grant was left behind");
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                assertThrows(SessionRejectedException.class, () -> email.confirm(link[1], browser, link[2])).reason(),
                "the link is spent");
        assertEquals(id.value(), passwords.login(new Email("ana@estudiantec.cr"), GOOD).userId(), "the owner still signs in");
    }

    // 5 — mailbox reassigned ----------------------------------------------------------------------------------

    @Test void aNewHolderOfTheMailboxCannotSetOrReplaceTheHistoricalAccountPassword() {
        var owner = signInWithLink("ana@estudiantec.cr");
        UserId id = userOf("ana@estudiantec.cr");
        passwords.setInitial(owner.sessionId(), id, GOOD);
        String hash = credentials.rows.get(id).hash();
        time.set(START.plus(Duration.ofDays(500)));
        // The new holder may still use the ordinary Magic Link sign-in (existing, independent behaviour)...
        var intruder = signInWithLink("ana@estudiantec.cr");
        // ...but gets no authority over the credential.
        assertFalse(grants.live(intruder.sessionId(), id, time.get()));
        assertEquals(Code.PASSWORD_ALREADY_SET,
                assertThrows(PasswordOperationException.class, () -> passwords.setInitial(intruder.sessionId(), id, "intruder passphrase 1")).code());
        assertEquals(Code.INVALID_CREDENTIALS,
                assertThrows(PasswordOperationException.class, () -> passwords.change(intruder.sessionId(), id, "guess guess guess", "intruder passphrase 1")).code());
        assertEquals(hash, credentials.rows.get(id).hash());
    }

    // 4 (Google) — conflicts -----------------------------------------------------------------------------------

    @Test void anAddressOwnedByAGoogleAccountNeverCreatesOrLinksAnInstitutionalIdentity() {
        googleEmails.add("goo@estudiantec.cr");
        var gid = new UserId("usr_00000000-0000-4000-8000-000000000009");
        var gmail = new Email("goo@estudiantec.cr");
        store.insert(User.create(gid, gmail, new ProfileDetails(gid.value(), "G", "G"), URI.create("about:blank"), START));
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, new ExternalIdentityKey("https://accounts.google.com", "sub-9"),
                gid, gmail, IdentityLinkStatus.ACTIVE));
        browser = email.request(gmail);
        var link = loginMail.get(0);
        var blocked = assertThrows(SessionRejectedException.class, () -> email.confirm(link[1], browser, link[2]));
        assertEquals(SessionRejectedException.Reason.CONTINUITY_REQUIRED, blocked.reason());
        assertEquals(0, accountsCreated.get(), "no duplicate user");
        assertTrue(store.findByKey(new ExternalIdentityKey(EmailLoginService.ISSUER, "goo@estudiantec.cr")).isEmpty(),
                "no institutional identity was linked by address coincidence");
        assertEquals(1, store.findByUser(gid).size());
        assertTrue(grants.rows.isEmpty());
        // No password is possible for the Google account, and password sign-in cannot reach it.
        assertEquals(Code.INVALID_CREDENTIALS,
                assertThrows(PasswordOperationException.class, () -> passwords.login(gmail, GOOD)).code());
    }

    @Test void recoveryIsNeverOfferedForAGoogleOnlyOrUnknownAddress() {
        googleEmails.add("goo@estudiantec.cr");
        var gid = new UserId("usr_00000000-0000-4000-8000-000000000009");
        var gmail = new Email("goo@estudiantec.cr");
        store.insert(User.create(gid, gmail, new ProfileDetails(gid.value(), "G", "G"), URI.create("about:blank"), START));
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, new ExternalIdentityKey("https://accounts.google.com", "sub-9"),
                gid, gmail, IdentityLinkStatus.ACTIVE));
        for (String address : List.of("goo@estudiantec.cr", "ghost@estudiantec.cr")) {
            String secret = email.requestRecovery(new Email(address));
            assertEquals(64, secret.length(), "the acknowledgement is identical, so nothing is revealed");
        }
        assertTrue(recoveryMail.isEmpty(), "nothing is sent");
        assertTrue(rows.isEmpty(), "nothing is stored");
    }

    @Test void aRecoveryDeliveryFailureIsAcknowledgedLikeAnUnknownAddress() {
        signInWithLink("ana@estudiantec.cr");
        recoveryProviderDown.set(true);
        String known = assertDoesNotThrow(() -> email.requestRecovery(new Email("ana@estudiantec.cr")));
        String unknown = email.requestRecovery(new Email("ghost@estudiantec.cr"));
        assertEquals(64, known.length(), "a failed delivery is not reported to the anonymous caller");
        assertEquals(unknown.length(), known.length());
        assertTrue(recoveryMail.isEmpty());
    }

    // unauthorized recovery attempts ---------------------------------------------------------------------------

    @Test void aRecoveryLinkRedeemedFromAnotherBrowserOrWithATamperedTokenChangesNothing() {
        signInWithLink("ana@estudiantec.cr");
        browser = email.requestRecovery(new Email("ana@estudiantec.cr"));
        var link = recoveryMail.get(0);
        int sessionsBefore = sessionCounter.get();
        assertThrows(SessionRejectedException.class, () -> email.confirm(link[1], "9".repeat(64), link[2]));
        assertThrows(SessionRejectedException.class, () -> email.confirm(link[1], browser, "0".repeat(64)));
        assertEquals(sessionsBefore, sessionCounter.get());
        assertFalse(rows.get(link[1]).consumed(), "the legitimate browser can still redeem it");
        assertEquals(EmailLoginAttempt.Purpose.RECOVERY, rows.get(link[1]).purpose());
    }

    @Test void aSuspendedInstitutionalIdentityCannotBeRecovered() {
        signInWithLink("ana@estudiantec.cr");
        var key = new ExternalIdentityKey(EmailLoginService.ISSUER, "ana@estudiantec.cr");
        store.replaceStatus(store.findByKey(key).orElseThrow(), IdentityLinkStatus.DISABLED);
        email.requestRecovery(new Email("ana@estudiantec.cr"));
        assertTrue(recoveryMail.isEmpty());
    }

    @Test void recoveryWithoutConfigurationIsUnavailableInsteadOfSilentlyDowngrading() {
        var plain = new EmailLoginService(new EmailLoginAttemptRepository() {
            public void insert(EmailLoginAttempt a) { fail(); }
            public Optional<EmailLoginAttempt> loadLocked(String id) { return Optional.empty(); }
            public void replace(EmailLoginAttempt a, EmailLoginAttempt b) { fail(); }
            public int countSince(String r, Instant s) { return 0; }
        }, store, store, store, new PendingGoogleRegistrationRepository() {
            public void insert(PendingGoogleRegistration p) { fail(); }
            public Optional<PendingGoogleRegistration> loadLocked(String id) { return Optional.empty(); }
            public void replace(PendingGoogleRegistration a, PendingGoogleRegistration b) { fail(); }
            public boolean historicalEmailExists(String e) { return false; }
        }, () -> new UuidUserIdGenerator().nextId(), sessions, ignored -> "jwt", (to, id, s) -> fail(), clock, new SecureRandom());
        assertFalse(plain.recoveryAvailable());
        assertThrows(IllegalStateException.class, () -> plain.requestRecovery(new Email("ana@estudiantec.cr")));
    }

    @Test void aPasswordLinkInterleavedWithAnOrdinaryLinkNeverSwapsPurposes() {
        signInWithLink("ana@estudiantec.cr");
        browser = email.request(new Email("ana@estudiantec.cr"));
        var ordinary = loginMail.get(loginMail.size() - 1);
        assertEquals(EmailLoginAttempt.Purpose.LOGIN, rows.get(ordinary[1]).purpose());
        var recovery = email.requestRecovery(new Email("ana@estudiantec.cr"));
        assertNotNull(recovery);
        assertEquals(EmailLoginAttempt.Purpose.RECOVERY, rows.get(recoveryMail.get(0)[1]).purpose());
    }
}
