package com.markettec.users.adapters.out.memory;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.domain.model.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryUserStoreTest {
    private final InMemoryUserStore store = new InMemoryUserStore();
    private final ExternalIdentityKey key = new ExternalIdentityKey("https://accounts.google.com", "subject");
    private final Email email = new Email("ana@estudiantec.cr");

    private User user() {
        return User.create(new UuidUserIdGenerator().nextId(), email,
                new ProfileDetails("ana", "Ana", ""), URI.create("https://example.invalid/a.png"), Instant.EPOCH);
    }

    private ExternalIdentity identity(User user) {
        return new ExternalIdentity(IdentityProvider.GOOGLE, key, user.userId(), email);
    }

    @Test
    void enforcesUniquenessAtWriteWithoutAnyPriorLookup() {
        User first = user();
        User second = user();
        store.insert(first);
        store.insert(second);
        ExternalIdentity original = store.insertOrGet(identity(first));
        assertEquals(original, store.insertOrGet(identity(first)));
        assertEquals(IDENTITY_CONFLICT,
                assertThrows(UserDomainException.class, () -> store.insertOrGet(identity(second))).reason());
        assertEquals(IDENTITY_CONFLICT, assertThrows(UserDomainException.class,
                () -> store.insertOrGet(new ExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                        key, first.userId(), email))).reason());
        assertEquals(original, store.findByKey(key).orElseThrow());
    }

    @Test
    void preventsOrphanIdentitiesAndLinksToDeactivatedOwners() {
        User user = user();
        assertEquals(USER_NOT_FOUND,
                assertThrows(UserDomainException.class, () -> store.insertOrGet(identity(user))).reason());
        store.insert(user.deactivate());
        assertEquals(USER_DEACTIVATED,
                assertThrows(UserDomainException.class, () -> store.insertOrGet(identity(user))).reason());
        assertTrue(store.findByKey(key).isEmpty());
    }

    @Test
    void duplicateAccountInsertAndStaleAggregateCannotOverwriteCurrentState() {
        User user = user();
        store.insert(user);
        assertEquals(USER_ALREADY_EXISTS, assertThrows(UserDomainException.class, () -> store.insert(user)).reason());
        User inactive = user.deactivate();
        store.replace(user, inactive);
        assertEquals(CONCURRENT_MODIFICATION,
                assertThrows(UserDomainException.class, () -> store.replace(user, user)).reason());
        assertThrows(IllegalArgumentException.class, () -> store.replace(inactive, user()));
        assertEquals(inactive, store.findById(user.userId()).orElseThrow());
    }

    @Test
    void transactionRollsBackAllWritesOnFailure() {
        User user = user();
        assertThrows(IllegalStateException.class, () -> store.execute(() -> {
            store.insert(user);
            store.insertOrGet(identity(user));
            throw new IllegalStateException("rollback");
        }));
        assertTrue(store.findById(user.userId()).isEmpty());
        assertTrue(store.findByKey(key).isEmpty());
    }
}
