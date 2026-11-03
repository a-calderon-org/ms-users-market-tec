package com.markettec.users.application.ports.out;

import com.markettec.users.application.ports.in.VerifiedInternalToken;

public interface InternalTokenVerifier {
    VerifiedInternalToken verify(String compact);
}
