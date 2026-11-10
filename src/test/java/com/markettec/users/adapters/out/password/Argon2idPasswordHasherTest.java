package com.markettec.users.adapters.out.password;

import com.markettec.users.application.session.PasswordOperationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class Argon2idPasswordHasherTest {
    private final Argon2idPasswordHasher hasher = new Argon2idPasswordHasher(2, 2_000);

    @Test void producesSelfDescribingSaltedArgon2idHashes() {
        String a = hasher.hash("correct horse battery");
        String b = hasher.hash("correct horse battery");
        assertTrue(a.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), a);
        assertNotEquals(a, b, "unique salt per hash");
        assertFalse(a.contains("correct horse battery"));
        assertTrue(a.length() <= 255, "fits the column");
        assertFalse(hasher.needsRehash(a));
        assertEquals(Argon2idPasswordHasher.POLICY_VERSION, hasher.policyVersion());
    }

    @Test void verifiesOnlyTheRightPasswordAndRejectsForeignOrMalformedHashes() {
        String hash = hasher.hash("correct horse battery");
        assertTrue(hasher.matches("correct horse battery", hash));
        assertFalse(hasher.matches("correct horse batterY", hash));
        assertFalse(hasher.matches("correct horse battery", "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"));
        assertFalse(hasher.matches("correct horse battery", "$argon2id$garbage"));
        assertFalse(hasher.matches("correct horse battery", null));
    }

    @Test void olderParametersStillVerifyAndAreFlaggedForUpgrade() {
        String weaker = new Argon2PasswordEncoder(16, 32, 1, 4_096, 1).encode("correct horse battery");
        assertTrue(weaker.startsWith("$argon2id$"));
        assertTrue(hasher.matches("correct horse battery", weaker), "parameters come from the stored hash");
        assertTrue(hasher.needsRehash(weaker));
    }

    @Test void burnSpendsWorkAndNeverThrowsForNullOrEmpty() {
        assertDoesNotThrow(() -> hasher.burn(null));
        assertDoesNotThrow(() -> hasher.burn("whatever"));
    }

    @Test void saturatedHasherFailsClosedInsteadOfQueueingWithoutBound() throws Exception {
        var single = new Argon2idPasswordHasher(1, 0);
        var refused = new java.util.concurrent.atomic.AtomicInteger();
        var done = new java.util.concurrent.atomic.AtomicInteger();
        var go = new CountDownLatch(1);
        var threads = new java.util.ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                try { go.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < 5; i++) {
                    try {
                        single.hash("busy busy busy busy");
                        done.incrementAndGet();
                    } catch (PasswordOperationException saturated) {
                        assertEquals(PasswordOperationException.Code.UNAVAILABLE, saturated.code());
                        refused.incrementAndGet();
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        for (Thread thread : threads) thread.join(60_000);
        assertTrue(refused.get() > 0, "concurrent callers beyond capacity are refused");
        assertTrue(done.get() > 0, "admitted callers still complete");
    }

    @Test void rejectsInvalidCapacityConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new Argon2idPasswordHasher(0, 100));
        assertThrows(IllegalArgumentException.class, () -> new Argon2idPasswordHasher(65, 100));
        assertThrows(IllegalArgumentException.class, () -> new Argon2idPasswordHasher(2, -1));
    }
}
