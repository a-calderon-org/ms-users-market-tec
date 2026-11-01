package com.markettec.users.application.ports.in;

import com.markettec.users.domain.model.UserId;

/**
 * Created only by a trusted adapter after authentication, never from headers or body IDs.
 * This is an application boundary, not an implemented authentication mechanism.
 */
@FunctionalInterface
public interface AuthenticatedPrincipal {
    UserId userId();
}
