package com.markettec.users.adapters.out.password;

import com.markettec.users.application.ports.out.PasswordHasher;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id with OWASP's minimum profile (19 MiB, 2 iterations, 1 lane). Each hash is self-describing, so
 * raising the parameters later only needs a new {@link #policyVersion()}: older hashes keep verifying with
 * the parameters they carry and are upgraded on the next successful login. Concurrency is bounded because
 * every hash holds its memory for its whole duration; a saturated hasher fails closed instead of queueing.
 */
public final class Argon2idPasswordHasher implements PasswordHasher {
    public static final int POLICY_VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_KIB = 19_456;
    private static final int ITERATIONS = 2;
    private static final String CURRENT_PARAMETERS = "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM + "$";
    /** A valid hash of a random value, used only to equalize the work done for unknown accounts. */
    private final String decoy;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM,
            MEMORY_KIB, ITERATIONS);
    private final Semaphore capacity;
    private final long waitMillis;

    public Argon2idPasswordHasher(int maxConcurrent, long waitMillis) {
        if (maxConcurrent < 1 || maxConcurrent > 64 || waitMillis < 0 || waitMillis > 10_000)
            throw new IllegalArgumentException("Invalid hasher capacity");
        this.capacity = new Semaphore(maxConcurrent, true);
        this.waitMillis = waitMillis;
        this.decoy = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Override public String hash(String password) {
        return bounded(() -> encoder.encode(password));
    }

    @Override public boolean matches(String password, String encoded) {
        if (encoded == null || !encoded.startsWith("$argon2id$")) return false;
        try {
            return bounded(() -> encoder.matches(password, encoded));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    @Override public boolean needsRehash(String encoded) {
        return encoded == null || !encoded.startsWith(CURRENT_PARAMETERS);
    }

    @Override public int policyVersion() { return POLICY_VERSION; }

    @Override public void burn(String password) {
        bounded(() -> encoder.matches(password == null ? "" : password, decoy));
    }

    private <T> T bounded(java.util.function.Supplier<T> work) {
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new com.markettec.users.application.session.PasswordOperationException(
                    com.markettec.users.application.session.PasswordOperationException.Code.UNAVAILABLE);
        }
        if (!acquired) throw new com.markettec.users.application.session.PasswordOperationException(
                com.markettec.users.application.session.PasswordOperationException.Code.UNAVAILABLE);
        try {
            return work.get();
        } finally {
            capacity.release();
        }
    }
}
