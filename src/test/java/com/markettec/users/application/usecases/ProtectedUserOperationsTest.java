package com.markettec.users.application.usecases;

import com.markettec.users.application.ports.in.*;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.*;
import com.markettec.users.domain.model.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectedUserOperationsTest {
    private static final UserId OWNER = new UserId("usr_123e4567-e89b-42d3-a456-426614174000");
    private static final UserId OTHER = new UserId("usr_223e4567-e89b-42d3-a456-426614174000");
    private static final String SID = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return time.get(); }
    };
    private final InternalTokenVerifier verifier = mock(InternalTokenVerifier.class);
    private final SessionAuthority authority = mock(SessionAuthority.class);
    private final UserUseCases users = mock(UserUseCases.class);
    private final ProtectedUserOperations boundary = new ProtectedUserOperations(verifier, authority, users, clock);

    private void valid() {
        when(verifier.verify("jwt")).thenReturn(new VerifiedInternalToken(OWNER, SID, SID, NOW, NOW.plusSeconds(100)));
    }

    @Test void localReadRequiresVerifiedOwnerAndChecksTimeBeforeResult() {
        valid();
        UserProfile profile = mock(UserProfile.class);
        when(users.getOwnProfile(any(), eq(OWNER))).thenReturn(profile);
        assertSame(profile, boundary.getOwnProfile("jwt", OWNER));
        verifyNoInteractions(authority);
        assertThrows(SessionRejectedException.class, () -> boundary.getOwnProfile("jwt", OTHER));
        verify(users, times(1)).getOwnProfile(any(), eq(OWNER));
        time.set(NOW.plusSeconds(100));
        assertThrows(SessionRejectedException.class, () -> boundary.getOwnProfile("jwt", OWNER));
    }

    @Test void invalidTokenAndMissingPermissionNeverReachWork() {
        when(verifier.verify("invalid")).thenThrow(new IllegalArgumentException());
        assertThrows(IllegalArgumentException.class, () -> boundary.deactivateUser("invalid", OWNER));
        valid();
        assertThrows(SessionRejectedException.class, () -> boundary.deactivateUser("jwt", OTHER));
        verifyNoInteractions(users, authority);
    }

    @Test void sensitiveWorkRequiresAuthorityAndRejectsFailure() {
        valid();
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenThrow(new SessionRejectedException());
        assertThrows(SessionRejectedException.class, () -> boundary.deactivateUser("jwt", OWNER));
        verifyNoInteractions(users);
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> boundary.deactivateUser("jwt", OWNER));
        verifyNoInteractions(users);
    }

    @Test void authorizedSensitiveWorkUsesVerifiedIdentityAndSession() {
        valid();
        User result = mock(User.class);
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Supplier<User> work = invocation.getArgument(2);
            return work.get();
        });
        when(users.deactivateUser(any(), eq(OWNER))).thenAnswer(invocation -> {
            AuthenticatedPrincipal principal = invocation.getArgument(0);
            assertEquals(OWNER, principal.userId());
            return result;
        });
        assertSame(result, boundary.deactivateUser("jwt", OWNER));
        verify(authority).withCurrentSession(eq(SID), eq(OWNER), any());
    }

    @Test void expiryDuringSensitiveWorkRejectsResult() {
        valid();
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Supplier<User> work = invocation.getArgument(2);
            return work.get();
        });
        when(users.deactivateUser(any(), eq(OWNER))).thenAnswer(invocation -> {
            time.set(NOW.plusSeconds(100));
            return mock(User.class);
        });
        assertThrows(SessionRejectedException.class, () -> boundary.deactivateUser("jwt", OWNER));
    }
}
