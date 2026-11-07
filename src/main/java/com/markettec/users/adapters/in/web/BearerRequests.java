package com.markettec.users.adapters.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

final class BearerRequests {
    private BearerRequests() { }

    /** Exactly one {@code Authorization: Bearer <jwt>} header; no query or cookie fallback. */
    static String bearer(HttpServletRequest request) {
        var values = request.getHeaders("Authorization");
        String header = values.hasMoreElements() ? values.nextElement() : null;
        if (header == null || values.hasMoreElements() || !header.startsWith("Bearer ") || header.length() < 8
                || header.indexOf(' ', 7) >= 0)
            throw new GoogleAuthController.AuthHttpException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED");
        return header.substring(7);
    }
}
