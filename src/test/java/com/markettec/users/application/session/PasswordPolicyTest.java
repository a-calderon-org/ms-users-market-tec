package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    private static final Email ACCOUNT = new Email("maria.lopez@estudiantec.cr");

    private static void rejected(String password) {
        var failure = assertThrows(PasswordOperationException.class, () -> PasswordPolicy.require(password, ACCOUNT));
        assertEquals(PasswordOperationException.Code.WEAK_PASSWORD, failure.code());
    }

    @Test void acceptsLongPassphrasesWithoutCompositionRules() {
        for (String ok : List.of("correct horse battery", "a1b2c3d4e5f6", "contraseña muy larga 1", "😀😀😀😀😀😀😀😀😀😀 ok!",
                "correct horse battery staple ".repeat(5).substring(0, 128)))
            assertDoesNotThrow(() -> PasswordPolicy.require(ok, ACCOUNT), ok);
    }

    @Test void rejectsShortLongControlRepeatedCommonAndAccountDerivedValues() {
        rejected(null);
        rejected("123456789");
        rejected("a".repeat(129));
        rejected("tab\tseparated password");
        rejected("aaaaaaaaaaaaaaa");
        rejected("abababababab");
        rejected("Password123");
        rejected("Contraseña123");
        rejected("MARKETTEC123");
        rejected("my-maria.lopez-secret");
        rejected("maria.lopez@estudiantec.cr");
    }

    @Test void countsCodePointsNotUtf16Units() {
        assertDoesNotThrow(() -> PasswordPolicy.require("😀😁😂🤣😃😄😅😆😉😊", ACCOUNT));
        rejected("😀😁😂🤣😃😄😅😆😉");
    }
}
