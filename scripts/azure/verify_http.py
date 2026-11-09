#!/usr/bin/env python3
"""Public HTTP checks for a deployed ms-users with every authentication flag off.

Only reads: GET/HEAD/POST to routes that must answer 404 do not reach any handler.
Prints method, path and status only; response bodies are never printed.
"""
import argparse
import http.client
import json
import sys
import time
import urllib.parse

# Every route registered by some flag (scripts/tests keeps this list in sync with src/main/java).
CLOSED_ROUTES = [
    ("POST", "/v1/auth/attempts"),
    ("POST", "/v1/auth/google"),
    ("POST", "/v1/auth/google/registrations"),
    ("POST", "/v1/auth/email/requests"),
    ("POST", "/v1/auth/email/confirm"),
    ("GET", "/v1/auth/session"),
    ("POST", "/v1/auth/session/activity"),
    ("POST", "/v1/auth/refresh"),
    ("POST", "/v1/auth/logout"),
    ("POST", "/v1/auth/proofs/attempts"),
    ("POST", "/v1/auth/proofs/google"),
    ("POST", "/v1/auth/proofs/email/requests"),
    ("POST", "/v1/auth/proofs/email/confirm"),
    ("GET", "/v1/me/profile"),
    ("GET", "/v1/me/auth-methods"),
    ("GET", "/v1/usuarios/00000000-0000-0000-0000-000000000000"),
    ("GET", "/.well-known/jwks.json"),
    ("GET", "/.well-known/markettec-identity"),
    # Management listener is loopback-only; nothing of Actuator is public.
    ("GET", "/actuator/health"),
    ("GET", "/actuator/health/readiness"),
    ("GET", "/actuator/env"),
    ("GET", "/readyz"),
    ("GET", "/livez"),
]
ORIGIN = "https://origin-check.invalid"


def request(base, method, path, timeout, body=None):
    parsed = urllib.parse.urlsplit(base)
    connection_type = http.client.HTTPSConnection if parsed.scheme == "https" else http.client.HTTPConnection
    connection = connection_type(parsed.netloc, timeout=timeout)
    headers = {"Origin": ORIGIN, "X-Correlation-ID": "mtec37-verify", "X-MTEC-Client": "web"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    try:
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        response.read()
        return response.status, response.getheaders()
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="https://<app>.azurewebsites.net")
    parser.add_argument("--attempts", type=int, default=12, help="/health attempts (cold start on F1)")
    parser.add_argument("--delay", type=float, default=15.0)
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    if urllib.parse.urlsplit(base).scheme != "https":
        raise SystemExit("base_url must be https")
    failures = []

    status = None
    for attempt in range(1, args.attempts + 1):
        try:
            status, headers = request(base, "GET", "/health", timeout=60)
        except OSError as error:
            status, headers = f"error:{type(error).__name__}", []
        print(f"GET /health attempt={attempt} status={status}")
        if status == 200:
            break
        time.sleep(args.delay)
    if status != 200:
        failures.append("GET /health")

    for method, path in CLOSED_ROUTES:
        body = json.dumps({}) if method == "POST" else None
        status, headers = request(base, method, path, timeout=30, body=body)
        names = {name.lower() for name, _ in headers}
        leaked = sorted(names & {"set-cookie", "access-control-allow-origin", "access-control-allow-credentials"})
        print(f"{method} {path} status={status}" + (f" leaked={leaked}" if leaked else ""))
        if status != 404 or leaked:
            failures.append(f"{method} {path}")

    insecure = "http://" + urllib.parse.urlsplit(base).netloc
    status, headers = request(insecure, "GET", "/health", timeout=30)
    location = dict((k.lower(), v) for k, v in headers).get("location", "")
    print(f"GET http:/health status={status} redirects_to_https={location.startswith('https://')}")
    if status not in (301, 302, 307, 308) or not location.startswith("https://"):
        failures.append("HTTPS-only redirect")

    if failures:
        print("FAIL: " + ", ".join(failures))
        return 1
    print("PASS: /health 200, closed routes 404 without cookies/CORS, HTTPS-only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
