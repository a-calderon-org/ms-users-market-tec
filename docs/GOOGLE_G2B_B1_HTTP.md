# Google G2B B1 local HTTP adapter

The three Draft routes are available only with the SQL profile and explicit
`USERS_GOOGLE_HTTP_ENABLED=true`. Google verification, login, registration,
nonce consumption, JWT issuance, session refresh verifiers, and session CSRF
remain owned by their existing application services.

The enabled service also requires `USERS_GOOGLE_LOGIN_ENABLED=true`, the Google
verifier and internal JWT configurations, plus:

- `USERS_GOOGLE_HTTP_ALLOWED_ORIGINS`: comma-separated exact HTTPS browser origins.
- `USERS_GOOGLE_HTTP_PUBLIC_API_ORIGIN`: exact HTTPS APIM origin.
- `USERS_GOOGLE_HTTP_SAME_SITE_VERIFIED=true`: operator assertion that the listed
  browser origins and public API origin satisfy the approved HTTPS same-site
  topology. This assertion must follow DNS, certificate, browser, and APIM checks.

No origin or API host has a default value. Missing configuration fails startup
when the HTTP switch is enabled. The operator must retain ingress restrictions,
rate limits, and APIM `Set-Cookie` forwarding before enabling externally.

`POST /v1/auth/attempts` accepts Google login or register only, checks exact
Origin, JSON, and `X-MTEC-Client: web`, and sets an HttpOnly Secure host-only
attempt cookie. The response has an attempt CSRF token derived from the random
browser binding with a domain-separated SHA-256 digest. The secret itself stays
in the cookie. `POST /v1/auth/google` and
`POST /v1/auth/google/registrations` require that cookie and CSRF token, then
pass the browser secret and Google ID Token to their existing dedicated services.
The session CSRF value in `AuthResponse` comes from B2 `SessionAuthority.issue`.
The refresh credential appears only in a Secure HttpOnly `__Host-` cookie with
`Path=/; SameSite=Lax`, bounded by the current session deadline.

The adapter translates typed consumed, expired, and identity conflict failures
to the Draft status and code. Other proof failures use `AUTH_PROOF_INVALID`;
response messages do not reveal account details. Logs record route templates and
status, without request bodies, Google ID Tokens, refresh credentials, or CSRF.

B07 interoperability and publication remain pending. Actual APIM and browser
behavior, production topology, rate policy, and Angular consumer conformance are
not established here. B1 does not expose refresh/logout or email flows. MTEC-41
and the other B07 decisions remain open. The pre-existing untracked blocker note
is intentionally outside this change.
