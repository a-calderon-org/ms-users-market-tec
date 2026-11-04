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
    private static final ProfileDetails DETAILS = new ProfileDetails("ana", "Ana", "Lopez");
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
        assertThrows(IllegalArgumentException.class, () -> boundary.updateOwnProfile("invalid", OWNER, DETAILS));
        valid();
        assertThrows(SessionRejectedException.class, () -> boundary.updateOwnProfile("jwt", OTHER, DETAILS));
        verifyNoInteractions(users, authority);
    }

    @Test void sensitiveWorkRequiresAuthorityAndRejectsFailure() {
        valid();
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenThrow(new SessionRejectedException());
        assertThrows(SessionRejectedException.class, () -> boundary.updateOwnProfile("jwt", OWNER, DETAILS));
        verifyNoInteractions(users);
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> boundary.updateOwnProfile("jwt", OWNER, DETAILS));
        verifyNoInteractions(users);
    }

    @Test void authorizedSensitiveWorkUsesVerifiedIdentityAndSession() {
        valid();
        UserProfile result = mock(UserProfile.class);
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Supplier<UserProfile> work = invocation.getArgument(2);
            return work.get();
        });
        when(users.updateOwnProfile(any(), eq(OWNER), eq(DETAILS))).thenAnswer(invocation -> {
            AuthenticatedPrincipal principal = invocation.getArgument(0);
            assertEquals(OWNER, principal.userId());
            return result;
        });
        assertSame(result, boundary.updateOwnProfile("jwt", OWNER, DETAILS));
        verify(authority).withCurrentSession(eq(SID), eq(OWNER), any());
    }

    @Test void expiryDuringSensitiveWorkRejectsResult() {
        valid();
        when(authority.withCurrentSession(eq(SID), eq(OWNER), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") java.util.function.Supplier<UserProfile> work = invocation.getArgument(2);
            return work.get();
        });
        when(users.updateOwnProfile(any(), eq(OWNER), eq(DETAILS))).thenAnswer(invocation -> {
            time.set(NOW.plusSeconds(100));
            return mock(UserProfile.class);
        });
        assertThrows(SessionRejectedException.class, () -> boundary.updateOwnProfile("jwt", OWNER, DETAILS));
    }

    @Test void deactivationAndLinkStayClosedWithoutIndependentProofs() {
        valid();
        assertThrows(SessionRejectedException.class, () -> boundary.deactivateUser("jwt", OWNER));
        assertThrows(SessionRejectedException.class, () -> boundary.linkExternalIdentity("jwt", OWNER, null));
        verifyNoInteractions(users, authority);
    }
}
