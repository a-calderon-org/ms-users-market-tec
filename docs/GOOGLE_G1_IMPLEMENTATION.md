# Google-G1 backend integration status

## Implemented internally

`GoogleLoginService` issues a 32-character random attempt ID, 256-bit nonce and
256-bit browser secret. The repository stores only a SHA-256 digest of the browser
secret. Attempts expire after five minutes, and SQL Server locks the row through
Google verification, exact identity resolution, one-time consumption, session
creation and JWT signing. A failed step rolls back consumption and session writes.
The JWT issuer uses the existing RS256 service and 240-second/deadline cap. Only
an existing `(GOOGLE, issuer, subject)` link can authenticate. Email matching,
implicit registration and institutional privileges are absent from this flow.

The SQL migration is `V4__google_login_intents.sql`. Startup schema validation
checks its table, key, columns and constraints. The SQL integration suite adds a
real database test for concurrent exchanges and rollback; the existing CI workflow
runs that suite on a native Linux x86-64 runner.

## Public HTTP boundary remains disabled

MTEC-15 and contract PR #25 still label the Google exchange as Draft and lack
approved browser transport, CSRF/CORS, refresh and response semantics. The service
registers no HTTP controller. Before enabling one, the contract must decide how
the server-issued browser secret is delivered and returned (for example, a secure
host-only cookie), define CSRF protection and exact request/response/error shapes,
and approve the transport and refresh protocol. An endpoint must never accept a
client-selected nonce or use a B04 session attempt for initial login.

`users.google.login-enabled` defaults to false. Even when enabled with the Google
verifier, signing keys and a single `login-audience`, only the internal service bean
is made available; no public endpoint is exposed.
