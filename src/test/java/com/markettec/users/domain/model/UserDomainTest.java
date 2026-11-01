package com.markettec.users.domain.model;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class UserDomainTest {
    private final UserId id = new UuidUserIdGenerator().nextId();
    private final Instant now = Instant.parse("2026-10-31T12:00:00Z");
    private final ProfileDetails details = new ProfileDetails("ana", "Ana", "López");
    private final Email email = new Email("Ana+market@Estudiantec.CR");
    private final URI photo = URI.create("https://example.invalid/ana.png");

    @Test
    void createsAnActiveUnverifiedInternalUserWithOwnedProfile() {
        User user = User.create(id, email, details, photo, now);
        assertEquals(AccountStatus.ACTIVE, user.status());
        assertEquals(id, user.profile().userId());
        assertEquals(details, user.profile().details());
        assertEquals(now, user.profile().fechaRegistro());
        assertEquals(now, user.profile().fechaActualizacion());
        assertFalse(user.profile().verificado());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "USR-001", "ana@estudiantec.cr", "123456789012345678901",
            "usr_00000000-0000-0000-0000-000000000000", "usr_00000000-0000-4000-8000-000000000000 ",
            "usr_AAAAAAAA-aaaa-4aaa-8aaa-aaaaaaaaaaaa"})
    void rejectsInvalidOrPersonalIdentifiers(String value) {
        assertThrows(IllegalArgumentException.class, () -> new UserId(value));
    }

    @Test
    void uuidGeneratorProducesDistinctOpaqueIds() {
        var generator = new UuidUserIdGenerator();
        assertNotEquals(generator.nextId(), generator.nextId());
        assertEquals(40, generator.nextId().value().length());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "@estudiantec.cr", "a@", "a@@b.cr", "a b@b.cr", "a@localhost",
            ".a@b.cr", "a.@b.cr", "a..b@b.cr", "a@-b.cr", "a@b-.cr", "a@b..cr", "a@b.cr.",
            " a@b.cr", "a@b.cr ", "a@b.cr\n", "á@b.cr", "a@b_.cr", "a@b.cr/path"})
    void rejectsInvalidEmails(String value) {
        assertThrows(IllegalArgumentException.class, () -> new Email(value));
    }

    @Test
    void rejectsOverlongEmailParts() {
        assertThrows(IllegalArgumentException.class, () -> new Email("a".repeat(65) + "@b.cr"));
        assertThrows(IllegalArgumentException.class, () -> new Email("a@" + "b".repeat(64) + ".cr"));
        assertThrows(IllegalArgumentException.class, () -> new Email("a@" + "b.".repeat(130) + "cr"));
    }

    @Test
    void preservesVerifiedSpellingAndOnlyNormalizesDomainCase() {
        assertEquals("Ana+market@Estudiantec.CR", email.address());
        assertEquals("Ana+market@estudiantec.cr", email.normalized());
        assertEquals(email, new Email("Ana+market@estudiantec.cr"));
        assertEquals(email.hashCode(), new Email("Ana+market@estudiantec.cr").hashCode());
        assertNotEquals(email, new Email("ana+market@estudiantec.cr"));
        assertNotEquals(email, new Email("Ana@estudiantec.cr"));
        assertNotEquals(new Email("a.na@example.com"), new Email("ana@example.com"));
    }

    @Test
    void emailNormalizationIsIndependentOfDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("Ana@institution.cr", new Email("Ana@INSTITUTION.CR").normalized());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void profileFieldBoundsFollowExistingContract() {
        assertDoesNotThrow(() -> new ProfileDetails("a".repeat(80), "a".repeat(100), "a".repeat(160)));
        assertDoesNotThrow(() -> new ProfileDetails("ana", "Ana", ""));
        assertDoesNotThrow(() -> new ProfileDetails("😀".repeat(80), "Ana", ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDetails("a".repeat(81), "Ana", ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDetails("ana", "a".repeat(101), ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDetails("ana", "Ana", "a".repeat(161)));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDetails(" ", "Ana", ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDetails("ana", null, ""));
        assertThrows(IllegalArgumentException.class, () -> new ProfileDetails("ana", "Ana", "x\n"));
    }

    @Test
    void rejectsMismatchedProfileOwnershipAndInvalidTimestamps() {
        User user = User.create(id, email, details, photo, now);
        assertThrows(IllegalArgumentException.class,
                () -> new User(new UuidUserIdGenerator().nextId(), AccountStatus.ACTIVE, user.profile()));
        assertThrows(IllegalArgumentException.class,
                () -> User.create(id, email, details, URI.create("relative.png"), now));
        assertThrows(IllegalArgumentException.class,
                () -> User.create(id, email, details, photo, Instant.EPOCH.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> user.updateProfile(details, now.minusSeconds(1)));
        User later = user.updateProfile(details, now.plusSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> later.updateProfile(details, now.plusSeconds(5)));
    }

    @Test
    void verificationAppliesOnlyToTheProfileAddressAndPreservesVerifiedSpelling() {
        User user = User.create(id, new Email("Ana+market@ESTUDIANTEC.CR"), details, photo, now);
        assertFalse(user.verifyProfileEmail(new Email("different@estudiantec.cr")).profile().verificado());
        User verified = user.verifyProfileEmail(email);
        assertTrue(verified.profile().verificado());
        assertEquals(email.address(), verified.profile().correo().address());
    }

    @Test
    void deactivationIsIdempotentAndBlocksProfileChanges() {
        User inactive = User.create(id, email, details, photo, now).deactivate();
        assertEquals(inactive, inactive.deactivate());
        assertThrows(UserDomainException.class, () -> inactive.updateProfile(details, now));
        assertThrows(UserDomainException.class, () -> inactive.verifyProfileEmail(email));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " issuer", "subject ", "a\nb"})
    void rejectsInvalidExternalIdentityKeys(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentityKey(value, "subject"));
        assertThrows(IllegalArgumentException.class, () -> new ExternalIdentityKey("issuer", value));
    }

    @Test
    void externalKeysPreserveCaseAndIssuerNamespaces() {
        ExternalIdentityKey key = new ExternalIdentityKey("https://accounts.google.com", "Subject");
        assertNotEquals(key, new ExternalIdentityKey("https://accounts.google.com", "subject"));
        assertNotEquals(key, new ExternalIdentityKey("institutional-mail", "Subject"));
    }
}
