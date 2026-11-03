package com.markettec.users.application.ports.out;

/** Verifies an external credential against server-owned policy and request context. */
public interface GoogleIdentityVerifier {
    VerifiedGoogleIdentity verify(String idToken, GoogleVerificationContext context);
}
