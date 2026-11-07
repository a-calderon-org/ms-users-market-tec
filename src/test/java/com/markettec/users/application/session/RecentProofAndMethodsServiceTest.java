package com.markettec.users.application.session;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.out.GoogleIdentityVerifier;
import com.markettec.users.application.ports.out.VerifiedGoogleIdentity;
import com.markettec.users.domain.model.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecentProofAndMethodsServiceTest {
    private static final Instant START = Instant.parse("2026-11-03T12:00:00Z");
    private static final String SID = "0123456789abcdef0123456789abcdef";
    private final AtomicReference<Instant> time = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private final InMemoryUserStore store = new InMemoryUserStore();
    private final UserId user = new UuidUserIdGenerator().nextId();
    private final UserId stranger = new UuidUserIdGenerator().nextId();
    private final Email email = new Email("ana@tec.ac.cr");
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final Map<String, RecentAttempt> data = new HashMap<>();
    private final Map<String, String[]> challenges = new HashMap<>();
    private final List<String[]> sent = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final RecentAttemptRepository attempts = new RecentAttemptRepository() {
        public void insert(RecentAttempt a) { data.put(a.attemptId(), a); }
        public Optional<RecentAttempt> loadLocked(String id) { return Optional.ofNullable(data.get(id)); }
        public void replace(RecentAttempt before, RecentAttempt after) {
            if (!data.replace(before.attemptId(), before, after)) throw new IllegalStateException("stale");
        }
    };
    private final EmailProofChallengeRepository repo = new EmailProofChallengeRepository() {
        public void insert(String id, String verifier) {
            if (challenges.putIfAbsent(id, new String[]{verifier, "open"}) != null)
                throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        }
        public Optional<String> loadOpenLocked(String id) {
            var c = challenges.get(id);
            return c != null && c[1].equals("open") ? Optional.of(c[0]) : Optional.empty();
        }
        public void markConsumed(String id) { challenges.get(id)[1] = "used"; }
    };
    private final Map<ExternalIdentityKey, ExternalIdentity> idMap = new HashMap<>();
    private final Map<String, ExternalIdentityKey> provenMap = new HashMap<>();
    private Runnable onInsert = () -> { };
    private final com.markettec.users.application.ports.out.ExternalIdentityRepository ids =
            new com.markettec.users.application.ports.out.ExternalIdentityRepository() {
        public Optional<ExternalIdentity> findByKey(ExternalIdentityKey key) { return Optional.ofNullable(idMap.get(key)); }
        public List<ExternalIdentity> findByUser(UserId id) { return idMap.values().stream().filter(i -> i.userId().equals(id)).toList(); }
        public ExternalIdentity insertOrGet(ExternalIdentity identity) {
            var existing = idMap.get(identity.key());
            if (existing != null && (!existing.userId().equals(identity.userId()) || existing.provider() != identity.provider()))
                throw new UserDomainException(UserDomainException.Reason.IDENTITY_CONFLICT);
            store.findById(identity.userId()).orElseThrow().requireActive();
            if (existing != null) return existing;
            idMap.put(identity.key(), identity);
            onInsert.run();
            return identity;
        }
        public void replaceStatus(ExternalIdentity expected, IdentityLinkStatus status) {
            idMap.replace(expected.key(), expected, expected.withStatus(status));
        }
    };
    private final ProvenIdentityRepository proven = new ProvenIdentityRepository() {
        public void record(String attemptId, ExternalIdentityKey key) {
            if (provenMap.putIfAbsent(attemptId, key) != null)
                throw new SessionRejectedException(SessionRejectedException.Reason.ATTEMPT_CONSUMED);
        }
        public Optional<ExternalIdentityKey> find(String attemptId) { return Optional.ofNullable(provenMap.get(attemptId)); }
    };
    private final ExternalIdentityKey googleKey = new ExternalIdentityKey("https://accounts.google.com", "g-sub");
    private String googleSubject = "g-sub";
    private final GoogleIdentityVerifier google = (token, ctx) -> {
        if (!("nonce:" + ctx.expectedNonce()).equals(token)) throw new com.markettec.users.adapters.out.google.InvalidGoogleTokenException();
        return new VerifiedGoogleIdentity(googleKey.issuer(), googleSubject, ctx.expectedAudience(),
                START, START.plusSeconds(3600), email);
    };
    private final RecentAuthenticationAuthority recent;
    private final RecentProofService proofs;
    private final AuthMethodsService methods;

    RecentProofAndMethodsServiceTest() {
        when(sessions.withCurrentSession(anyString(), any(), any())).thenAnswer(call -> {
            if (!SID.equals(call.getArgument(0)) || !user.equals(call.getArgument(1))) throw new SessionRejectedException();
            @SuppressWarnings("unchecked") Supplier<Object> work = call.getArgument(2);
            var before = new HashMap<>(data);
            var idBefore = new HashMap<>(idMap);
            var provenBefore = new HashMap<>(provenMap);
            try { return work.get(); }
            catch (RuntimeException failure) {
                data.clear(); data.putAll(before);
                idMap.clear(); idMap.putAll(idBefore);
                provenMap.clear(); provenMap.putAll(provenBefore);
                throw failure;
            }
        });
        when(sessions.current(SID)).thenReturn(Optional.of(Session.start(SID, user, START)));
        var u = User.create(user, email, new ProfileDetails("ana", "Ana", ""), URI.create("about:blank"), START);
        store.insert(u);
        store.replace(u, u.verifyProfileEmail(email));
        recent = new RecentAuthenticationAuthority(sessions, attempts, clock, random);
        proofs = new RecentProofService(sessions, recent, attempts, repo, store, ids, google, "client-id",
                (to, id, secret) -> sent.add(new String[]{to.address(), id, secret}), proven, clock, random);
        methods = new AuthMethodsService(sessions, recent, ids, store, google, "client-id", proven);
    }

    private void linkEmailIdentity() {
        ids.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized()), user, email));
    }

    private RecentAttempt currentEmailAttempt() {
        return proofs.begin(SID, user, RecentAttempt.Method.INSTITUTIONAL_EMAIL, RecentProofService.LINK_CURRENT, "GOOGLE");
    }

    private RecentAttempt newGoogleAttempt() {
        return proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, RecentProofService.LINK_NEW, "GOOGLE");
    }

    private void verifyByEmail(RecentAttempt a) {
        proofs.requestEmail(SID, user, a.attemptId());
        var link = sent.get(sent.size() - 1);
        assertEquals(email.address(), link[0]);
        proofs.confirmEmail(SID, user, a.attemptId(), link[2]);
    }

    @Test void onlyAllowlistedOperationMethodTargetCombinationsBegin() {
        var mail = RecentAttempt.Method.INSTITUTIONAL_EMAIL;
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user, mail, "DELETE_EVERYTHING", "GOOGLE"));
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user, mail, "LINK_IDENTITY", "GOOGLE"));
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user, mail, "DEACTIVATE_USER", stranger.value()));
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user, RecentAttempt.Method.PASSKEY, RecentProofService.LINK_CURRENT, "GOOGLE"));
        // current proof must come from the other method; new email proof only for the account's own address
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, RecentProofService.LINK_CURRENT, "GOOGLE"));
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user, mail, RecentProofService.LINK_NEW, "INSTITUTIONAL_EMAIL:evil@tec.ac.cr"));
        assertThrows(SessionRejectedException.class, () -> proofs.begin("f".repeat(32), user, mail, RecentProofService.LINK_CURRENT, "GOOGLE"));
        assertNotNull(currentEmailAttempt());
        assertNotNull(newGoogleAttempt());
        assertNotNull(proofs.begin(SID, user, mail, RecentProofService.LINK_NEW, "INSTITUTIONAL_EMAIL:" + email.normalized()));
    }

    @Test void emailLinkProvesControlOnceAndOnlyForTheBoundAttempt() {
        linkEmailIdentity();
        var a = currentEmailAttempt();
        var other = currentEmailAttempt();
        proofs.requestEmail(SID, user, a.attemptId());
        var link = sent.get(0);
        assertNotEquals(link[2], challenges.get(a.attemptId())[0]);
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> proofs.requestEmail(SID, user, a.attemptId())).reason());
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, other.attemptId(), link[2]));
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, a.attemptId(), "f".repeat(64)));
        proofs.confirmEmail(SID, user, a.attemptId(), link[2]);
        assertNotNull(data.get(a.attemptId()).authenticatedAt());
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, a.attemptId(), link[2]));
    }

    @Test void expiredEmailLinkIsRejected() {
        var a = currentEmailAttempt();
        proofs.requestEmail(SID, user, a.attemptId());
        time.set(START.plusSeconds(299));
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, a.attemptId(), sent.get(0)[2]));
    }

    @Test void addingGoogleNeedsBothIndependentProofsAndConsumesBothAtomically() {
        linkEmailIdentity();
        var cur = currentEmailAttempt();
        var neu = newGoogleAttempt();
        String token = "nonce:" + neu.nonce();
        // a verified profile address and a valid Google credential are not enough without the current proof
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), token));
        verifyByEmail(cur);
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), cur.attemptId(), token));
        assertThrows(com.markettec.users.adapters.out.google.InvalidGoogleTokenException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), "nonce:other"));
        assertFalse(data.get(cur.attemptId()).consumed());
        assertNull(data.get(neu.attemptId()).authenticatedAt());
        var linked = methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), token);
        assertEquals(user, linked.userId());
        assertEquals(user, ids.findByKey(googleKey).orElseThrow().userId());
        assertTrue(data.get(cur.attemptId()).consumed());
        assertTrue(data.get(neu.attemptId()).consumed());
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), token));
        assertEquals(2, methods.list(SID, user).size());
    }

    @Test void currentProofRequiresTheAccountToReallyHoldThatActiveIdentity() {
        // no email identity linked: control of the mailbox does not make it a current method
        var cur = currentEmailAttempt();
        proofs.requestEmail(SID, user, cur.attemptId());
        assertThrows(SessionRejectedException.class,
                () -> proofs.confirmEmail(SID, user, cur.attemptId(), sent.get(0)[2]));
        assertTrue(provenMap.isEmpty());
        assertNull(data.get(cur.attemptId()).authenticatedAt());
    }

    @Test void aProofForGoogleCannotAuthorizeAddingTheEmailMethod() {
        var cur = verifiedCurrentEmail();
        var neu = newGoogleAttempt();
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.INSTITUTIONAL_EMAIL,
                cur.attemptId(), neu.attemptId(), null));
    }

    @Test void googleIdentityOwnedByAnotherAccountIsNeverReassigned() {
        linkEmailIdentity();
        var u = User.create(stranger, new Email("beto@tec.ac.cr"), new ProfileDetails("beto", "Beto", ""),
                URI.create("about:blank"), START);
        store.insert(u);
        ids.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, stranger, new Email("beto@tec.ac.cr")));
        var cur = currentEmailAttempt();
        var neu = newGoogleAttempt();
        verifyByEmail(cur);
        assertEquals(UserDomainException.Reason.IDENTITY_CONFLICT, assertThrows(UserDomainException.class,
                () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), "nonce:" + neu.nonce())).reason());
        assertEquals(stranger, ids.findByKey(googleKey).orElseThrow().userId());
        assertFalse(data.get(cur.attemptId()).consumed());
        assertFalse(data.get(neu.attemptId()).consumed());
    }

    @Test void googleCurrentProofRequiresAnIdentityOwnedByTheSameAccount() {
        var a = proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, RecentProofService.LINK_CURRENT, "INSTITUTIONAL_EMAIL");
        assertThrows(SessionRejectedException.class, () -> proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce()));
        ids.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, user, email));
        googleSubject = "other-sub";
        assertThrows(SessionRejectedException.class, () -> proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce()));
        googleSubject = "g-sub";
        proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce());
        assertNotNull(data.get(a.attemptId()).authenticatedAt());
        // a "new identity" attempt can never be satisfied by an existing identity
        var neu = newGoogleAttempt();
        assertThrows(SessionRejectedException.class, () -> proofs.proveGoogle(SID, user, neu.attemptId(), "nonce:" + neu.nonce()));
    }

    @Test void addingEmailNeedsGoogleCurrentProofAndAnEmailLinkToTheExactAddress() {
        ids.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, user, email));
        var cur = proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, RecentProofService.LINK_CURRENT, "INSTITUTIONAL_EMAIL");
        var neu = proofs.begin(SID, user, RecentAttempt.Method.INSTITUTIONAL_EMAIL, RecentProofService.LINK_NEW,
                "INSTITUTIONAL_EMAIL:" + email.normalized());
        proofs.proveGoogle(SID, user, cur.attemptId(), "nonce:" + cur.nonce());
        // profile.verificado alone is not proof of control of the new method
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.INSTITUTIONAL_EMAIL,
                cur.attemptId(), neu.attemptId(), null));
        assertFalse(data.get(cur.attemptId()).consumed());
        verifyByEmail(neu);
        var linked = methods.link(SID, user, IdentityProvider.INSTITUTIONAL_EMAIL, cur.attemptId(), neu.attemptId(), null);
        assertEquals(IdentityProvider.INSTITUTIONAL_EMAIL, linked.provider());
        assertTrue(data.get(cur.attemptId()).consumed());
        assertTrue(data.get(neu.attemptId()).consumed());
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.INSTITUTIONAL_EMAIL,
                cur.attemptId(), neu.attemptId(), null));
        assertEquals(2, methods.list(SID, user).size());
    }

    private RecentAttempt verifiedCurrentEmail() {
        linkEmailIdentity();
        var cur = currentEmailAttempt();
        verifyByEmail(cur);
        return cur;
    }

    private void expectLinkRejectedAndNothingChanged(RecentAttempt cur, RecentAttempt neu, Class<? extends Throwable> type) {
        assertThrows(type, () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), "nonce:" + neu.nonce()));
        assertFalse(data.get(cur.attemptId()).consumed());
        assertFalse(data.get(neu.attemptId()).consumed());
        assertNull(data.get(neu.attemptId()).authenticatedAt());
        assertTrue(ids.findByKey(googleKey).isEmpty());
    }

    @Test void provenCurrentIdentityMustStillBeActiveForTheSameOwner() {
        var cur = verifiedCurrentEmail();
        var neu = newGoogleAttempt();
        var key = new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized());
        var held = ids.findByKey(key).orElseThrow();
        ids.replaceStatus(held, IdentityLinkStatus.DISABLED);
        expectLinkRejectedAndNothingChanged(cur, neu, SessionRejectedException.class);
        ids.replaceStatus(ids.findByKey(key).orElseThrow(), IdentityLinkStatus.ACTIVE);
        idMap.put(key, new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, key, stranger, email));
        expectLinkRejectedAndNothingChanged(cur, neu, SessionRejectedException.class);
        idMap.remove(key);
        expectLinkRejectedAndNothingChanged(cur, neu, SessionRejectedException.class);
    }

    @Test void theProofRecordsTheExactIdentityNotJustTheProvider() {
        var cur = verifiedCurrentEmail();
        assertEquals(new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized()), provenMap.get(cur.attemptId()));
        // another active email identity of the same user cannot stand in for the proven one
        var key = provenMap.get(cur.attemptId());
        var other = new ExternalIdentityKey(EmailLoginService.ISSUER, "other@tec.ac.cr");
        idMap.remove(key);
        idMap.put(other, new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, other, user, email));
        expectLinkRejectedAndNothingChanged(cur, newGoogleAttempt(), SessionRejectedException.class);
    }

    @Test void proofThatExpiresBeforeTheInsertRollsBothConsumptionsAndTheInsertBack() {
        var cur = verifiedCurrentEmail();
        var neu = newGoogleAttempt();
        onInsert = () -> time.set(START.plusSeconds(300));
        expectLinkRejectedAndNothingChanged(cur, neu, SessionRejectedException.class);
    }

    @Test void bothProofsAreAcceptedUpToTheLastMillisecondBeforeTheWindowCloses() {
        var cur = verifiedCurrentEmail();
        var neu = newGoogleAttempt();
        onInsert = () -> time.set(START.plusMillis(299_999));
        assertNotNull(methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), "nonce:" + neu.nonce()));
        assertTrue(data.get(cur.attemptId()).consumed());
        assertTrue(data.get(neu.attemptId()).consumed());
    }

    @Test void failedInsertRollsBackBothConsumptions() {
        var cur = verifiedCurrentEmail();
        var neu = newGoogleAttempt();
        var victim = User.create(stranger, new Email("beto@tec.ac.cr"), new ProfileDetails("beto", "Beto", ""),
                URI.create("about:blank"), START);
        store.insert(victim);
        ids.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, stranger, new Email("beto@tec.ac.cr")));
        assertThrows(UserDomainException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, cur.attemptId(), neu.attemptId(), "nonce:" + neu.nonce()));
        assertFalse(data.get(cur.attemptId()).consumed());
        assertFalse(data.get(neu.attemptId()).consumed());
        assertEquals(stranger, ids.findByKey(googleKey).orElseThrow().userId());
    }

    @Test void presentingTheSameGoogleProofTwiceIsAConsumedErrorAndKeepsTheOriginalProof() {
        ids.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, user, email));
        var a = proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, RecentProofService.LINK_CURRENT, "INSTITUTIONAL_EMAIL");
        proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce());
        var firstEvent = data.get(a.attemptId()).authenticatedAt();
        var firstIdentity = provenMap.get(a.attemptId());
        time.set(START.plusSeconds(5));
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce())).reason());
        assertEquals(firstEvent, data.get(a.attemptId()).authenticatedAt());
        assertEquals(firstIdentity, provenMap.get(a.attemptId()));
        assertFalse(data.get(a.attemptId()).consumed());
    }

    @Test void requestingTheEmailProofTwiceSendsOneLinkAndKeepsTheFirstChallenge() {
        linkEmailIdentity();
        var a = currentEmailAttempt();
        proofs.requestEmail(SID, user, a.attemptId());
        String verifier = challenges.get(a.attemptId())[0];
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> proofs.requestEmail(SID, user, a.attemptId())).reason());
        assertEquals(1, sent.size());
        assertEquals(verifier, challenges.get(a.attemptId())[0]);
        proofs.confirmEmail(SID, user, a.attemptId(), sent.get(0)[2]);
        // a verified attempt can no longer be asked for another link
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED, assertThrows(SessionRejectedException.class,
                () -> proofs.requestEmail(SID, user, a.attemptId())).reason());
        assertEquals(1, sent.size());
    }
}
