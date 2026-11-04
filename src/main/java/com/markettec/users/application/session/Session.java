package com.markettec.users.application.session;

import com.markettec.users.domain.model.UserId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable server-owned session state. Equality with any deadline means expired. */
public record Session(String sessionId, UserId userId, Instant createdAt, Instant renewedAt, Instant renewableUntil,
                      Instant lastActivityAt, Instant idleUntil, Instant absoluteUntil,
                      boolean revoked, long version) {
    public static final Duration WINDOW = Duration.ofMinutes(60);
    public static final Duration IDLE = Duration.ofMinutes(30);
    public static final Duration ABSOLUTE = Duration.ofHours(8);

    public Session {
        if (sessionId == null || !sessionId.matches("[0-9a-f]{32}") || userId == null
                || createdAt == null || renewableUntil == null || lastActivityAt == null
                || idleUntil == null || absoluteUntil == null || version < 0) {
            throw new IllegalArgumentException("Invalid session state");
        }
        if (renewableUntil.isAfter(absoluteUntil) || !renewableUntil.isAfter(createdAt)
                || !createdAt.plus(ABSOLUTE).equals(absoluteUntil)
                || !lastActivityAt.plus(IDLE).equals(idleUntil)
                || lastActivityAt.isBefore(createdAt) || lastActivityAt.isAfter(absoluteUntil)
                || (renewedAt != null && (renewedAt.isBefore(createdAt) || renewedAt.isAfter(absoluteUntil)))) {
            throw new IllegalArgumentException("Invalid session deadlines");
        }
    }

    public static Session start(String id, UserId user, Instant now) {
        return new Session(id, user, now, now, now.plus(WINDOW), now, now.plus(IDLE),
                now.plus(ABSOLUTE), false, 0);
    }

    /** Historical sessions may lack evidence of their last explicit renewal. */
    public Instant requireRenewedAt() {
        if (renewedAt == null) throw new SessionRejectedException();
        return renewedAt;
    }

    public Instant deadline() {
        Instant first = renewableUntil.isBefore(idleUntil) ? renewableUntil : idleUntil;
        return first.isBefore(absoluteUntil) ? first : absoluteUntil;
    }

    public boolean validAt(Instant now) {
        return !revoked && Objects.requireNonNull(now).isBefore(deadline());
    }

    public Session revoke() {
        return revoked ? this : new Session(sessionId, userId, createdAt, renewedAt, renewableUntil,
                lastActivityAt, idleUntil, absoluteUntil, true, version + 1);
    }

    public Session renewAt(Instant now) {
        requireValid(now);
        Instant next = now.plus(WINDOW);
        if (next.isAfter(absoluteUntil)) next = absoluteUntil;
        return new Session(sessionId, userId, createdAt, now, next, lastActivityAt, idleUntil,
                absoluteUntil, false, version + 1);
    }

    public Session activityAt(Instant now) {
        requireValid(now);
        if (now.isBefore(lastActivityAt)) throw new SessionRejectedException();
        return new Session(sessionId, userId, createdAt, renewedAt, renewableUntil, now, now.plus(IDLE),
                absoluteUntil, false, version + 1);
    }

    private void requireValid(Instant now) {
        if (!validAt(now)) throw new SessionRejectedException();
    }
}
