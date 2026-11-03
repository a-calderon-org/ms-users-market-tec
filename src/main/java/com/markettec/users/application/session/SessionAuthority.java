package com.markettec.users.application.session;

import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.ExternalIdentityRepository;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.domain.model.User;
import com.markettec.users.domain.model.ExternalIdentityKey;
import com.markettec.users.domain.model.IdentityProvider;
import com.markettec.users.domain.model.IdentityLinkStatus;
import com.markettec.users.application.ports.out.VerifiedGoogleIdentity;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import com.markettec.users.domain.model.UserId;

/** Internal authority. Callers must supply verified evidence and authorize explicit actions. */
public final class SessionAuthority {
    private final SessionRepository sessions;
    private final ExternalIdentityRepository identities;
    private final UserRepository users;
    private final UserTransactions transactions;
    private final Clock clock;
    private final SecureRandom random;
    private final SessionActionAuthorization authorization;

    public SessionAuthority(SessionRepository sessions, ExternalIdentityRepository identities,
                            UserRepository users, UserTransactions transactions, Clock clock, SecureRandom random,
                            SessionActionAuthorization authorization) {
        this.sessions = Objects.requireNonNull(sessions);
        this.identities = Objects.requireNonNull(identities);
        this.users = Objects.requireNonNull(users);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
        this.authorization = Objects.requireNonNull(authorization);
    }

    /** Trusted verified identity only; no HTTP adapter exposes this operation. */
    public Session create(VerifiedExternalIdentity evidence) {
        Objects.requireNonNull(evidence);
        return transactions.execute(() -> {
            var linked = identities.findByKey(evidence.key()).orElseThrow(SessionRejectedException::new);
            if (linked.provider() != evidence.provider() || linked.status() != IdentityLinkStatus.ACTIVE)
                throw new SessionRejectedException();
            User user = users.findById(linked.userId()).orElseThrow(SessionRejectedException::new);
            user.requireActive();
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            Session session = Session.start(HexFormat.of().formatHex(bytes), user.userId(), now());
            sessions.insert(session);
            return session;
        });
    }

    /** Mint a trusted issuance capability as part of the verified login path. */
    public AuthenticatedSession createAuthenticated(VerifiedExternalIdentity evidence) {
        return new AuthenticatedSession(create(evidence).sessionId());
    }

    /** Google subject resolves only through an existing exact issuer/subject link. */
    public AuthenticatedSession createGoogle(VerifiedGoogleIdentity evidence) {
        Objects.requireNonNull(evidence);
        return transactions.execute(() -> {
            var key = new ExternalIdentityKey(evidence.issuer(), evidence.subject());
            var linked = identities.findByKey(key).orElseThrow(SessionRejectedException::new);
            if (linked.provider() != IdentityProvider.GOOGLE || linked.status() != IdentityLinkStatus.ACTIVE)
                throw new SessionRejectedException();
            User user = users.findById(linked.userId()).orElseThrow(SessionRejectedException::new);
            user.requireActive();
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            Session session = Session.start(HexFormat.of().formatHex(bytes), user.userId(), now());
            sessions.insert(session);
            return new AuthenticatedSession(session.sessionId());
        });
    }

    /** Reauthenticate an existing session using trusted, freshly verified identity evidence. */
    public AuthenticatedSession authenticate(VerifiedExternalIdentity evidence, String sessionId) {
        Objects.requireNonNull(evidence);
        if (sessionId == null || !sessionId.matches("[0-9a-f]{32}")) throw new SessionRejectedException();
        return transactions.execute(() -> {
            var linked = identities.findByKey(evidence.key()).orElseThrow(SessionRejectedException::new);
            if (linked.provider() != evidence.provider() || linked.status() != IdentityLinkStatus.ACTIVE)
                throw new SessionRejectedException();
            Session session = sessions.loadLocked(sessionId).orElseThrow(SessionRejectedException::new);
            if (!session.validAt(now()) || !session.userId().equals(linked.userId()))
                throw new SessionRejectedException();
            users.findById(session.userId()).orElseThrow(SessionRejectedException::new).requireActive();
            return new AuthenticatedSession(sessionId);
        });
    }

    /** Snapshot only. Does not extend any deadline. */
    public Optional<Session> current(String sessionId) {
        return transactions.execute(() -> sessions.loadLocked(sessionId).filter(s -> s.validAt(now())));
    }

    /** Holds the authoritative session row lock through the caller's SQL commit. */
    public <T> T withCurrentSession(String sessionId, UserId userId, Supplier<T> work) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(work);
        if (sessionId == null || !sessionId.matches("[0-9a-f]{32}")) throw new SessionRejectedException();
        return transactions.execute(() -> {
            Session session = sessions.loadLocked(sessionId).orElseThrow(SessionRejectedException::new);
            if (!session.userId().equals(userId) || !session.validAt(now())) throw new SessionRejectedException();
            T result = work.get();
            if (!session.validAt(now())) throw new SessionRejectedException();
            return result;
        });
    }

    public void revoke(String sessionId) {
        transactions.execute(() -> {
            Session before = sessions.loadLocked(sessionId).orElseThrow(SessionRejectedException::new);
            if (!before.revoked()) sessions.replace(before, before.revoke());
            return null;
        });
    }

    /** Only an already authorized caller may invoke this; refresh alone is not authorization. */
    public Session renew(String sessionId) {
        return change(sessionId, true);
    }

    /** Only a server-verified, allowlisted business activity may invoke this. */
    public Session recordAdmissibleActivity(String sessionId) {
        return change(sessionId, false);
    }

    private Session change(String sessionId, boolean renewal) {
        return transactions.execute(() -> {
            Session before = sessions.loadLocked(sessionId).orElseThrow(SessionRejectedException::new);
            if (!before.validAt(now())) throw new SessionRejectedException();
            if (renewal) authorization.requireRenewal(before);
            else authorization.requireAdmissibleActivity(before);
            Session after = renewal ? before.renewAt(now()) : before.activityAt(now());
            sessions.replace(before, after);
            return after;
        });
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}
