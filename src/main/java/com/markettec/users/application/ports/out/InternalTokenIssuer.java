package com.markettec.users.application.ports.out;

import com.markettec.users.application.session.AuthenticatedSession;

public interface InternalTokenIssuer {
    String issue(AuthenticatedSession authenticated);
}
