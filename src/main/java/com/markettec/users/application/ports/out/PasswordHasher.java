package com.markettec.users.application.ports.out;

/** Adaptive password hashing. Implementations never log or retain the plaintext. */
public interface PasswordHasher {
    /** Self-describing hash (algorithm, parameters, salt) of the password. */
    String hash(String password);

    boolean matches(String password, String encoded);

    /** True when the stored hash predates the current parameters and should be recomputed on next success. */
    boolean needsRehash(String encoded);

    /** Version of the current parameter set, stored next to each hash. */
    int policyVersion();

    /** Spends comparable work when no real hash exists, so unknown accounts are not timing-distinguishable. */
    void burn(String password);
}
