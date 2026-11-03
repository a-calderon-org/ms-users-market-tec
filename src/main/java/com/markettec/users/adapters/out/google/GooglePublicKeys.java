package com.markettec.users.adapters.out.google;

import com.nimbusds.jose.jwk.JWKSet;

/** The implementation obtains keys only from a server-configured trusted source. */
public interface GooglePublicKeys {
    JWKSet keys(boolean refresh);
}
