#!/usr/bin/env bash
# ms-users — Azure Container Apps (Consumption) staging operations (MTEC-37, replaces App Service F1).
#
# Cost model (Canada Central, 30 days): scale to zero, 0.5 vCPU / 1 GiB, ACR Basic (~US$5),
# Log Analytics capped at 0.1 GB/day (inside the 5 GB free ingestion). No custom VNet: a custom
# VNet adds a billed Standard Load Balancer and public IPs (~US$22/month) and breaks the budget.
# SQL access is one /32 firewall rule per outbound IP of the app; authentication stays Entra-only.
#
# Usage: scripts/azure/aca/ms-users-aca.sh <step> [--sha <commit>]
#   infra        create identity, ACR, Log Analytics and the Container Apps environment
#   image --sha  build linux/amd64 image of a clean checkout of <commit> (CI must be green) and push
#   app --sha    create (flags false) or update (existing flags and settings kept) the container app
#   sql-access   /32 SQL firewall rules for the app outbound IPs + least-privilege runtime user
#   verify       HTTPS /health, sensitive routes closed, flags, Key Vault secret reference
#   email        opt-in Magic Link delivery: Key Vault ref to the mail provider key + USERS_EMAIL_* settings
# Secrets are never printed. Authentication flags are never enabled by this script, except USERS_EMAIL_ENABLED
# by the explicit `email` step.
set -euo pipefail
set +x

SUB="${AZ_SUBSCRIPTION:-26db4383-add6-4e47-9ca1-3b208853ac17}"
LOC="canadacentral"
RG="acalderon-TEC"
SQL_RG="a-calderon-org" SQL_SERVER="sql-markettec-dev" SQL_DB="sqldb-ms-users-dev"
KV="kv-mtec-users-stg" CSRF_SECRET="users-csrf-keys-json" CSRF_KID="stg-2026-11"
EMAIL_SECRET="users-email-provider-api-key"
UAI="id-ms-users-stg" ACR="acrmsusersstg" LAW="log-ms-users-stg" ENV="cae-ms-users-stg2" APP="ca-ms-users-stg"
REPO_SLUG="a-calderon-org/ms-users-market-tec"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
FLAGS=(USERS_INTERNAL_JWT_ENABLED USERS_API_HTTP_ENABLED USERS_AUTH_METHOD_LINKING_ENABLED USERS_EMAIL_ENABLED
  USERS_SESSION_LOGOUT_ENABLED USERS_SESSION_REFRESH_ENABLED USERS_SESSION_HTTP_ENABLED
  USERS_SESSION_HTTP_HTTPS_TOPOLOGY_VERIFIED USERS_GOOGLE_VERIFIER_ENABLED USERS_GOOGLE_LOGIN_ENABLED
  USERS_GOOGLE_REGISTRATION_ENABLED USERS_GOOGLE_HTTP_ENABLED USERS_GOOGLE_HTTP_SAME_SITE_VERIFIED
  USERS_GATEWAY_USER_TOKEN_RELOCATION_ENABLED USERS_PASSWORD_ENABLED)

die() { echo "error: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }
STEP="${1:-}"; shift || true
SHA=""
while [[ $# -gt 0 ]]; do case "$1" in --sha) SHA="${2:-}"; shift 2 ;; *) die "unknown option $1" ;; esac; done
az account set -s "$SUB"

require_green_sha() {
  [[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha <40-hex commit> required"
  local ok
  ok=$(gh run list -R "$REPO_SLUG" --commit "$SHA" --workflow "ms-users CI" --json conclusion -q '[.[]|select(.conclusion=="success")]|length')
  [[ "$ok" -ge 1 ]] || die "no successful 'ms-users CI' run for $SHA"
}

uai_id() { az identity show -g "$RG" -n "$UAI" --query id -o tsv; }

case "$STEP" in
  infra)
    az identity create -g "$RG" -n "$UAI" -l "$LOC" -o none
    az acr show -n "$ACR" -o none 2>/dev/null || az acr create -g "$RG" -n "$ACR" -l "$LOC" --sku Basic --admin-enabled false -o none
    az monitor log-analytics workspace show -g "$RG" -n "$LAW" -o none 2>/dev/null \
      || az monitor log-analytics workspace create -g "$RG" -n "$LAW" -l "$LOC" --retention-time 30 --quota 0.1 -o none
    pid=$(az identity show -g "$RG" -n "$UAI" --query principalId -o tsv)
    az role assignment create --assignee-object-id "$pid" --assignee-principal-type ServicePrincipal --role AcrPull \
      --scope "$(az acr show -n "$ACR" --query id -o tsv)" -o none
    az role assignment create --assignee-object-id "$pid" --assignee-principal-type ServicePrincipal \
      --role "Key Vault Secrets User" --scope "$(az keyvault show -n "$KV" --query id -o tsv)/secrets/$CSRF_SECRET" -o none
    if ! az containerapp env show -g "$RG" -n "$ENV" -o none 2>/dev/null; then
      ws=$(az monitor log-analytics workspace show -g "$RG" -n "$LAW" --query customerId -o tsv)
      key=$(az monitor log-analytics workspace get-shared-keys -g "$RG" -n "$LAW" --query primarySharedKey -o tsv)
      # `az containerapp env create` without a VNet now yields an Express environment, which rejects
      # Key Vault secret references and system-assigned identities. Request WorkloadProfiles mode.
      # (Azure for Students allows one environment per region.)
      body=$(mktemp); trap 'rm -f "$body"' EXIT
      python3 -c 'import json,sys; json.dump({"location":sys.argv[1],"properties":{"environmentMode":"WorkloadProfiles",
        "workloadProfiles":[{"name":"Consumption","workloadProfileType":"Consumption"}],
        "appLogsConfiguration":{"destination":"log-analytics","logAnalyticsConfiguration":{"customerId":sys.argv[2],"sharedKey":sys.argv[3]}}}},
        open(sys.argv[4],"w"))' "$LOC" "$ws" "$key" "$body"
      az rest --method put -o none --body "@$body" --url \
        "https://management.azure.com/subscriptions/$SUB/resourceGroups/$RG/providers/Microsoft.App/managedEnvironments/$ENV?api-version=2025-10-02-preview"
      until [[ "$(az containerapp env show -g "$RG" -n "$ENV" --query properties.provisioningState -o tsv)" == Succeeded ]]; do sleep 15; done
    fi
    log "infra ready" ;;

  image)
    require_green_sha
    # The build context is exactly the tree of the CI-approved commit. Nothing is copied from the
    # local working tree: a modified or untracked Dockerfile/.dockerignore must never reach the image.
    git -C "$ROOT" cat-file -e "$SHA^{commit}" 2>/dev/null || die "commit $SHA is not available locally (git fetch)"
    for required in Dockerfile .dockerignore; do
      git -C "$ROOT" cat-file -e "$SHA:$required" 2>/dev/null || die "$required missing in commit $SHA"
    done
    work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
    git -C "$ROOT" archive --format=tar "$SHA" | tar -x -C "$work"
    for required in Dockerfile .dockerignore; do
      [[ -f "$work/$required" ]] || die "$required missing from the archive of $SHA"
    done
    az acr login -n "$ACR" >/dev/null
    docker buildx build --platform linux/amd64 --label "org.opencontainers.image.revision=$SHA" \
      -t "$ACR.azurecr.io/ms-users:$SHA" --push "$work"
    log "pushed $ACR.azurecr.io/ms-users:$SHA" ;;

  app)
    require_green_sha
    id=$(uai_id)
    env_args=(SPRING_PROFILES_ACTIVE=sql-appservice "USERS_SQL_SERVER=$SQL_SERVER.database.windows.net"
      "USERS_SQL_DATABASE=$SQL_DB" USERS_SQL_PORT=1433 USERS_SQL_POOL_SIZE=3 "USERS_CSRF_ACTIVE_KID=$CSRF_KID"
      USERS_CSRF_KEYS_JSON=secretref:csrf-keys-json)
    if az containerapp show -g "$RG" -n "$APP" -o none 2>/dev/null; then
      # Redeploy: set only the settings this step owns. Flags, origins, JWT and email settings already on the app
      # (enabled by an operator or by the `email` step) are kept; `--replace-env-vars` would silently drop them.
      az containerapp update -g "$RG" -n "$APP" --image "$ACR.azurecr.io/ms-users:$SHA" \
        --revision-suffix "r${SHA:0:7}" --set-env-vars "${env_args[@]}" -o none
      # `containerapp update --tags` replaces every tag on the resource; merge only `sha` instead.
      az resource tag --ids "$(az containerapp show -g "$RG" -n "$APP" --query id -o tsv)" \
        --is-incremental --tags "sha=$SHA" -o none
    else
      # New app: every authentication flag starts false.
      for f in "${FLAGS[@]}"; do env_args+=("$f=false"); done
      az containerapp create -g "$RG" -n "$APP" --environment "$ENV" --workload-profile-name Consumption \
        --image "$ACR.azurecr.io/ms-users:$SHA" --registry-server "$ACR.azurecr.io" --registry-identity "$id" \
        --user-assigned "$id" --system-assigned \
        --secrets "csrf-keys-json=keyvaultref:https://$KV.vault.azure.net/secrets/$CSRF_SECRET,identityref:$id" \
        --env-vars "${env_args[@]}" --cpu 0.5 --memory 1Gi --min-replicas 0 --max-replicas 1 \
        --scale-rule-name http --scale-rule-type http --scale-rule-http-concurrency 20 \
        --ingress external --target-port 8080 --transport auto --revisions-mode single \
        --revision-suffix "r${SHA:0:7}" --tags "sha=$SHA" service=ms-users env=staging -o none
    fi
    # Probes: `containerapp update` keeps the probes already on the template. Only a probe type that is missing
    # (new app) gets the default below; an existing probe — e.g. a Startup probe tuned for a cold start that also
    # resumes the serverless database — is never overwritten on redeploy.
    app_id=$(az containerapp show -g "$RG" -n "$APP" --query id -o tsv)
    tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
    rc=0
    az rest --method get --url "https://management.azure.com$app_id?api-version=2024-03-01" | python3 -c '
import json,sys
d=json.load(sys.stdin); t=d["properties"]["template"]; c=t["containers"][0]
h=lambda: {"path":"/health","port":8080}
defaults=[{"type":"Startup","httpGet":h(),"initialDelaySeconds":1,"periodSeconds":3,"timeoutSeconds":5,"failureThreshold":30},
      {"type":"Readiness","httpGet":h(),"periodSeconds":15,"timeoutSeconds":5,"failureThreshold":3},
      {"type":"Liveness","httpGet":h(),"periodSeconds":30,"timeoutSeconds":5,"failureThreshold":3}]
probes=list(c.get("probes") or []); present={p.get("type") for p in probes}
missing=[p for p in defaults if p["type"] not in present]
if not missing: sys.exit(3)
c["probes"]=probes+missing; t["revisionSuffix"]=(t.get("revisionSuffix") or "r")[:20]+"p"
json.dump({"properties":{"template":t}},sys.stdout)' > "$tmp" || rc=$?
    case "$rc" in
      0) az rest --method patch --url "https://management.azure.com$app_id?api-version=2024-03-01" --body "@$tmp" -o none
         log "added missing default probes" ;;
      3) log "probes kept as configured" ;;
      *) die "could not read the container app template" ;;
    esac
    log "app $(az containerapp show -g "$RG" -n "$APP" --query properties.configuration.ingress.fqdn -o tsv)" ;;

  sql-access)
    for ip in $(az containerapp show -g "$RG" -n "$APP" --query "properties.outboundIpAddresses[]" -o tsv); do
      az sql server firewall-rule create -g "$SQL_RG" -s "$SQL_SERVER" -n "ms-users-aca-${ip//./-}" \
        --start-ip-address "$ip" --end-ip-address "$ip" -o none
    done
    pid=$(az containerapp show -g "$RG" -n "$APP" --query identity.principalId -o tsv)
    client=$(az ad sp show --id "$pid" --query appId -o tsv)
    sqlcmd -S "$SQL_SERVER.database.windows.net" -d "$SQL_DB" --authentication-method ActiveDirectoryAzCli -l 60 -W -b \
      -v RUNTIME_PRINCIPAL="$APP" RUNTIME_CLIENT_ID="$client" -i "$ROOT/scripts/azure/sql/grant-runtime-identity.sql"
    # A revision activated before SQL access existed stays ActivationFailed (a restart does not
    # recover it), so roll a fresh revision with the same template.
    az containerapp update -g "$RG" -n "$APP" --revision-suffix "s$(date +%s)" -o none
    log "new revision $(az containerapp show -g "$RG" -n "$APP" --query properties.latestRevisionName -o tsv)" ;;

  verify)
    fqdn=$(az containerapp show -g "$RG" -n "$APP" --query properties.configuration.ingress.fqdn -o tsv)
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 240 "https://$fqdn/health"); echo "GET /health $code"
    [[ "$code" == 200 ]] || die "/health is not 200"
    for p in /v1/auth/attempts /v1/auth/google /v1/auth/refresh /v1/auth/logout /v1/auth/email/requests; do
      c=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' "https://$fqdn$p")
      echo "POST $p $c"; [[ "$c" == 404 ]] || die "$p is reachable"
    done
    for p in /v1/me/profile /.well-known/jwks.json /actuator/health; do
      c=$(curl -s -o /dev/null -w '%{http_code}' "https://$fqdn$p"); echo "GET $p $c"; [[ "$c" == 404 ]] || die "$p is reachable"
    done
    n=$(az containerapp show -g "$RG" -n "$APP" --query "length(properties.template.containers[0].env[?(ends_with(name,'_ENABLED') || ends_with(name,'_VERIFIED')) && value=='false'])" -o tsv)
    echo "auth flags false: $n/${#FLAGS[@]}"; [[ "$n" == "${#FLAGS[@]}" ]] || die "auth flags not all false"
    az containerapp secret list -g "$RG" -n "$APP" --query "[].{name:name,keyVaultUrl:keyVaultUrl}" -o table ;;

  email)
    # Magic Link via an HTTPS JSON mail API (Bearer key; body {from,to[],subject,text}, e.g. Resend
    # https://api.resend.com/emails). The key must already be in Key Vault secret $EMAIL_SECRET; it is never
    # read or printed here. Inputs (non-secret):
    #   MTEC_EMAIL_PROVIDER_ENDPOINT    https endpoint of the provider
    #   MTEC_EMAIL_FROM                 sender authorized by the provider (verified domain or address)
    #   MTEC_EMAIL_LOGIN_LINK_BASE_URL  https://<SPA origin>/acceso/correo (no query or fragment)
    for v in MTEC_EMAIL_PROVIDER_ENDPOINT MTEC_EMAIL_FROM MTEC_EMAIL_LOGIN_LINK_BASE_URL; do
      [[ -n "${!v:-}" ]] || die "$v required"
    done
    [[ "$MTEC_EMAIL_PROVIDER_ENDPOINT" == https://* && "$MTEC_EMAIL_LOGIN_LINK_BASE_URL" == https://*/acceso/correo ]] \
      || die "provider endpoint and link base must be https; link base must end in /acceso/correo"
    az keyvault secret show --vault-name "$KV" -n "$EMAIL_SECRET" --query id -o none 2>/dev/null \
      || die "Key Vault secret $EMAIL_SECRET is missing (az keyvault secret set --vault-name $KV -n $EMAIL_SECRET --file <key file>)"
    id=$(uai_id)
    pid=$(az identity show -g "$RG" -n "$UAI" --query principalId -o tsv)
    az role assignment create --assignee-object-id "$pid" --assignee-principal-type ServicePrincipal \
      --role "Key Vault Secrets User" --scope "$(az keyvault show -n "$KV" --query id -o tsv)/secrets/$EMAIL_SECRET" -o none
    az containerapp secret set -g "$RG" -n "$APP" -o none \
      --secrets "email-provider-api-key=keyvaultref:https://$KV.vault.azure.net/secrets/$EMAIL_SECRET,identityref:$id"
    az containerapp update -g "$RG" -n "$APP" -o none --revision-suffix "e$(date +%s)" --set-env-vars \
      USERS_EMAIL_ENABLED=true USERS_EMAIL_PROVIDER_API_KEY=secretref:email-provider-api-key \
      "USERS_EMAIL_PROVIDER_ENDPOINT=$MTEC_EMAIL_PROVIDER_ENDPOINT" "USERS_EMAIL_FROM=$MTEC_EMAIL_FROM" \
      "USERS_EMAIL_LOGIN_LINK_BASE_URL=$MTEC_EMAIL_LOGIN_LINK_BASE_URL"
    log "Magic Link delivery enabled on $(az containerapp show -g "$RG" -n "$APP" --query properties.latestRevisionName -o tsv)" ;;

  *) sed -n '2,18p' "$0"; exit 2 ;;
esac
