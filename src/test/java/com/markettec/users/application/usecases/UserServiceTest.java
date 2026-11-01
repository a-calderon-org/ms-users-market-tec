package com.markettec.users.application.usecases;

import com.markettec.users.adapters.out.UuidUserIdGenerator;
import com.markettec.users.adapters.out.memory.InMemoryUserStore;
import com.markettec.users.application.ports.in.*;
import com.markettec.users.application.ports.out.*;
import com.markettec.users.domain.model.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.markettec.users.domain.model.UserDomainException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class UserServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);
    private static final ProfileDetails DETAILS = new ProfileDetails("ana", "Ana", "López Mora");
    private static final URI PHOTO = URI.create("https://example.invalid/avatar.png");
    private static final Email EMAIL = new Email("ana@estudiantec.cr");
    private final InMemoryUserStore store = new InMemoryUserStore();
    private final Set<Grant> grants = ConcurrentHashMap.newKeySet();
    private final AtomicInteger generatedIds = new AtomicInteger();
    private final UserIdGenerator ids = () -> {
        generatedIds.incrementAndGet();
        return new UuidUserIdGenerator().nextId();
    };
    private final IdentityLinkAuthorization authorization = (principal, target, identity) -> {
        if (!grants.contains(new Grant(principal.userId(), target, identity.provider(), identity.key()))) {
            throw new UserDomainException(UNAUTHORIZED);
        }
    };
    private final UserService service = service(store, ids, authorization);

    private record Grant(UserId principal, UserId target, IdentityProvider provider, ExternalIdentityKey key) { }

    private UserService service(ExternalIdentityRepository identities, UserIdGenerator generator,
                                IdentityLinkAuthorization policy) {
        return new UserService(store, identities, generator, store, policy, CLOCK);
    }

    private VerifiedExternalIdentity google(String subject) {
        return new VerifiedExternalIdentity(IdentityProvider.GOOGLE,
                new ExternalIdentityKey("https://accounts.google.com", subject), EMAIL);
    }

    private User create() { return service.createUser(EMAIL, DETAILS, PHOTO); }
    private AuthenticatedPrincipal principal(User user) { return user::userId; }
    private void grant(User user, VerifiedExternalIdentity identity) {
        grants.add(new Grant(user.userId(), user.userId(), identity.provider(), identity.key()));
    }

    private void fails(UserDomainException.Reason reason, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason, assertThrows(UserDomainException.class, operation).reason());
    }

    @Test
    void provisionsInternalUserWithoutExternalIdentityOrVerifiedEmail() {
        User user = create();
        assertEquals(user, store.findById(user.userId()).orElseThrow());
        assertFalse(user.profile().verificado());
        assertEquals(1, generatedIds.get());
    }

    @Test
    void googleRegistrationIsVerifiedAndIndependentOfExternalSubject() {
        VerifiedExternalIdentity google = google("123456789012345678901");
        User user = service.registerVerifiedIdentity(google, DETAILS, PHOTO);
        assertNotEquals(google.key().subject(), user.userId().value());
        assertFalse(user.userId().value().contains("ana"));
        assertTrue(user.profile().verificado());
        ExternalIdentity identity = store.findByKey(google.key()).orElseThrow();
        assertEquals(user.userId(), identity.userId());
        assertEquals(EMAIL.address(), identity.verifiedEmail().address());
    }

    @Test
    void registrationRetryReturnsOriginalUserWithoutOverwritingProfileOrEvidence() {
        var identity = google("google-1");
        User first = service.registerVerifiedIdentity(identity, DETAILS, PHOTO);
        var retryEvidence = new VerifiedExternalIdentity(identity.provider(), identity.key(),
                new Email("changed@estudiantec.cr"));
        User retry = service.registerVerifiedIdentity(retryEvidence,
                new ProfileDetails("otro", "Otra", "Persona"), URI.create("https://example.invalid/other.png"));
        assertEquals(first, retry);
        assertEquals(1, generatedIds.get());
        assertEquals(EMAIL, store.findByKey(identity.key()).orElseThrow().verifiedEmail());
    }

    @Test
    void neverMergesAccountsByEmailOrSubjectAcrossIssuers() {
        User internal = create();
        User google = service.registerVerifiedIdentity(google("same-subject"), DETAILS, PHOTO);
        var mailIdentity = new VerifiedExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                new ExternalIdentityKey("markettec:institutional-email", "same-subject"), EMAIL);
        User mail = service.registerVerifiedIdentity(mailIdentity, DETAILS, PHOTO);
        assertEquals(3, Set.of(internal.userId(), google.userId(), mail.userId()).size());
    }

    @Test
    void sameIssuerAndSubjectCannotBeReusedUnderAnotherProvider() {
        var original = google("google-1");
        service.registerVerifiedIdentity(original, DETAILS, PHOTO);
        var conflict = new VerifiedExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL, original.key(), EMAIL);
        fails(IDENTITY_CONFLICT, () -> service.registerVerifiedIdentity(conflict, DETAILS, PHOTO));
        assertEquals(1, generatedIds.get());
    }

    @Test
    void linksTwoVerifiedMethodsToOneInternalAccountUnderExplicitAuthorization() {
        User user = create();
        var google = google("google-1");
        var email = new VerifiedExternalIdentity(IdentityProvider.INSTITUTIONAL_EMAIL,
                new ExternalIdentityKey("markettec:institutional-email", "mail-subject"), EMAIL);
        for (var identity : List.of(google, email)) {
            grant(user, identity);
            assertEquals(user.userId(), service.linkExternalIdentity(principal(user), user.userId(), identity).userId());
            assertEquals(user.userId(), service.registerVerifiedIdentity(identity, DETAILS, PHOTO).userId());
        }
        assertTrue(service.getOwnProfile(principal(user), user.userId()).verificado());
        assertEquals(1, generatedIds.get());
    }

    @Test
    void authenticatedOwnershipAloneDoesNotAuthorizeLinking() {
        User user = create();
        var identity = google("google-1");
        fails(UNAUTHORIZED, () -> service.linkExternalIdentity(principal(user), user.userId(), identity));
        assertTrue(store.findByKey(identity.key()).isEmpty());
        assertFalse(store.findById(user.userId()).orElseThrow().profile().verificado());
    }

    @Test
    void authorizationForOneIdentityDoesNotAuthorizeAnother() {
        User user = create();
        grant(user, google("allowed"));
        fails(UNAUTHORIZED, () -> service.linkExternalIdentity(principal(user), user.userId(), google("other")));
    }

    @Test
    void linkingRetryIsIdempotentAndStillRequiresAuthorization() {
        User user = create();
        var identity = google("google-1");
        grant(user, identity);
        ExternalIdentity first = service.linkExternalIdentity(principal(user), user.userId(), identity);
        var differentEmail = new VerifiedExternalIdentity(identity.provider(), identity.key(),
                new Email("other@estudiantec.cr"));
        assertEquals(first, service.linkExternalIdentity(principal(user), user.userId(), differentEmail));
        assertEquals(EMAIL, store.findById(user.userId()).orElseThrow().profile().correo());
        grants.clear();
        fails(UNAUTHORIZED, () -> service.linkExternalIdentity(principal(user), user.userId(), identity));
    }

    @Test
    void cannotReassignAnIdentityEvenWithAuthorizationForBothAccounts() {
        User first = create();
        User other = create();
        var identity = google("google-1");
        grant(first, identity);
        grant(other, identity);
        service.linkExternalIdentity(principal(first), first.userId(), identity);
        fails(IDENTITY_CONFLICT, () -> service.linkExternalIdentity(principal(other), other.userId(), identity));
        assertEquals(first.userId(), store.findByKey(identity.key()).orElseThrow().userId());
        assertFalse(store.findById(other.userId()).orElseThrow().profile().verificado());
    }

    @Test
    void linkingAnotherVerifiedEmailPreservesProfileAddressAndVerificationState() {
        User user = create();
        var external = new VerifiedExternalIdentity(IdentityProvider.GOOGLE, google("google-1").key(),
                new Email("other@estudiantec.cr"));
        grant(user, external);
        service.linkExternalIdentity(principal(user), user.userId(), external);
        UserProfile profile = service.getOwnProfile(principal(user), user.userId());
        assertEquals(EMAIL, profile.correo());
        assertFalse(profile.verificado());
        assertEquals(external.email(), store.findByKey(external.key()).orElseThrow().verifiedEmail());
    }

    @Test
    void readsOnlyTheAuthenticatedOwnersProfile() {
        User user = create();
        assertEquals(user.profile(), service.getOwnProfile(principal(user), user.userId()));
        fails(UNAUTHORIZED, () -> service.getOwnProfile(principal(create()), user.userId()));
        fails(UNAUTHORIZED, () -> service.getOwnProfile(null, user.userId()));
        fails(UNAUTHORIZED, () -> service.getOwnProfile(() -> null, user.userId()));
        fails(UNAUTHORIZED, () -> service.getOwnProfile(principal(user), null));
    }

    @Test
    void rejectsCrossAccountUpdatesLinkingAndDeactivation() {
        User user = create();
        User other = create();
        grant(user, google("google-1"));
        fails(UNAUTHORIZED, () -> service.updateOwnProfile(principal(other), user.userId(), DETAILS));
        fails(UNAUTHORIZED, () -> service.linkExternalIdentity(principal(other), user.userId(), google("google-1")));
        fails(UNAUTHORIZED, () -> service.deactivateUser(principal(other), user.userId()));
        assertEquals(user, store.findById(user.userId()).orElseThrow());
    }

    @Test
    void reportsMissingAccountOnlyAfterOwnershipCheck() {
        UserId missing = ids.nextId();
        AuthenticatedPrincipal owner = () -> missing;
        fails(USER_NOT_FOUND, () -> service.getOwnProfile(owner, missing));
        fails(USER_NOT_FOUND, () -> service.updateOwnProfile(owner, missing, DETAILS));
        fails(USER_NOT_FOUND, () -> service.deactivateUser(owner, missing));
        fails(USER_NOT_FOUND, () -> service.linkExternalIdentity(owner, missing, google("google-1")));
        fails(UNAUTHORIZED, () -> service.getOwnProfile(principal(create()), missing));
    }

    @Test
    void updatesOnlyEditableProfileFields() {
        User user = create();
        ProfileDetails replacement = new ProfileDetails("ana.nueva", "Ana María", "López");
        UserService later = new UserService(store, store, ids, store, authorization,
                Clock.offset(CLOCK, java.time.Duration.ofSeconds(60)));
        UserProfile updated = later.updateOwnProfile(principal(user), user.userId(), replacement);
        assertEquals(replacement, updated.details());
        assertEquals(user.userId(), updated.userId());
        assertEquals(user.profile().correo(), updated.correo());
        assertEquals(PHOTO, updated.fotoPerfil());
        assertEquals(user.profile().fechaRegistro(), updated.fechaRegistro());
        assertEquals(CLOCK.instant().plusSeconds(60), updated.fechaActualizacion());
        assertFalse(updated.verificado());
        assertEquals(updated, later.getOwnProfile(principal(user), user.userId()));
        assertEquals(DETAILS, user.profile().details()); // Previously returned snapshots remain immutable.
    }

    @Test
    void deactivationBlocksAllActiveOperationsAndDoesNotCreateAnotherAccountOnRetry() {
        var identity = google("google-1");
        User user = service.registerVerifiedIdentity(identity, DETAILS, PHOTO);
        grant(user, identity);
        User inactive = service.deactivateUser(principal(user), user.userId());
        assertEquals(AccountStatus.DEACTIVATED, inactive.status());
        assertEquals(inactive, service.deactivateUser(principal(user), user.userId()));
        fails(USER_DEACTIVATED, () -> service.getOwnProfile(principal(user), user.userId()));
        fails(USER_DEACTIVATED, () -> service.updateOwnProfile(principal(user), user.userId(), DETAILS));
        fails(USER_DEACTIVATED, () -> service.linkExternalIdentity(principal(user), user.userId(), identity));
        fails(USER_DEACTIVATED, () -> service.registerVerifiedIdentity(identity, DETAILS, PHOTO));
        assertEquals(1, generatedIds.get());
        assertEquals(user.userId(), store.findByKey(identity.key()).orElseThrow().userId());
    }

    @Test
    void generatedIdCollisionNeverOverwritesAnExistingAccount() {
        User first = create();
        UserService collision = service(store, first::userId, authorization);
        fails(USER_ALREADY_EXISTS, () -> collision.createUser(new Email("other@estudiantec.cr"), DETAILS, PHOTO));
        assertEquals(first, store.findById(first.userId()).orElseThrow());
    }

    @Test
    void registrationRollsBackBothUserAndIdentityIfAdapterFailsAfterWriting() {
        List<UserId> attempted = new ArrayList<>();
        UserIdGenerator recordingIds = () -> {
            UserId id = ids.nextId();
            attempted.add(id);
            return id;
        };
        UserService failing = service(failingAfterIdentityInsert(), recordingIds, authorization);
        var identity = google("google-1");
        assertThrows(IllegalStateException.class, () -> failing.registerVerifiedIdentity(identity, DETAILS, PHOTO));
        assertTrue(store.findById(attempted.getFirst()).isEmpty());
        assertTrue(store.findByKey(identity.key()).isEmpty());
        assertNotNull(service.registerVerifiedIdentity(identity, DETAILS, PHOTO));
    }

    @Test
    void failedLinkRollsBackTheMappingAndProfileTogether() {
        User user = create();
        var identity = google("google-1");
        grant(user, identity);
        UserService failing = service(failingAfterIdentityInsert(), ids, authorization);
        assertThrows(IllegalStateException.class,
                () -> failing.linkExternalIdentity(principal(user), user.userId(), identity));
        assertTrue(store.findByKey(identity.key()).isEmpty());
        assertEquals(user, store.findById(user.userId()).orElseThrow());
    }

    private ExternalIdentityRepository failingAfterIdentityInsert() {
        return new ExternalIdentityRepository() {
            public Optional<ExternalIdentity> findByKey(ExternalIdentityKey key) { return store.findByKey(key); }
            public ExternalIdentity insertOrGet(ExternalIdentity identity) {
                store.insertOrGet(identity);
                throw new IllegalStateException("Simulated adapter failure");
            }
        };
    }

    @Test
    void concurrentRegistrationsAcrossServiceInstancesResolveToOneAccount() throws Exception {
        var identity = google("same-subject");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<User>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                UserService instance = service(store, ids, authorization);
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return instance.registerVerifiedIdentity(identity, DETAILS, PHOTO);
                }));
            }
            start.countDown();
            Set<UserId> registered = new HashSet<>();
            for (Future<User> result : results) {
                registered.add(result.get(10, TimeUnit.SECONDS).userId());
            }
            assertEquals(1, registered.size());
            assertEquals(1, generatedIds.get());
            assertEquals(registered.iterator().next(), store.findByKey(identity.key()).orElseThrow().userId());
        }
    }

    @Test
    void concurrentLinkAttemptsHaveOneOwnerAndOneConflict() throws Exception {
        User first = create();
        User other = create();
        var identity = google("same-subject");
        grant(first, identity);
        grant(other, identity);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> results = new ArrayList<>();
            for (User owner : List.of(first, other)) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        return service(store, ids, authorization)
                                .linkExternalIdentity(principal(owner), owner.userId(), identity);
                    } catch (UserDomainException conflict) {
                        return conflict.reason();
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(ExternalIdentity.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(IDENTITY_CONFLICT::equals).count());
            UserId winner = store.findByKey(identity.key()).orElseThrow().userId();
            User loser = winner.equals(first.userId()) ? other : first;
            assertEquals(loser, store.findById(loser.userId()).orElseThrow());
        }
    }
}
