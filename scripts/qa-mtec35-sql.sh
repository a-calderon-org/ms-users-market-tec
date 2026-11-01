#!/usr/bin/env bash
# Strict QA entrypoint: missing Docker, undiscovered tests and skipped cases all fail.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${MTEC35_SQLSERVER_ACCEPT_EULA:-}" != Y ]]; then
  echo 'SQL Server EULA authorization required: MTEC35_SQLSERVER_ACCEPT_EULA=Y' >&2
  exit 1
fi
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || {
  echo 'Strict SQL QA requires a native Linux x86-64 runner.' >&2; exit 1;
}
[[ "$(docker info --format '{{.OSType}}/{{.Architecture}}')" =~ ^linux/(x86_64|amd64)$ ]] || {
  echo 'Docker must be available and run native Linux x86-64 containers.' >&2; exit 1;
}
python3 -B -m unittest discover -s scripts/tests -v
./mvnw --batch-mode --no-transfer-progress -Psql-integration clean verify
python3 scripts/verify-mtec35-reports.py
