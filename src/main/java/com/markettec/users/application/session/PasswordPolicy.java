package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;
import java.util.Locale;
import java.util.Set;

/**
 * Length-first password policy (no composition rules): 10 to 128 code points, no control characters,
 * not a single repeated character, not a trivially common value and not derived from the account address.
 */
public final class PasswordPolicy {
    public static final int MIN = 10;
    public static final int MAX = 128;
    private static final Set<String> COMMON = Set.of("1234567890", "12345678910", "0123456789", "qwertyuiop",
            "password12", "password123", "password1234", "contrasena1", "contrasena12", "contrasena123",
            "contrasena1234", "markettec123", "markettec1234", "iloveyou123", "abcdefghij", "1q2w3e4r5t");

    private PasswordPolicy() { }

    /** Throws {@link PasswordOperationException} (WEAK_PASSWORD) when the password is unacceptable. */
    public static void require(String password, Email account) {
        if (password == null) throw weak();
        int length = password.codePointCount(0, password.length());
        if (length < MIN || length > MAX) throw weak();
        if (password.codePoints().anyMatch(Character::isISOControl)) throw weak();
        if (password.codePoints().distinct().count() < 4) throw weak();
        String lower = password.toLowerCase(Locale.ROOT);
        String folded = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        if (COMMON.contains(folded)) throw weak();
        if (account != null) {
            String address = account.normalized().toLowerCase(Locale.ROOT);
            String local = address.substring(0, address.indexOf('@'));
            if (lower.contains(address) || (local.length() >= 4 && lower.contains(local))) throw weak();
        }
    }

    private static PasswordOperationException weak() {
        return new PasswordOperationException(PasswordOperationException.Code.WEAK_PASSWORD);
    }
}
