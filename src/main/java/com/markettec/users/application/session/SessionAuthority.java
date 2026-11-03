package com.markettec.users.application.session;

import com.markettec.users.application.ports.in.VerifiedExternalIdentity;
import com.markettec.users.application.ports.out.ExternalIdentityRepository;
import com.markettec.users.application.ports.out.UserRepository;
import com.markettec.users.application.ports.out.UserTransactions;
import com.markettec.users.application.ports.out.InternalTokenIssuer;
import com.markettec.users.domain.model.User;
import com.markettec.users.domain.model.ExternalIdentityKey;
import com.markettec.users.domain.model.IdentityProvider;
import com.markettec.users.domain.model.IdentityLinkStatus;
import com.markettec.users.application.ports.out.VerifiedGoogleIdentity;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
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

    /** Called within the login transaction. No credential escapes before its commit. */
    public SessionTokens issue(AuthenticatedSession authenticated, InternalTokenIssuer jwt) {
        Objects.requireNonNull(authenticated);
        Objects.requireNonNull(jwt);
        return transactions.execute(() -> {
            Session session = sessions.loadLocked(authenticated.sessionId()).orElseThrow(SessionRejectedException::new);
            if (!session.validAt(now())) throw new SessionRejectedException();
            String refresh = randomHex(32);
            String csrf = randomHex(32);
            sessions.initializeVerifiers(session.sessionId(), new SessionVerifiers(hash(refresh), hash(csrf)));
            return tokens(session, jwt.issue(authenticated), refresh, csrf);
        });
    }

    /**
     * Technical credential rotation does not authorize a user renewal or activity.
     * A consumed credential is rejected without grace. It does not revoke an otherwise live
     * session: replay-triggered revocation is a separate policy decision not approved here.
     * Refresh and CSRF proofs both rotate on success; no session deadline changes.
     */
    public SessionTokens refresh(String refresh, String csrf, InternalTokenIssuer jwt) {
        Objects.requireNonNull(jwt);
        if (!validSecret(refresh) || !validSecret(csrf)) throw new SessionRejectedException();
        return transactions.execute(() -> {
            Session before = sessions.loadByRefreshHashLocked(hash(refresh)).orElseThrow(SessionRejectedException::new);
            String id = before.sessionId();
            if (!before.validAt(now())) throw new SessionRejectedException();
            SessionVerifiers old = requireCredentials(id, refresh, csrf);
            String nextRefresh = randomHex(32);
            String nextCsrf = randomHex(32);
            sessions.rotateVerifiers(id, old, new SessionVerifiers(hash(nextRefresh), hash(nextCsrf)));
            return tokens(before, jwt.issue(new AuthenticatedSession(id)), nextRefresh, nextCsrf);
        });
    }

    /** Synchronizer proof is bound to a live session and constant-time compared. */
    public void requireCsrf(String sessionId, UserId userId, String csrf) {
        if (sessionId == null || !sessionId.matches("[0-9a-f]{32}") || userId == null || !validSecret(csrf))
            throw new SessionRejectedException();
        transactions.execute(() -> {
            Session session = sessions.loadLocked(sessionId).orElseThrow(SessionRejectedException::new);
            SessionVerifiers values = sessions.verifiers(sessionId).orElseThrow(SessionRejectedException::new);
            if (!session.validAt(now()) || !session.userId().equals(userId)
                    || !equal(values.csrfHash(), hash(csrf)))
                throw new SessionRejectedException();
            return null;
        });
    }

    /** Logout by the current browser credentials. A consumed refresh cannot log out another session. */
    public void logout(String refresh, String csrf) {
        if (!validSecret(refresh) || !validSecret(csrf)) throw new SessionRejectedException();
        transactions.execute(() -> {
            Session session = sessions.loadByRefreshHashLocked(hash(refresh)).orElseThrow(SessionRejectedException::new);
            if (!session.validAt(now())) throw new SessionRejectedException();
            requireCredentials(session.sessionId(), refresh, csrf);
            sessions.replace(session, session.revoke());
            return null;
        });
    }

    private SessionVerifiers requireCredentials(String id, String refresh, String csrf) {
        if (!validSecret(csrf)) throw new SessionRejectedException();
        SessionVerifiers values = sessions.verifiers(id).orElseThrow(SessionRejectedException::new);
        if (!equal(values.refreshHash(), hash(refresh)) || !equal(values.csrfHash(), hash(csrf)))
            throw new SessionRejectedException();
        return values;
    }

    private static boolean validSecret(String secret) {
        return secret != null && secret.matches("[0-9a-f]{64}");
    }

    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean equal(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static SessionTokens tokens(Session session, String jwt, String refresh, String csrf) {
        return new SessionTokens(jwt, refresh, csrf, session.sessionId(), session.userId().value(), session.deadline());
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
