package com.markettec.users.application.session;

import com.markettec.users.application.ports.out.PasswordHasher;
import com.markettec.users.domain.model.UserId;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory ports for the password feature: deterministic, fast and free of any real hash. */
final class PasswordFakes {
    private PasswordFakes() { }

    static final class Credentials implements PasswordCredentialRepository {
        final Map<UserId, PasswordCredential> rows = new HashMap<>();
        public Optional<PasswordCredential> findLocked(UserId id) { return Optional.ofNullable(rows.get(id)); }
        public void insert(PasswordCredential c) {
            if (rows.putIfAbsent(c.userId(), c) != null) throw new IllegalStateException("duplicate");
        }
        public void replace(PasswordCredential before, PasswordCredential after) {
            if (!rows.replace(before.userId(), before, after)) throw new IllegalStateException("stale");
        }
    }

    static final class Grants implements PasswordSetupGrantRepository {
        record Row(UserId user, Instant expires, boolean consumed) { }
        final Map<String, Row> rows = new HashMap<>();
        public void issue(String session, UserId user, Instant created, Instant expires) {
            if (rows.putIfAbsent(session, new Row(user, expires, false)) != null) throw new IllegalStateException("dup");
        }
        public boolean live(String session, UserId user, Instant now) {
            var r = rows.get(session);
            return r != null && !r.consumed() && r.user().equals(user) && now.isBefore(r.expires());
        }
        public boolean consume(String session, UserId user, Instant now) {
            if (!live(session, user, now)) return false;
            rows.put(session, new Row(user, rows.get(session).expires(), true));
            return true;
        }
    }

    static final class Throttle implements PasswordAttemptThrottle {
        final Map<String, State> rows = new HashMap<>();
        public Optional<State> loadLocked(String key) { return Optional.ofNullable(rows.get(key)); }
        public void save(String key, State state) { rows.put(key, state); }
        public void clear(String key) { rows.remove(key); }
    }

    /** "Hash" is reversible on purpose (tests only); it still never equals the plaintext. */
    static final class Hasher implements PasswordHasher {
        final AtomicInteger burns = new AtomicInteger();
        volatile boolean stale;
        public String hash(String password) { return "$fake$" + new StringBuilder(password).reverse(); }
        public boolean matches(String password, String encoded) { return hash(password).equals(encoded); }
        public boolean needsRehash(String encoded) { return stale; }
        public int policyVersion() { return 1; }
        public void burn(String password) { burns.incrementAndGet(); }
    }
}
