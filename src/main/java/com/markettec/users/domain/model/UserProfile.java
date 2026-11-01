package com.markettec.users.domain.model;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/** Profile owned by the user aggregate; account status belongs to User. */
public record UserProfile(UserId userId, ProfileDetails details, Email correo, URI fotoPerfil,
                          Instant fechaRegistro, Instant fechaActualizacion, boolean verificado) {
    public UserProfile {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(details);
        Objects.requireNonNull(correo);
        Objects.requireNonNull(fotoPerfil);
        Objects.requireNonNull(fechaRegistro);
        Objects.requireNonNull(fechaActualizacion);
        if (!fotoPerfil.isAbsolute() || fechaRegistro.isBefore(Instant.EPOCH)
                || fechaActualizacion.isBefore(fechaRegistro)) {
            throw new IllegalArgumentException("Invalid profile URI or timestamps");
        }
    }

    public UserProfile update(ProfileDetails replacement, Instant now) {
        if (now.isBefore(fechaActualizacion)) {
            throw new IllegalArgumentException("Profile timestamps cannot go backwards");
        }
        return new UserProfile(userId, replacement, correo, fotoPerfil,
                fechaRegistro, now, verificado);
    }

    public UserProfile verifyEmail(Email verifiedEmail) {
        if (!correo.equals(verifiedEmail)) {
            return this; // Linking a different address does not replace the profile address.
        }
        return new UserProfile(userId, details, verifiedEmail, fotoPerfil,
                fechaRegistro, fechaActualizacion, true);
    }
}
