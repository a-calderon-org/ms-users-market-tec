package com.markettec.users.domain.model;

/** The three editable fields of the existing UpdateUserRequest contract. */
public record ProfileDetails(String nombreUsuario, String nombre, String apellidos) {
    public ProfileDetails {
        validate(nombreUsuario, 80, false);
        validate(nombre, 100, false);
        validate(apellidos, 160, true);
    }

    private static void validate(String value, int maximum, boolean allowEmpty) {
        if (value == null || value.codePointCount(0, value.length()) > maximum
                || (!allowEmpty && value.isBlank())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid profile field");
        }
    }
}
