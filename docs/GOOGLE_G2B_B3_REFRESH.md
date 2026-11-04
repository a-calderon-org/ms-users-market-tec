# G2B B3 refresh HTTP

`POST /v1/auth/refresh` is enabled only with `USERS_SESSION_REFRESH_ENABLED=true`,
verified HTTPS session topology, exact allowed origins, and a fully configured internal
JWT signer (`USERS_INTERNAL_JWT_ENABLED=true`, issuer, audience and signing keys).
The default remains disabled. The browser must send the current refresh cookie and
CSRF proof with `{"mode":"rotate"}`. A successful rotation returns the new JWT and
CSRF proof, and puts the new refresh only in the secure HttpOnly cookie.

## Pending contract discrepancy

OpenAPI `0.3.0-draft` describes revocation on consumed-refresh replay. The approved
session authority policy for this increment rejects consumed credentials without
revoking the winning session family. The contract needs reconciliation in a separate
decision; this increment does not alter OpenAPI or implement replay revocation.
