package com.markettec.users.application.session;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoogleRegistrationServiceTest {
    private final Instant start = Instant.parse("2026-11-03T12:00:00Z");
    private final MutableClock clock = new MutableClock(start);
    private final InMemoryUserStore users = new InMemoryUserStore();
    private final Map<String, GoogleLoginIntent> intents = new HashMap<>();
    private final Map<String, PendingGoogleRegistration> pending = new HashMap<>();
    private final ExternalIdentityKey key = new ExternalIdentityKey("https://accounts.google.com", "new-google-sub");
    private Email email = new Email("someone@tec.ac.cr");
    private String delivered;
    private boolean deliveryFails, historical;
    private UserId lastGenerated;
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final UserTransactions tx = new UserTransactions() {
        public synchronized <T> T execute(Supplier<T> work) {
            var beforeIntents = new HashMap<>(intents);
            var beforePending = new HashMap<>(pending);
            try { return users.execute(work); }
            catch (RuntimeException | Error failure) {
                intents.clear(); intents.putAll(beforeIntents);
                pending.clear(); pending.putAll(beforePending);
                throw failure;
            }
        }
    };
    private final GoogleLoginIntentRepository intentRepo = new GoogleLoginIntentRepository() {
        public void insert(GoogleLoginIntent a) { intents.put(a.attemptId(), a); }
        public Optional<GoogleLoginIntent> loadLocked(String id) { return Optional.ofNullable(intents.get(id)); }
        public void replace(GoogleLoginIntent before, GoogleLoginIntent after) {
            if (!intents.replace(before.attemptId(), before, after)) throw new IllegalStateException("race");
        }
    };
    private final PendingGoogleRegistrationRepository pendingRepo = new PendingGoogleRegistrationRepository() {
        public void insert(PendingGoogleRegistration p) { pending.put(p.attemptId(), p); }
        public Optional<PendingGoogleRegistration> loadLocked(String id) { return Optional.ofNullable(pending.get(id)); }
        public void replace(PendingGoogleRegistration before, PendingGoogleRegistration after) {
            if (!pending.replace(before.attemptId(), before, after)) throw new IllegalStateException("race");
        }
        public boolean historicalEmailExists(String ignored) { return historical; }
    };
    private final GoogleIdentityVerifier verifier = (token, context) -> {
        if (!("nonce:" + context.expectedNonce()).equals(token))
            throw new IllegalArgumentException("invalid token");
        return new VerifiedGoogleIdentity(key.issuer(), key.subject(), context.expectedAudience(),
                start, start.plusSeconds(3600), email);
    };
    private GoogleRegistrationService service() {
        when(sessions.createGoogle(any())).thenReturn(new AuthenticatedSession("a".repeat(32)));
        doAnswer(call -> new SessionTokens("jwt", "b".repeat(64),
                "c".repeat(64), "a".repeat(32), lastGenerated.value(), start.plusSeconds(1800)))
                .when(sessions).issue(any(), any());
        InstitutionalLinkSender sender = (recipient, attemptId, secret) -> {
            if (deliveryFails) throw new IllegalStateException("delivery failed");
            assertEquals(email, recipient);
            delivered = secret;
        };
        return new GoogleRegistrationService(intentRepo, pendingRepo, tx, verifier, users, users,
                () -> { lastGenerated = new UuidUserIdGenerator().nextId(); return lastGenerated; },
                sessions, ignored -> "jwt", sender, "client-id", clock, new SecureRandom());
    }
    private GoogleLoginService.Challenge prepared(GoogleRegistrationService service) {
        var challenge = service.begin();
        service.prepare(challenge.attemptId(), challenge.browserSecret(), "nonce:" + challenge.nonce());
        return challenge;
    }

    @Test void googleProofIsPendingEvenWhenEmailVerifiedAndCreatesNoAccountOrCredentials() {
        var service = service();
        var c = prepared(service);
        assertTrue(intents.get(c.attemptId()).consumed());
        assertEquals(PendingGoogleRegistration.State.PENDING, pending.get(c.attemptId()).state());
        assertNull(pending.get(c.attemptId()).verifier());
        assertTrue(users.findByKey(key).isEmpty());
        verifyNoInteractions(sessions);
        assertThrows(SessionRejectedException.class, () ->
                service.prepare(c.attemptId(), c.browserSecret(), "nonce:" + c.nonce()));
    }

    @Test void exactInstitutionalDomainsOnlyAndMismatchedEmailCannotReceiveLink() {
        for (String address : List.of("a@estudiantec.cr", "a@itcr.ac.cr", "a@tec.ac.cr",
                "a@x.tec.ac.cr", "a@tec.ac.cr.evil", "a@example.com")) {
            email = new Email(address);
            var service = service();
            var c = prepared(service);
            boolean eligible = Set.of("a@estudiantec.cr", "a@itcr.ac.cr", "a@tec.ac.cr").contains(address);
            assertEquals(eligible, pending.containsKey(c.attemptId()));
            service.requestLink(c.attemptId(), c.browserSecret(), "other@tec.ac.cr");
            assertNull(delivered);
            if (eligible) {
                service.requestLink(c.attemptId(), c.browserSecret(), email.normalized());
                assertNotNull(delivered);
                assertNotEquals(delivered, pending.get(c.attemptId()).verifier());
                delivered = null;
            }
        }
    }

    @Test void linkRequiresOriginalBrowserAndCanConfirmOnlyOnce() {
        var service = service();
        var c = prepared(service);
        service.requestLink(c.attemptId(), c.browserSecret(), email.normalized());
        String secret = delivered;
        assertThrows(SessionRejectedException.class, () -> service.confirm(c.attemptId(), "f".repeat(64), secret));
        assertThrows(SessionRejectedException.class, () -> service.confirm(c.attemptId(), c.browserSecret(), "e".repeat(64)));
        assertTrue(users.findByKey(key).isEmpty());
        var result = service.confirm(c.attemptId(), c.browserSecret(), secret);
        assertEquals(lastGenerated.value(), result.userId());
        assertTrue(users.findById(lastGenerated).orElseThrow().profile().verificado());
        assertEquals(PendingGoogleRegistration.State.CONFIRMED, pending.get(c.attemptId()).state());
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                assertThrows(SessionRejectedException.class, () ->
                        service.confirm(c.attemptId(), c.browserSecret(), secret)).reason());
        verify(sessions, times(1)).issue(any(), any());
    }

    @Test void deliveryFailureRollsBackVerifierAndCannotConfirm() {
        var service = service();
        var c = prepared(service);
        deliveryFails = true;
        assertThrows(IllegalStateException.class, () ->
                service.requestLink(c.attemptId(), c.browserSecret(), email.normalized()));
        assertEquals(PendingGoogleRegistration.State.PENDING, pending.get(c.attemptId()).state());
        assertTrue(users.findByKey(key).isEmpty());
    }

    @Test void expiryHistoricalEmailAndExistingGoogleLinkDoNotCreateAccounts() {
        var service = service();
        var c = prepared(service);
        service.requestLink(c.attemptId(), c.browserSecret(), email.normalized());
        historical = true;
        assertThrows(SessionRejectedException.class, () -> service.confirm(c.attemptId(), c.browserSecret(), delivered));
        assertTrue(users.findByKey(key).isEmpty());
        historical = false;
        clock.set(start.plusSeconds(300));
        assertEquals(SessionRejectedException.Reason.ATTEMPT_EXPIRED,
                assertThrows(SessionRejectedException.class, () ->
                        service.confirm(c.attemptId(), c.browserSecret(), delivered)).reason());
    }

    @Test void jwtFailureRollsBackUserLinkAndConsumption() {
        var service = service();
        var c = prepared(service);
        service.requestLink(c.attemptId(), c.browserSecret(), email.normalized());
        doThrow(new IllegalStateException("JWT failed")).when(sessions).issue(any(), any());
        assertThrows(IllegalStateException.class, () -> service.confirm(c.attemptId(), c.browserSecret(), delivered));
        assertTrue(users.findByKey(key).isEmpty());
        assertTrue(users.findById(lastGenerated).isEmpty());
        assertEquals(PendingGoogleRegistration.State.LINK_ISSUED, pending.get(c.attemptId()).state());
    }

    @Test void anotherIntentCannotUseTheLinkAndReplayCannotReissue() {
        var service = service();
        var first = prepared(service);
        service.requestLink(first.attemptId(), first.browserSecret(), email.normalized());
        String secret = delivered;
        var second = prepared(service);
        assertThrows(SessionRejectedException.class, () ->
                service.confirm(second.attemptId(), second.browserSecret(), secret));
        delivered = null;
        service.requestLink(first.attemptId(), first.browserSecret(), email.normalized());
        assertNull(delivered);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void set(Instant value) { now = value; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
