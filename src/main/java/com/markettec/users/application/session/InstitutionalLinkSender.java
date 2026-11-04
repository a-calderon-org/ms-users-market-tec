package com.markettec.users.application.session;

import com.markettec.users.domain.model.Email;

/** Delivery adapter must never log or retain the one-use secret. */
public interface InstitutionalLinkSender {
    void send(Email recipient, String attemptId, String secret);
}
