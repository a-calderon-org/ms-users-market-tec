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
            if (challenges.putIfAbsent(id, new String[]{verifier, "open"}) != null) throw new IllegalStateException("dup");
        }
        public Optional<String> loadOpenLocked(String id) {
            var c = challenges.get(id);
            return c != null && c[1].equals("open") ? Optional.of(c[0]) : Optional.empty();
        }
        public void markConsumed(String id) { challenges.get(id)[1] = "used"; }
    };
    private final ExternalIdentityKey googleKey = new ExternalIdentityKey("https://accounts.google.com", "g-sub");
    private String googleSubject = "g-sub";
    private final GoogleIdentityVerifier google = (token, ctx) -> {
        if (!("nonce:" + ctx.expectedNonce()).equals(token)) throw new IllegalArgumentException("bad token");
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
            try { return work.get(); }
            catch (RuntimeException failure) { data.clear(); data.putAll(before); throw failure; }
        });
        when(sessions.current(SID)).thenReturn(Optional.of(Session.start(SID, user, START)));
        var u = User.create(user, email, new ProfileDetails("ana", "Ana", ""), URI.create("about:blank"), START);
        store.insert(u);
        store.replace(u, u.verifyProfileEmail(email));
        store.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                new ExternalIdentityKey(EmailLoginService.ISSUER, email.normalized()), user, email));
        recent = new RecentAuthenticationAuthority(sessions, attempts, clock, random);
        proofs = new RecentProofService(sessions, recent, attempts, repo, store, store, google, "client-id",
                (to, id, secret) -> sent.add(new String[]{to.address(), id, secret}), clock, random);
        methods = new AuthMethodsService(sessions, recent, store, store, google, "client-id");
    }

    private RecentAttempt emailLinkAttempt() {
        return proofs.begin(SID, user, RecentAttempt.Method.INSTITUTIONAL_EMAIL, "LINK_IDENTITY", "GOOGLE");
    }

    private void verifyByEmail(RecentAttempt a) {
        proofs.requestEmail(SID, user, a.attemptId());
        var link = sent.get(sent.size() - 1);
        assertEquals(email.address(), link[0]);
        proofs.confirmEmail(SID, user, a.attemptId(), link[2]);
    }

    @Test void onlyAllowlistedOperationMethodTargetCombinationsBegin() {
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user,
                RecentAttempt.Method.INSTITUTIONAL_EMAIL, "DELETE_EVERYTHING", "GOOGLE"));
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user,
                RecentAttempt.Method.INSTITUTIONAL_EMAIL, "DEACTIVATE_USER", stranger.value()));
        assertThrows(SessionRejectedException.class, () -> proofs.begin(SID, user,
                RecentAttempt.Method.PASSKEY, "LINK_IDENTITY", "GOOGLE"));
        assertThrows(SessionRejectedException.class, () -> proofs.begin("f".repeat(32), user,
                RecentAttempt.Method.INSTITUTIONAL_EMAIL, "LINK_IDENTITY", "GOOGLE"));
        assertNotNull(emailLinkAttempt());
    }

    @Test void emailLinkProvesControlOnceAndOnlyForTheBoundAttempt() {
        var a = emailLinkAttempt();
        var other = emailLinkAttempt();
        proofs.requestEmail(SID, user, a.attemptId());
        var link = sent.get(0);
        assertNotEquals(link[2], challenges.get(a.attemptId())[0]);
        assertThrows(IllegalStateException.class, () -> proofs.requestEmail(SID, user, a.attemptId()));
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, other.attemptId(), link[2]));
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, a.attemptId(), "f".repeat(64)));
        proofs.confirmEmail(SID, user, a.attemptId(), link[2]);
        assertNotNull(data.get(a.attemptId()).authenticatedAt());
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, a.attemptId(), link[2]));
    }

    @Test void expiredEmailLinkIsRejected() {
        var a = emailLinkAttempt();
        proofs.requestEmail(SID, user, a.attemptId());
        time.set(START.plusSeconds(299));
        assertThrows(SessionRejectedException.class, () -> proofs.confirmEmail(SID, user, a.attemptId(), sent.get(0)[2]));
    }

    @Test void linkingGoogleNeedsVerifiedProofAndNonceBoundCredentialAndConsumesTheAttempt() {
        var a = emailLinkAttempt();
        String token = "nonce:" + a.nonce();
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, a.attemptId(), token));
        verifyByEmail(a);
        assertThrows(IllegalArgumentException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, a.attemptId(), "nonce:other"));
        var linked = methods.link(SID, user, IdentityProvider.GOOGLE, a.attemptId(), token);
        assertEquals(user, linked.userId());
        assertEquals(user, store.findByKey(googleKey).orElseThrow().userId());
        assertTrue(data.get(a.attemptId()).consumed());
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.GOOGLE, a.attemptId(), token));
        assertEquals(2, methods.list(SID, user).size());
    }

    @Test void googleIdentityOwnedByAnotherAccountIsNeverReassigned() {
        var u = User.create(stranger, new Email("beto@tec.ac.cr"), new ProfileDetails("beto", "Beto", ""),
                URI.create("about:blank"), START);
        store.insert(u);
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, stranger, new Email("beto@tec.ac.cr")));
        var a = emailLinkAttempt();
        verifyByEmail(a);
        assertEquals(UserDomainException.Reason.IDENTITY_CONFLICT, assertThrows(UserDomainException.class,
                () -> methods.link(SID, user, IdentityProvider.GOOGLE, a.attemptId(), "nonce:" + a.nonce())).reason());
        assertEquals(stranger, store.findByKey(googleKey).orElseThrow().userId());
        assertFalse(data.get(a.attemptId()).consumed());
    }

    @Test void googleProofRequiresAnIdentityOwnedByTheSameAccount() {
        var a = proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, "LINK_IDENTITY", "INSTITUTIONAL_EMAIL");
        assertThrows(SessionRejectedException.class, () -> proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce()));
        store.insertOrGet(new ExternalIdentity(IdentityProvider.GOOGLE, googleKey, user, email));
        googleSubject = "other-sub";
        assertThrows(SessionRejectedException.class, () -> proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce()));
        googleSubject = "g-sub";
        proofs.proveGoogle(SID, user, a.attemptId(), "nonce:" + a.nonce());
        assertNotNull(data.get(a.attemptId()).authenticatedAt());
    }

    @Test void institutionalEmailLinkNeedsGoogleProofAndIsIdempotentForTheOwner() {
        var a = proofs.begin(SID, user, RecentAttempt.Method.GOOGLE, "LINK_IDENTITY", "INSTITUTIONAL_EMAIL");
        assertThrows(SessionRejectedException.class, () -> methods.link(SID, user, IdentityProvider.INSTITUTIONAL_EMAIL, a.attemptId(), null));
        assertEquals(1, methods.list(SID, user).size());
    }
}
