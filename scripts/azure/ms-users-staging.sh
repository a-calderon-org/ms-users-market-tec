#!/usr/bin/env bash
# ms-users — Azure App Service staging operations (MTEC-37).
#
# Every Azure-mutating step is a DRY RUN unless --execute is passed; dry runs only print the
# az/sqlcmd/java command. Read-only steps (check, render-settings, verify, status) never mutate.
# No step prints secret values: the CSRF key is generated into a 0600 temp file, uploaded to
# Key Vault with `-o none` and removed. Authentication flags are never enabled by this script.
#
# Usage: scripts/azure/ms-users-staging.sh --config <file> <step> [--execute] [--sha <commit>]
# Steps (in execution order, see docs/MTEC37_AZURE_STAGING_EXECUTION.md):
#   check            read-only inventory of the configured resources
#   create-keyvault  create the RBAC Key Vault and grant the operator Secrets Officer on it
#   create-csrf-secret  generate users-csrf-keys-json (only if absent) without printing it
#   create-webapp    create the Java 21 web app on the existing plan with system identity
#   grant-keyvault   grant the web app identity Secrets User on the CSRF secret only
#   sql-firewall     allow the web app outbound IPs on the SQL server (one /32 rule each)
#   configure        apply runtime, startup command, logging and app settings (flags false)
#   package --sha S  build the JAR of a clean checkout at commit S whose CI succeeded
#   migrate-info     Flyway info against Azure SQL with the operator's Entra identity (read-only)
#   migrate          Flyway migrate (requires --confirm-database=<db> and --execute)
#   sql-grant        create the runtime SQL user and least-privilege grants
#   deploy --sha S   deploy the packaged JAR of S and tag the app with the SHA
#   verify           public HTTP checks + app settings and Key Vault reference status
#   rollback --sha S redeploy a previously packaged JAR (same as deploy)
#   stop | start     stop/start the web app
#   render-settings  print the rendered app settings (no secret values exist in them)
set -euo pipefail
set +x

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/scripts/azure"
CONFIG="" STEP="" EXECUTE=false SHA="" CONFIRM_DATABASE="" CONFIRM_V13=""
CSRF_SECRET_NAME="users-csrf-keys-json"
RUNTIME="JAVA:21-java21"
STARTUP='java $JAVA_OPTS -jar /home/site/wwwroot/app.jar --server.port=8080'
REPO_SLUG="a-calderon-org/ms-users-market-tec"
CI_WORKFLOW="qa-mtec35-sql.yml"

die() { echo "error: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config) CONFIG="${2:-}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    --sha) SHA="${2:-}"; shift 2 ;;
    --confirm-database=*) CONFIRM_DATABASE="${1#*=}"; shift ;;
    --confirm-email-attempt-invalidation) CONFIRM_V13="$1"; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [[ -z "$STEP" ]] || die "only one step per invocation"; STEP="$1"; shift ;;
  esac
done
[[ -n "$STEP" ]] || die "missing step (see --help)"
[[ -n "$CONFIG" && -f "$CONFIG" ]] || die "--config <file> is required (see ms-users-staging.env.example)"
ARTIFACT_DIR="${HOME}/.cache/markettec/ms-users-artifacts"
# Configuration is data. Never evaluate shell code or accept operation switches here.
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
  [[ "$line" == *=* ]] || die "invalid configuration line"
  name="${line%%=*}" value="${line#*=}"
  case "$name" in
    AZ_SUBSCRIPTION_ID|AZ_LOCATION|APP_RESOURCE_GROUP|APP_SERVICE_PLAN|SQL_RESOURCE_GROUP|SQL_SERVER_NAME|SQL_DATABASE_NAME|WEBAPP_NAME|KEY_VAULT_NAME|KEY_VAULT_RESOURCE_GROUP|USERS_CSRF_ACTIVE_KID|JAVA_OPTS|ARTIFACT_DIR)
      if [[ "$value" == \"*\" ]]; then value="${value:1:${#value}-2}"; fi
      export "$name=$value"
      ;;
    *) die "unsupported configuration key" ;;
  esac
done <"$CONFIG"

require_vars() {
  local name
  for name in "$@"; do [[ -n "${!name:-}" ]] || die "$name is empty in $CONFIG"; done
}
require_vars AZ_SUBSCRIPTION_ID AZ_LOCATION APP_RESOURCE_GROUP APP_SERVICE_PLAN SQL_RESOURCE_GROUP \
  SQL_SERVER_NAME SQL_DATABASE_NAME JAVA_OPTS ARTIFACT_DIR
GUID='^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
[[ "$AZ_SUBSCRIPTION_ID" =~ $GUID ]] || die "AZ_SUBSCRIPTION_ID must be a subscription GUID"
SUB=(--subscription "$AZ_SUBSCRIPTION_ID")

# Mutating command: printed in dry run, executed only with --execute.
mutate() {
  if $EXECUTE; then log "EXECUTE: $*"; "$@"; else echo "[dry-run] $*"; fi
}

need_webapp() {
  require_vars WEBAPP_NAME
  [[ "$WEBAPP_NAME" =~ ^[a-zA-Z0-9][a-zA-Z0-9-]{0,59}$ ]] || die "invalid WEBAPP_NAME"
}
need_vault() { require_vars KEY_VAULT_NAME KEY_VAULT_RESOURCE_GROUP; }
need_sha() {
  [[ "$SHA" =~ ^[0-9a-f]{40}$ ]] || die "--sha must be a full 40-hex commit SHA"
}
artifact() { echo "$ARTIFACT_DIR/ms-users-$SHA.jar"; }

verify_artifact() {
  local filename="ms-users-$SHA.jar" actual expected
  [[ -f "$(artifact)" ]] || die "JAR not found (run package --sha $SHA)"
  actual="$(cd "$ARTIFACT_DIR" && shasum -a 256 "$filename")"
  expected="$(cat "$(artifact).sha256")"
  [[ "$actual" == "$expected" ]] || die "artifact checksum mismatch for $SHA"
}

sql_token() {
  local account_type
  account_type="$(az account show "${SUB[@]}" --query user.type -o tsv)" || return
  [[ "$account_type" == user ]] || die "sign in to Azure CLI as the Entra operator"
  az account get-access-token "${SUB[@]}" --resource https://database.windows.net/ --query accessToken -o tsv
}

graph_id() {
  local token
  token="$(az account get-access-token "${SUB[@]}" --resource-type ms-graph --query accessToken -o tsv)" || return
  [[ -n "$token" ]] || die "Microsoft Graph access token unavailable"
  printf 'Authorization: Bearer %s\n' "$token" |
    curl --disable --fail --silent --show-error --header @- "https://graph.microsoft.com/v1.0/$1?\$select=$2" |
    python3 -I -c 'import json, sys; print(json.load(sys.stdin)[sys.argv[1]])' "$2"
}

render_settings() {
  require_vars SQL_SERVER_NAME SQL_DATABASE_NAME USERS_CSRF_ACTIVE_KID KEY_VAULT_NAME JAVA_OPTS
  [[ "$USERS_CSRF_ACTIVE_KID" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || die "USERS_CSRF_ACTIVE_KID must match [A-Za-z0-9_-]{1,32}"
  python3 -I - "$HERE/appsettings.staging.json" <<'PY'
import json, os, re, sys
template = json.load(open(sys.argv[1]))
def render(value):
    def sub(match):
        name = match.group(1)
        if not os.environ.get(name):
            raise SystemExit(f"error: {name} is required by appsettings.staging.json")
        return os.environ[name]
    return re.sub(r"\$\{([A-Z_]+)\}", sub, value)
print(json.dumps([{"name": k, "value": render(v), "slotSetting": False} for k, v in template.items()], indent=2))
PY
}

ci_succeeded() {
  command -v gh >/dev/null || die "gh CLI is required to check CI for $SHA"
  local runs
  runs="$(gh run list -R "$REPO_SLUG" --workflow "$CI_WORKFLOW" --commit "$SHA" --status success \
    --json databaseId --jq 'length')"
  [[ "${runs:-0}" -ge 1 ]] || die "no successful '$CI_WORKFLOW' run for $SHA (normal + 65 SQL cases required)"
}

case "$STEP" in
  check)
    log "account"; az account show "${SUB[@]}" --query "{subscription:name,state:state,user:user.name}" -o table
    log "plan"; az appservice plan show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$APP_SERVICE_PLAN" \
      --query "{sku:sku.name,tier:sku.tier,linux:reserved,apps:numberOfSites,location:location,status:status}" -o table
    log "sql server"; az sql server show "${SUB[@]}" -g "$SQL_RESOURCE_GROUP" -n "$SQL_SERVER_NAME" \
      --query "{fqdn:fullyQualifiedDomainName,minTls:minimalTlsVersion,entraOnly:administrators.azureAdOnlyAuthentication,publicAccess:publicNetworkAccess}" -o table
    log "sql database"; az sql db show "${SUB[@]}" -g "$SQL_RESOURCE_GROUP" -s "$SQL_SERVER_NAME" -n "$SQL_DATABASE_NAME" \
      --query "{sku:sku.name,status:status,autoPauseMinutes:autoPauseDelay,freeLimit:useFreeLimit,onFreeLimitExhausted:freeLimitExhaustionBehavior}" -o table
    log "sql firewall rules"; az sql server firewall-rule list "${SUB[@]}" -g "$SQL_RESOURCE_GROUP" -s "$SQL_SERVER_NAME" \
      --query "[].{name:name,start:startIpAddress,end:endIpAddress}" -o table
    if [[ -n "${WEBAPP_NAME:-}" ]]; then
      log "web app"; az webapp show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" \
        --query "{state:state,host:defaultHostName,httpsOnly:httpsOnly,identity:identity.type,runtime:siteConfig.linuxFxVersion,deployedSha:tags.deployedSha}" -o table \
        2>/dev/null || echo "web app $WEBAPP_NAME: not found"
    fi
    if [[ -n "${KEY_VAULT_NAME:-}" ]]; then
      log "key vault"; az keyvault show "${SUB[@]}" -n "$KEY_VAULT_NAME" \
        --query "{rbac:properties.enableRbacAuthorization,softDeleteDays:properties.softDeleteRetentionInDays,publicAccess:properties.publicNetworkAccess}" -o table \
        2>/dev/null || echo "key vault $KEY_VAULT_NAME: not found"
    fi
    ;;

  render-settings)
    render_settings ;;

  create-keyvault)
    need_vault
    operator_id="<operator-object-id>"
    if $EXECUTE; then
      operator_id="$(graph_id me id)"
      [[ "$operator_id" =~ $GUID ]] || die "signed-in Entra user not found"
    fi
    mutate az keyvault create "${SUB[@]}" -g "$KEY_VAULT_RESOURCE_GROUP" -n "$KEY_VAULT_NAME" -l "$AZ_LOCATION" \
      --sku standard --enable-rbac-authorization true --retention-days 7 -o none
    vault_scope="/subscriptions/$AZ_SUBSCRIPTION_ID/resourceGroups/$KEY_VAULT_RESOURCE_GROUP/providers/Microsoft.KeyVault/vaults/$KEY_VAULT_NAME"
    mutate az role assignment create "${SUB[@]}" --assignee-object-id "$operator_id" --assignee-principal-type User \
      --role "Key Vault Secrets Officer" --scope "$vault_scope" -o none
    ;;

  create-csrf-secret)
    need_vault; require_vars USERS_CSRF_ACTIVE_KID
    [[ "$USERS_CSRF_ACTIVE_KID" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || die "invalid USERS_CSRF_ACTIVE_KID"
    if [[ "$EXECUTE" == false ]]; then
      echo "[dry-run] generate {\"$USERS_CSRF_ACTIVE_KID\": <32 random bytes, base64>} into a 0600 temp file"
      echo "[dry-run] az keyvault secret set --subscription $AZ_SUBSCRIPTION_ID --vault-name $KEY_VAULT_NAME -n $CSRF_SECRET_NAME --file <temp> -o none"
      exit 0
    fi
    existing="$(az keyvault secret list "${SUB[@]}" --vault-name "$KEY_VAULT_NAME" \
      --query "[?name=='$CSRF_SECRET_NAME'].name" -o tsv)"
    [[ -z "$existing" ]] || die "$CSRF_SECRET_NAME already exists; rotation requires separate authorization"
    umask 077
    secret_file="$(mktemp)"; trap 'rm -f "$secret_file"' EXIT
    key="$(openssl rand -base64 32)"
    printf '{"%s":"%s"}' "$USERS_CSRF_ACTIVE_KID" "$key" >"$secret_file"; unset key
    log "EXECUTE: az keyvault secret set --vault-name $KEY_VAULT_NAME -n $CSRF_SECRET_NAME --file <temp> -o none"
    az keyvault secret set "${SUB[@]}" --vault-name "$KEY_VAULT_NAME" -n "$CSRF_SECRET_NAME" \
      --file "$secret_file" --content-type application/json -o none
    ;;

  create-webapp)
    need_webapp
    mutate az webapp create "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -p "$APP_SERVICE_PLAN" -n "$WEBAPP_NAME" \
      --runtime "$RUNTIME" --assign-identity "[system]" --https-only true --basic-auth Disabled -o none
    ;;

  grant-keyvault)
    need_webapp; need_vault
    principal="$(az webapp identity show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" --query principalId -o tsv 2>/dev/null || true)"
    if [[ -z "$principal" ]]; then $EXECUTE && die "web app has no system identity yet"; principal="<webapp-principal-id>"; fi
    secret_scope="/subscriptions/$AZ_SUBSCRIPTION_ID/resourceGroups/$KEY_VAULT_RESOURCE_GROUP/providers/Microsoft.KeyVault/vaults/$KEY_VAULT_NAME/secrets/$CSRF_SECRET_NAME"
    mutate az role assignment create "${SUB[@]}" --assignee-object-id "$principal" --assignee-principal-type ServicePrincipal \
      --role "Key Vault Secrets User" --scope "$secret_scope" -o none
    ;;

  sql-firewall)
    need_webapp
    ips="$(az webapp show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" --query outboundIpAddresses -o tsv 2>/dev/null || true)"
    if [[ -z "$ips" ]]; then $EXECUTE && die "web app outbound IP addresses unavailable"; ips="<outboundIpAddresses>"; fi
    i=0
    for ip in ${ips//,/ }; do
      if $EXECUTE; then
        [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ && "$ip" != 0.0.0.0 ]] || die "invalid outbound IPv4 address"
      fi
      i=$((i + 1))
      mutate az sql server firewall-rule create "${SUB[@]}" -g "$SQL_RESOURCE_GROUP" -s "$SQL_SERVER_NAME" \
        -n "ms-users-stg-outbound-$i" --start-ip-address "$ip" --end-ip-address "$ip" -o none
    done
    ;;

  configure)
    need_webapp
    settings_file="$(mktemp)"; trap 'rm -f "$settings_file"' EXIT
    render_settings >"$settings_file"
    mutate az webapp config set "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" \
      --linux-fx-version "JAVA|21-java21" --startup-file "$STARTUP" --always-on false \
      --ftps-state Disabled --min-tls-version 1.2 --http20-enabled true -o none
    mutate az webapp log config "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" \
      --docker-container-logging filesystem -o none
    mutate az webapp config appsettings set "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" \
      --settings "@$settings_file" -o none
    ;;

  package)
    need_sha
    [[ -z "$(git -C "$ROOT" status --porcelain)" ]] || die "working tree is not clean"
    [[ "$(git -C "$ROOT" rev-parse HEAD)" == "$SHA" ]] || die "HEAD is not $SHA (check out that commit first)"
    ci_succeeded
    (cd "$ROOT" && ./mvnw --batch-mode --no-transfer-progress -DskipTests clean package)
    mkdir -p "$ARTIFACT_DIR"
    cp "$ROOT/target/ms-users-0.1.0-SNAPSHOT.jar" "$(artifact)"
    (cd "$ARTIFACT_DIR" && shasum -a 256 "ms-users-$SHA.jar" | tee "ms-users-$SHA.jar.sha256")
    ;;

  migrate-info|migrate)
    if [[ "$STEP" == migrate ]]; then
      [[ "$CONFIRM_DATABASE" == "$SQL_DATABASE_NAME" ]] || die "migrate requires --confirm-database matching SQL_DATABASE_NAME"
    fi
    need_sha; verify_artifact
    command=(java -Dloader.main=com.markettec.users.adapters.out.sql.SqlMigrationRunner -cp "$(artifact)"
      org.springframework.boot.loader.launch.PropertiesLauncher)
    export USERS_SQL_SERVER="$SQL_SERVER_NAME.database.windows.net" USERS_SQL_DATABASE="$SQL_DATABASE_NAME"
    if [[ "$STEP" == migrate-info ]]; then
      log "read-only: Flyway info (operator Entra identity, resumes a paused serverless database)"
      sql_token | "${command[@]}" info
    else
      command+=(migrate "--confirm-database=$CONFIRM_DATABASE")
      if [[ -n "$CONFIRM_V13" ]]; then command+=("$CONFIRM_V13"); fi
      if $EXECUTE; then
        log "EXECUTE: Flyway migrate for $SQL_DATABASE_NAME"
        sql_token | "${command[@]}"
      else
        mutate "${command[@]}"
      fi
    fi
    ;;

  sql-grant)
    need_webapp
    sqlcmd="${SQLCMD_ODBC:-sqlcmd}"
    client_id="<webapp-client-id>"
    token_file="<token-file>"
    if $EXECUTE; then
      command -v "$sqlcmd" >/dev/null || die "sqlcmd (ODBC) is required"
      help="$("$sqlcmd" '-?' 2>&1 || true)"
      [[ "$help" == *"SQL Server Command Line Tool"* ]] || die "set SQLCMD_ODBC to sqlcmd (ODBC)"
      principal="$(az webapp identity show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" --query principalId -o tsv)"
      [[ "$principal" =~ $GUID ]] || die "web app has no system identity"
      client_id="$(graph_id "servicePrincipals/$principal" appId)"
      [[ "$client_id" =~ $GUID ]] || die "web app client ID not found"
      umask 077
      token_file="$(mktemp)"; trap 'rm -f "$token_file"' EXIT
      sql_token | tr -d '\r\n' | iconv -f UTF-8 -t UTF-16LE >"$token_file"
      [[ -s "$token_file" ]] || die "SQL access token unavailable"
    fi
    mutate "$sqlcmd" -S "$SQL_SERVER_NAME.database.windows.net" -d "$SQL_DATABASE_NAME" -N \
      -G -P "$token_file" -X1 -b -v "RUNTIME_PRINCIPAL=$WEBAPP_NAME" "RUNTIME_CLIENT_ID=$client_id" \
      -i "$HERE/sql/grant-runtime-identity.sql"
    ;;

  deploy|rollback)
    need_webapp; need_sha; verify_artifact; ci_succeeded
    jar="$(artifact)"
    previous="$(az webapp show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" --query tags.deployedSha -o tsv)"
    log "currently deployed SHA: ${previous:-none}; target: $SHA"
    mutate az webapp deploy "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" \
      --src-path "$jar" --type jar --restart true --track-status false -o none
    mutate az webapp update "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" \
      --set "tags.deployedSha=$SHA" "tags.previousSha=${previous:-none}" -o none
    ;;

  stop|start)
    need_webapp
    mutate az webapp "$STEP" "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME"
    ;;

  verify)
    need_webapp
    host="$(az webapp show "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" --query defaultHostName -o tsv)"
    log "app settings: flags and secret references (values of non-flag settings are not printed)"
    az webapp config appsettings list "${SUB[@]}" -g "$APP_RESOURCE_GROUP" -n "$WEBAPP_NAME" -o json | python3 -I -c '
import json, sys
settings = {s["name"]: s["value"] for s in json.load(sys.stdin)}
template = json.load(open(sys.argv[1]))
bad = []
for name, value in template.items():
    if value == "false" and settings.get(name) != "false":
        bad.append(name)
if settings.get("SPRING_PROFILES_ACTIVE") != "sql-appservice":
    bad.append("SPRING_PROFILES_ACTIVE")
if not settings.get("USERS_CSRF_KEYS_JSON", "").startswith("@Microsoft.KeyVault("):
    bad.append("USERS_CSRF_KEYS_JSON (must be a Key Vault reference)")
print("flags false and Key Vault reference present" if not bad else "MISMATCH: " + ", ".join(bad))
sys.exit(1 if bad else 0)' "$HERE/appsettings.staging.json"
    log "Key Vault reference resolution status"
    status="$(az rest "${SUB[@]}" --method get --uri "https://management.azure.com/subscriptions/$AZ_SUBSCRIPTION_ID/resourceGroups/$APP_RESOURCE_GROUP/providers/Microsoft.Web/sites/$WEBAPP_NAME/config/configreferences/appsettings/USERS_CSRF_KEYS_JSON?api-version=2023-12-01" \
      --query properties.status -o tsv)"
    [[ "$status" == Resolved ]] || die "Key Vault reference is unresolved"
    log "HTTP checks against https://$host"
    python3 -I "$HERE/verify_http.py" "https://$host"
    ;;

  *) die "unknown step $STEP (see --help)" ;;
esac
