package com.markettec.users.adapters.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.markettec.users.adapters.out.jwt.InternalJwtService.InvalidInternalJwtException;
import com.markettec.users.application.ports.out.InternalTokenVerifier;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.application.session.SessionRejectedException;
import com.markettec.users.application.usecases.ProtectedUserOperations;
import com.markettec.users.domain.model.ProfileDetails;
import com.markettec.users.domain.model.UserDomainException;
import com.markettec.users.domain.model.UserId;
import com.markettec.users.domain.model.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Own-profile API. Identity comes only from the verified internal JWT; ownership is enforced per call. */
@RestController
@Profile("sql-dev | sql-appservice | sql-integration")
@ConditionalOnProperty(prefix = "users.api.http", name = "enabled", havingValue = "true")
public final class UserProfileController {
    private static final Set<String> EDITABLE = Set.of("nombreUsuario", "nombre", "apellidos");
    private final InternalTokenVerifier jwt;
    private final ProtectedUserOperations operations;
    private final Clock clock;

    public UserProfileController(InternalTokenVerifier jwt, ProtectedUserOperations operations, Clock clock) {
        this.jwt = jwt;
        this.operations = operations;
        this.clock = clock;
    }

    @GetMapping(path = "/v1/me/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> myProfile(HttpServletRequest request) {
        String bearer = BearerRequests.bearer(request);
        return ok(operations.getOwnProfile(bearer, jwt.verify(bearer).userId()));
    }

    @PutMapping(path = "/v1/me/profile", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateMyProfile(@RequestBody JsonNode body, HttpServletRequest request) {
        String bearer = BearerRequests.bearer(request);
        return update(bearer, jwt.verify(bearer).userId(), body);
    }

    @GetMapping(path = "/v1/usuarios/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> profile(@PathVariable String userId, HttpServletRequest request) {
        String bearer = BearerRequests.bearer(request);
        return ok(operations.getOwnProfile(bearer, owned(bearer, userId)));
    }

    @PutMapping(path = "/v1/usuarios/{userId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateProfile(@PathVariable String userId, @RequestBody JsonNode body,
                                           HttpServletRequest request) {
        String bearer = BearerRequests.bearer(request);
        return update(bearer, owned(bearer, userId), body);
    }

    /** Authenticate first, then require the path account to be the token subject. */
    private UserId owned(String bearer, String pathId) {
        UserId subject = jwt.verify(bearer).userId();
        UserId target;
        try {
            target = new UserId(pathId);
        } catch (IllegalArgumentException malformed) {
            throw new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        if (!subject.equals(target)) throw new GoogleAuthController.AuthHttpException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        return target;
    }

    private ResponseEntity<?> update(String bearer, UserId target, JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) throw invalid();
        var names = body.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!EDITABLE.contains(name) || !body.get(name).isTextual()) throw invalid();
        }
        UserProfile current = operations.getOwnProfile(bearer, target);
        var d = current.details();
        var merged = new ProfileDetails(body.has("nombreUsuario") ? body.get("nombreUsuario").asText() : d.nombreUsuario(),
                body.has("nombre") ? body.get("nombre").asText() : d.nombre(),
                body.has("apellidos") ? body.get("apellidos").asText() : d.apellidos());
        return ok(operations.updateOwnProfile(bearer, target, merged));
    }

    private static GoogleAuthController.AuthHttpException invalid() {
        return new GoogleAuthController.AuthHttpException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    private static ResponseEntity<?> ok(UserProfile p) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new ProfileResponse(p.userId().value(),
                p.details().nombreUsuario(), p.details().nombre(), p.details().apellidos(), p.correo().address(),
                p.fotoPerfil().toString(), p.fechaRegistro().toString(), p.fechaActualizacion().toString(),
                p.verificado()));
    }

    @ExceptionHandler({GoogleAuthController.AuthHttpException.class, InvalidInternalJwtException.class,
            SessionRejectedException.class, UserDomainException.class, IllegalArgumentException.class})
    public ResponseEntity<?> failed(Exception failure, HttpServletRequest request) {
        return ApiErrors.map(failure, request, clock);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpMessageNotReadableException ignored, HttpServletRequest request) {
        return ApiErrors.of(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", request, clock);
    }

    public record ProfileResponse(String userId, String nombreUsuario, String nombre, String apellidos,
                                  String correo, String fotoPerfil, String fechaRegistro,
                                  String fechaActualizacion, boolean verificado) { }
}
