package com.markettec.users.application.session;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.domain.model.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailLoginServiceTest {
    private static final Instant START = Instant.parse("2026-11-03T12:00:00Z");
    private final AtomicReference<Instant> time = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private final InMemoryUserStore store = new InMemoryUserStore();
    private final Map<String, EmailLoginAttempt> rows = new LinkedHashMap<>();
    private final List<String[]> sent = new ArrayList<>();
    private boolean historical;
    private final SessionAuthority sessions = mock(SessionAuthority.class);
    private final EmailLoginAttemptRepository repo = new EmailLoginAttemptRepository() {
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
    private final PendingGoogleRegistrationRepository pending = new PendingGoogleRegistrationRepository() {
        public void insert(PendingGoogleRegistration p) { fail(); }
        public Optional<PendingGoogleRegistration> loadLocked(String id) { return Optional.empty(); }
        public void replace(PendingGoogleRegistration a, PendingGoogleRegistration b) { fail(); }
        public boolean historicalEmailExists(String email) { return historical; }
    };
    private UserId generated;
    private int issued;
    private final EmailLoginService service;

    EmailLoginServiceTest() {
        when(sessions.createAuthenticated(any())).thenReturn(new AuthenticatedSession("a".repeat(32)));
        doAnswer(call -> new SessionTokens("jwt", "b".repeat(64), "c".repeat(64), "a".repeat(32),
                generated.value(), START.plusSeconds(1800)))
                .when(sessions).issue(any(), any());
        service = new EmailLoginService(repo, store, store, store, pending,
                () -> { issued++; return generated = new UuidUserIdGenerator().nextId(); }, sessions, ignored -> "jwt",
                (to, id, secret) -> sent.add(new String[]{to.address(), id, secret}), clock, new SecureRandom());
    }

    private String browser;

    private String[] requestLink(String address) {
        browser = service.request(new Email(address));
        return sent.get(sent.size() - 1);
    }

    @Test void requestSendsOneLinkAndStoresOnlyAVerifier() {
        var link = requestLink("ana@estudiantec.cr");
        var row = rows.get(link[1]);
        assertNotEquals(link[2], row.verifier());
        assertEquals(64, link[2].length());
        assertEquals(START.plusSeconds(600), row.expiresAt());
        assertEquals(0, issued);
    }

    @Test void nonInstitutionalDomainsAreRejectedWithoutDelivery() {
        for (String a : List.of("a@gmail.com", "a@x.tec.ac.cr", "a@tec.ac.cr.evil"))
            assertThrows(SessionRejectedException.class, () -> service.request(new Email(a)));
        assertTrue(sent.isEmpty());
        assertTrue(rows.isEmpty());
    }

    @Test void requestsAreSilentlyThrottledPerAddress() {
        for (int i = 0; i < 6; i++) service.request(new Email("ana@tec.ac.cr"));
        assertEquals(3, sent.size());
        time.set(START.plus(Duration.ofMinutes(16)));
        service.request(new Email("ana@tec.ac.cr"));
        assertEquals(4, sent.size());
    }

    @Test void confirmCreatesVerifiedAccountOnceAndLinkIsSingleUse() {
        var link = requestLink("ana@tec.ac.cr");
        var result = service.confirm(link[1], browser, link[2]);
        assertEquals("jwt", result.jwt());
        var user = store.findById(generated).orElseThrow();
        assertTrue(user.profile().verificado());
        assertEquals(generated, store.findByKey(new ExternalIdentityKey(EmailLoginService.ISSUER, "ana@tec.ac.cr")).orElseThrow().userId());
        assertTrue(rows.get(link[1]).consumed());
        assertEquals(SessionRejectedException.Reason.ATTEMPT_CONSUMED,
                assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], browser, link[2])).reason());
        verify(sessions, times(1)).issue(any(), any());
    }

    @Test void existingLinkedAccountRequiresContinuityAndReceivesNoCredentials() {
        var first = requestLink("ana@tec.ac.cr");
        service.confirm(first[1], browser, first[2]);
        var second = requestLink("ana@tec.ac.cr");
        assertEquals(SessionRejectedException.Reason.CONTINUITY_REQUIRED,
                assertThrows(SessionRejectedException.class, () -> service.confirm(second[1], browser, second[2])).reason());
        assertEquals(1, issued);
        assertTrue(rows.get(second[1]).consumed());
        verify(sessions, times(1)).issue(any(), any());
        verify(sessions, times(1)).createAuthenticated(any());
    }

    @Test void foreignBrowsersChangeNothingAndCannotBlockTheLegitimateRedemption() {
        var link = requestLink("ana@tec.ac.cr");
        String mine = browser;
        var before = rows.get(link[1]);
        for (int i = 0; i < 10; i++)
            assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], "9".repeat(64), link[2]));
        assertEquals(before, rows.get(link[1]));
        assertEquals(0, rows.get(link[1]).failures());
        assertFalse(rows.get(link[1]).consumed());
        assertEquals(0, issued);
        assertNotNull(service.confirm(link[1], mine, link[2]));
    }

    @Test void foreignBrowserLearnsNothingAboutConsumedOrExpiredLinks() {
        var link = requestLink("ana@tec.ac.cr");
        service.confirm(link[1], browser, link[2]);
        var reason = assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], "9".repeat(64), link[2])).reason();
        assertEquals(SessionRejectedException.Reason.PROOF_INVALID, reason);
    }

    @Test void onlyTheRequestingBrowserCanBurnAttemptsWithAWrongToken() {
        var link = requestLink("ana@tec.ac.cr");
        assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], browser, "f".repeat(64)));
        assertEquals(1, rows.get(link[1]).failures());
    }

    private static String sha256Hex(String v) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(v.getBytes())); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void wrongSecretCountsFailuresThenLocksTheAttempt() {
        var link = requestLink("ana@tec.ac.cr");
        for (int i = 0; i < EmailLoginAttempt.MAX_FAILURES; i++)
            assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], browser, "f".repeat(64)));
        assertEquals(EmailLoginAttempt.MAX_FAILURES, rows.get(link[1]).failures());
        assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], browser, link[2]));
        assertEquals(0, issued);
    }

    @Test void expiredAttemptIsGoneAndCreatesNothing() {
        var link = requestLink("ana@tec.ac.cr");
        time.set(START.plusSeconds(600));
        assertEquals(SessionRejectedException.Reason.ATTEMPT_EXPIRED,
                assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], browser, link[2])).reason());
        assertEquals(0, issued);
    }

    @Test void secretForAnotherAttemptOrAddressIsRejected() {
        var a = requestLink("ana@tec.ac.cr");
        var b = requestLink("beto@tec.ac.cr");
        assertThrows(SessionRejectedException.class, () -> service.confirm(a[1], browser, b[2]));
        assertThrows(SessionRejectedException.class, () -> service.confirm("0".repeat(32), browser, a[2]));
        assertThrows(SessionRejectedException.class, () -> service.confirm("bad", browser, a[2]));
    }

    @Test void existingAccountWithTheSameAddressIsNeverMerged() {
        historical = true;
        var link = requestLink("ana@tec.ac.cr");
        assertEquals(SessionRejectedException.Reason.CONTINUITY_REQUIRED,
                assertThrows(SessionRejectedException.class, () -> service.confirm(link[1], browser, link[2])).reason());
        assertEquals(0, issued);
        verify(sessions, never()).issue(any(), any());
    }
}
