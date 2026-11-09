# MTEC-37 — Ejecución de ms-users en Azure App Service (staging)

**Fecha:** 2026-11-08. **Base:** `main@08ccf056c975f6ec5d918c4cf76143fb04def7b2`.
**Estado:** preparado para despliegue controlado. **ms-users no está desplegado:** ningún recurso Azure se creó ni se modificó en esta intervención.

Este documento sustituye, para la ejecución, a las secciones operativas de [MTEC37_DEPLOYMENT_READINESS.md](MTEC37_DEPLOYMENT_READINESS.md), que pasa a ser referencia histórica (reconstrucción del 2026-11-01). Los flags y sus dependencias siguen definidos en [MS_USERS_ACTIVATION_MANIFEST.md](MS_USERS_ACTIVATION_MANIFEST.md).

Etiquetas de certeza:

- **Código:** implementado y probado en este repositorio.
- **Azure (lectura):** observado con `az` CLI en modo solo lectura el 2026-11-08.
- **Pendiente:** requiere ejecución o validación real en Azure, con autorización.
- **Documentado, no verificado:** proviene de documentación pública de Microsoft y no se comprobó en esta suscripción.

## 1. Suscripción y recursos

`az` CLI tenía una sesión válida (cuenta de estudiante del tenant TEC). Existen dos suscripciones llamadas «Azure for Students». **La predeterminada en la CLI del operador no es la que contiene el plan ni SQL**, por eso el script pasa siempre `--subscription`.

| Recurso | Suscripción | Estado | Certeza |
|---|---|---|---|
| Plan `ASP-acalderonTEC-9c69` (RG `acalderon-TEC`, `canadacentral`) | `26db4383-…3ac17` | Linux, **Free F1**, 1 worker, 0 apps, `Ready` | Azure (lectura) |
| Servidor SQL `sql-markettec-dev` (RG `a-calderon-org`, `canadacentral`) | `26db4383-…` | TLS mínimo 1.2, **solo autenticación Entra**, administrador Entra de tipo usuario (el operador), acceso público habilitado | Azure (lectura) |
| Base `sqldb-ms-users-dev` | `26db4383-…` | `GP_S_Gen5` serverless, 0,5–2 vCore, pausa a los 60 min, **oferta gratuita activa** (`useFreeLimit=true`, `AutoPause` al agotarse), backup local, `Paused` | Azure (lectura) |
| Firewall SQL | `26db4383-…` | Una única regla /32 creada el 2026-10-31 para la IP del operador | Azure (lectura) |
| APIM `a-calderon-api-management` (RG `acalderon-TEC`) | `26db4383-…` | Consumption, `Succeeded`, sin identidad; API `products-api-v1` | Azure (lectura) |
| APIM `markettec-api` (RG `markettec-dev`, `westus2`) | `904ab275-…` | Consumption, API `markettec-api-v1`; **no** existe `markettec-users-v1` | Azure (lectura) |
| Static Web App `markettec` | `904ab275-…` | Free | Azure (lectura) |
| Identidades `oidc-msi-*` (4) | `26db4383-…` | Federadas con `Allan587/simple-react-app` rama `dev`, sin roles. **No pertenecen a MarketTec**; no se reutilizan | Azure (lectura) |
| Permisos del operador | `26db4383-…` | `Owner` de la suscripción | Azure (lectura) |

**Faltan:** App Service para ms-users, Key Vault (no hay ninguno, tampoco eliminados), identidad runtime, reglas de firewall para App Service, usuario SQL runtime, federación OIDC para despliegue desde GitHub y API `markettec-users-v1` en APIM.

**No determinado:** si el esquema de `sqldb-ms-users-dev` ya tiene migraciones aplicadas. La base está pausada y consultarla la reanuda y consume cómputo, por lo que no se consultó.

## 2. Arquitectura propuesta

```
Internet ─HTTPS─> <webapp>.azurewebsites.net (App Service Linux F1, Java SE 21)
                    │  JAR Spring Boot, :8080; Actuator solo en 127.0.0.1:9091
                    │  identidad administrada asignada por el sistema
                    ├──> Key Vault (RBAC): users-csrf-keys-json  [Key Vault Secrets User sobre ese secreto]
                    └──TLS 1.2, Entra (ActiveDirectoryManagedIdentity)──> sql-markettec-dev / sqldb-ms-users-dev
                                                                         usuario runtime: SELECT/INSERT/UPDATE en dbo
Operador (az login, admin Entra de SQL) ──> SqlMigrationRunner (Flyway) ──> misma base (DDL e historial)
```

Todo en `canadacentral` y en la suscripción `26db4383-…`. La web app va en `acalderon-TEC` junto al plan reutilizado. APIM, la SWA y la exposición pública de autenticación quedan fuera de este incremento.

## 3. Runtime y plan

| Tema | Decisión | Certeza |
|---|---|---|
| Runtime | `JAVA|21-java21` (Java SE, Linux). Está en el catálogo con soporte `Active` hasta 2028-09-01 | Azure (lectura) |
| Empaquetado | El JAR ejecutable de Spring Boot existente (`ms-users-0.1.0-SNAPSHOT.jar`). No se cambia framework, Java ni empaquetado | Código |
| Comando de inicio | `java $JAVA_OPTS -jar /home/site/wwwroot/app.jar --server.port=8080`. `az webapp deploy --type jar` publica el archivo como `app.jar`. El argumento de línea de comandos fija el puerto aunque la plataforma inyecte `PORT` o `SERVER_PORT` | Código; Pendiente (comprobar en Azure) |
| Puerto | `WEBSITES_PORT=8080`. App Service termina TLS y reenvía al contenedor | Documentado, no verificado |
| Memoria | F1: 1 GB para todo el plan. `JAVA_OPTS`: `-Xms64m -Xmx384m -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError`. El JIT C1 y SerialGC reducen los minutos de CPU que consume el arranque | Código; RSS real Pendiente |
| Logs | JSON por stdout (`SafeJsonLogFormatter`), recogidos con `az webapp log config --docker-container-logging filesystem` | Código; Pendiente |
| Límites de F1 | 60 min de CPU al día, sin Always On (se descarga tras inactividad y cada visita posterior arranca de nuevo la JVM), sin slots, sin integración VNet, sin SLA | Documentado, no verificado |

Con flags apagados, el puerto principal expone solo `/health` (200, sin SQL) y el fallback `404 ROUTE_NOT_FOUND`. `/health` sigue siendo una prueba de proceso: **no** se convierte en sonda SQL.

## 4. Conectividad SQL y permisos

**Runtime (código existente, sin cambios).** El perfil `sql-appservice` usa exclusivamente `ActiveDirectoryManagedIdentity` (identidad asignada por el sistema), `encrypt=true`, `trustServerCertificate=false` y `hostNameInCertificate=*.database.windows.net`. No admite contraseña, usuario SQL ni URL JDBC concatenada, y no ejecuta migraciones. En el arranque, `SqlSchemaValidator` comprueba el nombre de la base, el historial Flyway (`validate`), las claves primarias, los índices y las FK. **Si la aplicación arranca, la conectividad y el esquema se validaron en ese momento.**

**Migraciones (nuevo, código).** `SqlMigrationRunner` es un punto de entrada para el operador, fuera de Spring y nunca invocado por el runtime. Se ejecuta desde el mismo JAR:

```bash
java -Dloader.main=com.markettec.users.adapters.out.sql.SqlMigrationRunner \
  -cp ms-users-<sha>.jar org.springframework.boot.loader.launch.PropertiesLauncher info
```

- Se autentica con `ActiveDirectoryDefault`, es decir, con la sesión `az login` del operador (administrador Entra), y **nunca** con la identidad runtime.
- Solo admite `info` (lectura) y `migrate --confirm-database=<USERS_SQL_DATABASE>`. Rechaza `clean`, `repair`, `baseline` y cualquier argumento desconocido. Exige un host `*.database.windows.net` y comprueba `DB_NAME()` antes de actuar.
- Usa Flyway con `cleanDisabled`, sin baseline, sin migraciones fuera de orden y sin crear esquemas, sobre las mismas migraciones `V1…V13` que valida el runtime.
- Solo imprime el nombre de la base, las versiones y su estado; ante un error, solo el tipo de excepción.

**Permisos runtime.** [`scripts/azure/sql/grant-runtime-identity.sql`](../scripts/azure/sql/grant-runtime-identity.sql) hace lo siguiente:

- Crea `[<webapp>] FROM EXTERNAL PROVIDER`.
- Concede `SELECT, INSERT, UPDATE ON SCHEMA::dbo`. El código no ejecuta `DELETE` ni DDL; se comprobó con `grep` sobre `src/main/java`.
- Deniega `INSERT, UPDATE, DELETE, ALTER` sobre `dbo.flyway_schema_history`.
- No asigna ningún rol (`db_owner`, `db_datawriter`, etc.).
- Aborta si todavía no existe el historial de migraciones.
- Imprime los roles y permisos resultantes como evidencia.

**Pendiente:** confirmar en Azure que la visibilidad de metadatos que da `SELECT` basta para las consultas de `sys.indexes` y `sys.foreign_keys` del validador. Si no basta, el arranque falla cerrado y la corrección es `GRANT VIEW DEFINITION ON SCHEMA::dbo`.

**Red.** F1 no admite integración VNet ni endpoint privado. La conectividad requiere una regla de firewall /32 por cada IP de `possibleOutboundIpAddresses` de la app (paso `sql-firewall`). Riesgos aceptados para staging:

- Esas IP son compartidas con otros clientes del mismo stamp de App Service. La autenticación sigue siendo exclusivamente Entra.
- La lista de IP puede cambiar si se cambia de plan.

**No** se habilita «Allow Azure services» (`0.0.0.0`).

**Coste en SQL serverless.** Cada arranque de la JVM, incluido cada arranque en frío de F1 sin Always On, valida el esquema y reanuda la base. La sonda de readiness interna (loopback, caché de 30 s) no se usa como health check de App Service.

## 5. Identidad administrada y Key Vault

| Secreto o valor | Dónde | Necesario ahora |
|---|---|---|
| `USERS_CSRF_KEYS_JSON` | Secreto `users-csrf-keys-json`, referenciado como `@Microsoft.KeyVault(VaultName=<kv>;SecretName=users-csrf-keys-json)` | **Sí.** `SqlUserConfig.csrfKeys` lo exige con cualquier perfil SQL (verificado en código y arrancando el JAR, ver §9) |
| `USERS_CSRF_ACTIVE_KID` | App setting en claro: es un identificador, no un secreto | Sí; lo elige el operador |
| `USERS_INTERNAL_JWT_KEYS_JSON`, issuer, audience, JWKS URI | Reservado: `users-internal-jwt-keys-json` | **No**: `users.internal-jwt.enabled=false`. No se crea ni se referencia; issuer y audience están pendientes de D-13 |
| `USERS_EMAIL_PROVIDER_API_KEY` | — | No (correo prohibido, D-02) |

- El Key Vault usa RBAC. El operador recibe `Key Vault Secrets Officer` sobre el vault (su rol `Owner` no da acceso al plano de datos con RBAC).
- La identidad de la app recibe `Key Vault Secrets User` **solo sobre el secreto CSRF**.
- La clave se genera en el momento con `openssl rand -base64 32` (32 bytes, dentro del rango 32–64 de `CsrfKeys`). Se escribe en un archivo temporal con permisos 0600, se sube con `-o none` y se borra. **Nunca se imprime ni se guarda en el repositorio.**
- Si la referencia no se resuelve, App Service entrega el literal `@Microsoft.KeyVault(...)` y el arranque falla cerrado (§9, caso C). El paso `verify` muestra el estado de resolución sin mostrar valores.

## 6. Variables de entorno (sin secretos)

Fuente declarativa: [`scripts/azure/appsettings.staging.json`](../scripts/azure/appsettings.staging.json). Se renderiza con `ms-users-staging.sh render-settings`.

| Variable | Valor |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `sql-appservice` |
| `WEBSITES_PORT` | `8080` |
| `JAVA_OPTS` | ver §3 |
| `USERS_SQL_SERVER` / `USERS_SQL_DATABASE` / `USERS_SQL_PORT` / `USERS_SQL_POOL_SIZE` | `sql-markettec-dev.database.windows.net` / `sqldb-ms-users-dev` / `1433` / `3` |
| `USERS_CSRF_ACTIVE_KID` | elegido por el operador (`[A-Za-z0-9_-]{1,32}`) |
| `USERS_CSRF_KEYS_JSON` | referencia de Key Vault |
| Los 13 flags `USERS_*` con valor por defecto `false`, incluidos `USERS_SESSION_HTTP_HTTPS_TOPOLOGY_VERIFIED` y `USERS_GOOGLE_HTTP_SAME_SITE_VERIFIED` | `false`, explícitos |

`scripts/tests/test_azure_staging_config.py` falla si:

- algún flag `${USERS_*:false}` de `application.yml` no aparece como `false`;
- algún nombre de tipo clave o secreto no es una referencia de Key Vault;
- aparece material con aspecto de clave;
- aparecen valores JWT o de correo;
- `verify_http.py` deja de cubrir alguna ruta del código.

## 7. Migraciones y requisitos previos

1. CI `ms-users CI` en verde para el SHA que se va a desplegar, con los casos normales y los 65 SQL Server nativos. El paso `package` lo comprueba con `gh run list`.
2. Conocer el estado real de la base: ejecutar `migrate-info` (solo lectura; reanuda la base). Ante cualquiera de estas situaciones, detenerse:
   - tablas sin historial (Flyway rechaza un esquema no vacío sin baseline);
   - checksums distintos;
   - versiones desconocidas.
3. Con autorización, ejecutar `migrate --execute`. Después, `info` debe mostrar `pending=0` y `current_version=13`.
4. Ejecutar `sql-grant --execute` **después** de la migración. El script SQL exige que exista el historial.
5. No hay rollback de esquema mediante `DROP`. Un defecto se corrige con una migración aditiva revisada.

La IP del operador debe estar en el firewall SQL. Existe una regla del 2026-10-31; si la IP cambió, añadir la regla es una operación que requiere autorización (§12).

## 8. Despliegue reproducible

Todo pasa por [`scripts/azure/ms-users-staging.sh`](../scripts/azure/ms-users-staging.sh):

- Los pasos que modifican Azure son **simulacros** salvo que se pase `--execute`.
- `check`, `render-settings`, `migrate-info` y `verify` solo leen.

La configuración se toma de una copia de [`ms-users-staging.env.example`](../scripts/azure/ms-users-staging.env.example), que no contiene secretos. Los nombres de la web app y del Key Vault y el `kid` CSRF quedan a elección del operador; este documento no los inventa.

```bash
CFG=scripts/azure/.env    # ignorado por .gitignore
S=scripts/azure/ms-users-staging.sh
$S --config $CFG check
$S --config $CFG create-keyvault --execute
$S --config $CFG create-csrf-secret --execute
$S --config $CFG create-webapp --execute
$S --config $CFG grant-keyvault --execute
$S --config $CFG sql-firewall --execute
git checkout --detach <sha> && $S --config $CFG package --sha <sha>   # checkout limpio
$S --config $CFG migrate-info --sha <sha>
$S --config $CFG migrate --sha <sha> --execute
$S --config $CFG sql-grant --execute
$S --config $CFG configure --execute
$S --config $CFG deploy --sha <sha> --execute
$S --config $CFG verify
```

El artefacto se guarda fuera del repositorio (`ARTIFACT_DIR`), con nombre `ms-users-<sha>.jar` y su `.sha256`. `deploy` verifica el hash y etiqueta la app con `deployedSha` y `previousSha`.

**No se añadió un workflow de despliegue.** El CI existente valida el SHA, y un despliegue desde GitHub requeriría crear una identidad federada y un rol sobre la app, operaciones que no están autorizadas. Cuando se autoricen, el siguiente paso natural es un workflow `workflow_dispatch` con `environment` protegido que reutilice este script.

## 9. Verificaciones

**Ejecutadas localmente en esta intervención** (JDK 24 compilando con `--release 21`; no hay JDK 21 local; el CI usa Temurin 21):

| Comprobación | Resultado |
|---|---|
| `./mvnw clean package` | 397 pruebas, 0 fallos (392 previas y 5 nuevas de `SqlMigrationRunnerTest`). `verify-mtec35-reports.py --normal-only` pasa con `normal_count=397` |
| `python3 -m unittest discover -s scripts/tests` | 18 pruebas OK (12 previas y 6 nuevas) |
| Smoke del JAR, perfil `local` (`smoke-mtec37-jar.py`) | Todas las comprobaciones HTTP pasan: `/health` 200; `/actuator/*`, `/readyz` y `/livez` 404 en el puerto principal; liveness y readiness 200 en loopback; env y metrics 404. El script marca FAIL solo porque el JDK 24 imprime avisos `sun.misc.Unsafe` que no son JSON; con Java 21 no aparecen (no verificado aquí) |
| Runner desde el JAR (`PropertiesLauncher`) | Sin variables: exit 2. `clean`: exit 2. `migrate` sin confirmación: exit 2. Host inexistente: exit 1 con solo `CannotGetJdbcConnectionException` |
| Arranque `sql-appservice` sin destino SQL / sin claves CSRF / con referencia de Key Vault sin resolver / con configuración válida pero sin identidad administrada | Los cuatro casos terminan con exit 1. Solo registran `exception_type=UnsatisfiedDependencyException`, y la salida no contiene la clave, el host ni la referencia |

**Limitación de diagnóstico.** Ante un fallo de arranque, el log solo muestra el tipo de excepción externa, siempre el mismo. Para distinguir la causa:

1. Ejecutar `verify`, que comprueba los app settings y el estado de las referencias de Key Vault.
2. Comprobar las reglas de firewall con `check`.
3. Comprobar el usuario SQL con la evidencia de `sql-grant`.

Ampliar el formatter de logs queda fuera de este incremento.

**En Azure, tras el despliegue (Pendiente):** `verify`

- Comprueba los app settings: los flags están en `false`, el perfil es `sql-appservice` y `USERS_CSRF_KEYS_JSON` es una referencia de Key Vault. Los valores no se imprimen.
- Comprueba el estado de resolución de la referencia de Key Vault.
- Ejecuta [`verify_http.py`](../scripts/azure/verify_http.py):
  - `/health` responde 200, con reintentos para el arranque en frío de F1.
  - Las 18 rutas de autenticación, perfil y JWKS del código, más `/actuator/*`, `/readyz` y `/livez`, responden 404 sin `Set-Cookie` ni CORS aunque se envíe `Origin`.
  - `http://` redirige a `https://`.

Que `/health` responda 200 con el perfil `sql-appservice` demuestra a la vez:

- que el proceso arrancó;
- que el perfil cargó;
- que la identidad administrada obtuvo un token;
- que SQL era accesible;
- que el esquema estaba migrado y validado en el arranque.

Opcionalmente, desde `az webapp ssh`: `curl -s 127.0.0.1:9091/actuator/health/readiness` (loopback; no se publica). Logs de arranque: `az webapp log tail`.

## 10. Rollback

| Situación | Acción |
|---|---|
| Versión defectuosa | `rollback --sha <previousSha> --execute`. Vuelve a desplegar el JAR conservado en `ARTIFACT_DIR` y verifica su hash. `previousSha` está en las etiquetas de la app |
| Configuración defectuosa | Volver a ejecutar `configure` con la configuración anterior. Un flag se revierte poniéndolo en `false` y reiniciando (manifiesto, regla 5) |
| Detener el servicio | `stop --execute` (F1 no factura; detiene también la reanudación de SQL) |
| Esquema | Sin `DROP` ni edición de migraciones aplicadas. Solo migraciones aditivas o restauración planificada (backup local, ventana y pérdida de escrituras acordadas) |
| Recursos nuevos | Eliminarlos (web app, Key Vault y reglas `ms-users-stg-outbound-*`) solo con autorización explícita. El Key Vault queda 7 días en borrado suave |

Un JAR anterior solo es compatible si no depende de migraciones posteriores a las aplicadas. Antes de revertir, comparar `current_version` con las migraciones del SHA de destino.

## 11. Costes

| Concepto | Coste | Certeza |
|---|---|---|
| App Service F1 | Plan gratuito; 1 app en un plan con 0 apps | Azure (lectura) del SKU; precio 0 documentado, no verificado |
| Azure SQL serverless | Con oferta gratuita activa y `AutoPause` al agotarse, no debería facturar cómputo. El consumo real depende de los arranques y de la actividad | Configuración: Azure (lectura). Consumo: **no determinado** (`az consumption usage list` devuelve `PretaxCost: None`) |
| Key Vault Standard | Por operación (céntimos por cada 10 000 operaciones); las referencias se cachean y refrescan cada 24 h | Documentado, no verificado |
| Reglas de firewall, identidad administrada, RBAC | Sin coste | Documentado, no verificado |
| Logs en filesystem | Sin coste (cuota del plan) | Documentado, no verificado |
| APIM Consumption | No interviene en este incremento | — |
| Crédito restante de Azure for Students | **No determinado** por CLI | — |

Ningún paso activa servicios de pago: no hay Application Insights, Log Analytics, B1, VNet ni endpoints privados.

## 12. Operaciones de Azure que requieren autorización

Todas en la suscripción `26db4383-add6-4e47-9ca1-3b208853ac17`, región `canadacentral`. Ninguna se ejecutó.

| # | Paso del script | Operación exacta |
|---|---|---|
| 1 | `create-keyvault` | `az keyvault create -g acalderon-TEC -n <kv> --sku standard --enable-rbac-authorization true --retention-days 7` |
| 2 | `create-keyvault` | `az role assignment create --role "Key Vault Secrets Officer"` del operador sobre `<kv>` |
| 3 | `create-csrf-secret` | `az keyvault secret set --vault-name <kv> -n users-csrf-keys-json --file <temp>` (genera la clave; no la imprime) |
| 4 | `create-webapp` | `az webapp create -g acalderon-TEC -p ASP-acalderonTEC-9c69 -n <webapp> --runtime JAVA:21-java21 --assign-identity [system] --https-only true --basic-auth Disabled` |
| 5 | `grant-keyvault` | `az role assignment create --role "Key Vault Secrets User"` de la identidad de `<webapp>` sobre el secreto CSRF |
| 6 | `sql-firewall` | `az sql server firewall-rule create -s sql-markettec-dev -n ms-users-stg-outbound-<i>`, una regla /32 por cada IP saliente |
| 7 | — | Si la IP del operador cambió: regla de firewall SQL para esa IP |
| 8 | `migrate` | Flyway `migrate` contra `sqldb-ms-users-dev` (reanuda la base; DDL V1–V13 pendientes) |
| 9 | `sql-grant` | `CREATE USER [<webapp>] FROM EXTERNAL PROVIDER` + `GRANT`/`DENY` del script SQL |
| 10 | `configure` | `az webapp config set` (runtime, comando de inicio, FTPS deshabilitado, TLS 1.2, Always On desactivado), `az webapp log config`, `az webapp config appsettings set` |
| 11 | `deploy` | `az webapp deploy --type jar` + etiquetas `deployedSha`/`previousSha` |
| 12 | `start`/`stop`/`rollback` | Según sea necesario |

Fuera de este incremento, con su propia autorización: APIM `markettec-users-v1`, Static Web App, DNS, cualquier flag, claves JWT, federación OIDC para CI y cambio de plan a B1.
