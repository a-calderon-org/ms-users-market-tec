package com.markettec.users.application.session;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class CsrfKeysTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private CsrfKeys keys() { return CsrfKeys.fromJson("{\"v1\":\"" + key + "\"}", "v1"); }

    @Test void bindsSessionGenerationAndRefreshMaterial() {
        var keys = keys();
        String one = keys.derive("v1", "a".repeat(32), 0, "b".repeat(64));
        assertEquals(one, keys.derive("v1", "a".repeat(32), 0, "b".repeat(64)));
        assertNotEquals(one, keys.derive("v1", "c".repeat(32), 0, "b".repeat(64)));
        assertNotEquals(one, keys.derive("v1", "a".repeat(32), 1, "b".repeat(64)));
        assertNotEquals(one, keys.derive("v1", "a".repeat(32), 0, "c".repeat(64)));
        assertThrows(SessionRejectedException.class,
                () -> keys.derive("unknown", "a".repeat(32), 0, "b".repeat(64)));
    }

    @Test void rejectsIncompleteConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> CsrfKeys.fromJson("", "v1"));
        assertThrows(IllegalArgumentException.class, () -> CsrfKeys.fromJson("{\"v1\":\"" + key + "\"}", "v2"));
        assertThrows(IllegalArgumentException.class, () -> CsrfKeys.fromJson("{\"v1\":\"AA==\"}", "v1"));
    }
}
