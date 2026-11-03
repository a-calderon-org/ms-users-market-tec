package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;
import com.markettec.users.domain.model.UserId;
import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstitutionalEmailProofTest {
    private static final Instant START = Instant.parse("2026-11-03T12:00:00Z");
    private static final UserId USER = new UserId("usr_123e4567-e89b-42d3-a456-426614174000");
    private static final Email RECIPIENT = new Email("Ana+tec@Estudiantec.CR");
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecentAttempt attempt(UserId user, String sid, String operation, String target) {
        return new RecentAttempt("0123456789abcdef0123456789abcdef", user, sid,
                RecentAttempt.Method.INSTITUTIONAL_EMAIL, operation, target, "a".repeat(64),
                START, START.plusSeconds(300), null, false, 0);
    }

    private RecentAttempt attempt() {
        return attempt(USER, "fedcba9876543210fedcba9876543210", "DEACTIVATE_USER", USER.value());
    }

    @Test void onlyExactInstitutionalDomainsAreEligible() {
        for (String address : new String[]{"a@estudiantec.cr", "a@ITCR.AC.CR", "a@tec.ac.cr"})
            assertTrue(InstitutionalEmailProof.eligible(new Email(address)));
        for (String address : new String[]{"a@nottec.ac.cr", "a@tec.ac.cr.example", "a@mail.itcr.ac.cr"})
            assertFalse(InstitutionalEmailProof.eligible(new Email(address)));
        assertFalse(InstitutionalEmailProof.eligible(null));
        assertThrows(SessionRejectedException.class, () -> InstitutionalEmailProof.issue(
                new Email("a@example.com"), attempt(), START, RANDOM));
    }

    @Test void issueCreatesUniqueSecretAndVerifierWithoutPrintingTheSecret() {
        var first = InstitutionalEmailProof.issue(RECIPIENT, attempt(), START, RANDOM);
        var second = InstitutionalEmailProof.issue(RECIPIENT, attempt(), START, RANDOM);
        assertNotEquals(first.secret(), second.secret());
        assertNotEquals(first.verifier(), second.verifier());
        assertEquals(64, first.secret().length());
        assertEquals(64, first.verifier().length());
        assertFalse(first.toString().contains(first.secret()));
        assertTrue(InstitutionalEmailProof.matches(RECIPIENT, attempt(), first.verifier(),
                first.secret(), START));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt(), first.verifier(),
                second.secret(), START));
    }

    @Test void proofIsBoundToExactRecipientAccountSessionOperationAndTarget() {
        var proof = InstitutionalEmailProof.issue(RECIPIENT, attempt(), START, RANDOM);
        String sid = "fedcba9876543210fedcba9876543210";
        assertFalse(InstitutionalEmailProof.matches(new Email("ana+tec@estudiantec.cr"),
                attempt(), proof.verifier(), proof.secret(), START));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt(
                new UserId("usr_223e4567-e89b-42d3-a456-426614174000"), sid,
                "DEACTIVATE_USER", USER.value()), proof.verifier(), proof.secret(), START));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt(USER,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "DEACTIVATE_USER", USER.value()),
                proof.verifier(), proof.secret(), START));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt(USER, sid,
                "LINK_IDENTITY", USER.value()), proof.verifier(), proof.secret(), START));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt(USER, sid,
                "DEACTIVATE_USER", "different"), proof.verifier(), proof.secret(), START));
    }

    @Test void expiryIsStrictlyUnderFiveMinutesAndVerifiedAttemptCannotBeReused() {
        var attempt = attempt();
        var proof = InstitutionalEmailProof.issue(RECIPIENT, attempt, START, RANDOM);
        assertTrue(InstitutionalEmailProof.matches(RECIPIENT, attempt, proof.verifier(),
                proof.secret(), START.plusSeconds(298)));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt, proof.verifier(),
                proof.secret(), START.plusSeconds(299)));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt, proof.verifier(),
                proof.secret(), START.minusMillis(1)));
        assertFalse(InstitutionalEmailProof.matches(RECIPIENT, attempt.verifiedAt(START),
                proof.verifier(), proof.secret(), START));
        assertThrows(SessionRejectedException.class, () -> InstitutionalEmailProof.issue(
                RECIPIENT, attempt, START.plusSeconds(299), RANDOM));
    }
}
