# MTEC-37 — ms-users en Azure Container Apps (staging)

Sustituye al App Service F1 `ms-users-stg-markettec` (estado `QuotaExceeded`, sin arranque estable de Java 21).

## Plataforma

| Elemento | Valor |
| --- | --- |
| Servicio | Azure Container Apps, plan Consumption, entorno `cae-ms-users-stg2` (modo `WorkloadProfiles`, sin VNet propia), Canada Central |
| App | `ca-ms-users-stg`, 0,5 vCPU / 1 GiB, réplicas 0..1, regla HTTP (20 concurrentes), modo de revisión único |
| URL | `https://ca-ms-users-stg.orangerock-c46177b6.canadacentral.azurecontainerapps.io` (HTTP → 301 HTTPS) |
| Imagen | `acrmsusersstg.azurecr.io/ms-users:<sha>` (ACR Basic, pull con identidad asignada por el usuario) |
| Java | Temurin 21 JRE, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -Xss512k` |
| Sondas | Startup `/health` (retardo 1 s, 3 s × 30 ≈ 91 s), Readiness (15 s × 3), Liveness (30 s × 3). `app` conserva las sondas existentes y solo añade las que falten |
| Logs | Log Analytics `log-ms-users-stg`, retención 30 días, tope 0,1 GB/día |

### Identidades

- `id-ms-users-stg` (asignada por el usuario): `AcrPull` en el ACR y `Key Vault Secrets User` solo sobre el secreto `users-csrf-keys-json`. Resuelve la referencia de Key Vault del secreto `csrf-keys-json`.
- Identidad del sistema de `ca-ms-users-stg`: usuario SQL `[ca-ms-users-stg]`. El driver usa `ActiveDirectoryManagedIdentity` sin client ID, es decir, la identidad del sistema.

## Decisiones de coste (Canada Central, precios minoristas oficiales)

- **Sin VNet propia.** Con VNet, el entorno crea en `ME_…` un Standard Load Balancer (0,025 US$/h ≈ 18,25 US$/mes) y una IP pública estándar (≈ 3,65 US$/mes). Sumado al ACR supera los 25 US$/30 días.
- **Sin entorno Express.** `az containerapp env create` sin VNet crea ahora un entorno Express, que rechaza referencias de Key Vault (`ExpressKeyVaultSecretsNotSupported`) e identidades del sistema. El script crea el entorno por REST con `environmentMode=WorkloadProfiles`.
- **Escala a cero obligatoria.** Con una réplica permanente activa (0,5 vCPU) el coste rondaría 38 US$/mes y Hikari mantendría despierta la base serverless, agotando su oferta gratuita (que está en `AutoPause`). Hikari ya usa `minimumIdle=0` e `idleTimeout=60s`.

## Red hacia Azure SQL

Una regla de firewall /32 por cada IP de `outboundIpAddresses` de la app (161 IP compartidas por el egreso de Container Apps en la región, reglas `ms-users-aca-*`). No se usa la regla "Allow Azure services" (0.0.0.0) ni rangos amplios. Riesgo aceptado para staging, igual que con F1: esas IP las comparten otros clientes de Container Apps en Canada Central; la autenticación sigue siendo exclusivamente Entra (`azureAdOnlyAuthentication=true`). La alternativa privada (VNet + service endpoint o private endpoint) no cabe en el presupuesto por el balanceador.

**Riesgo operativo pendiente: rotación de las 161 reglas.** Azure puede cambiar el conjunto de `outboundIpAddresses` del entorno sin aviso. Las reglas `ms-users-aca-*` no se reconcilian solas: si cambia una IP, la app pierde acceso a SQL (fallo de arranque o de `/health`) y las reglas de IP que ya no pertenecen al entorno siguen abiertas. Hasta que exista una reconciliación automatizada (alta de IP nuevas y baja de las obsoletas), `sql-access` debe volver a ejecutarse tras cada cambio del entorno o ante errores de conexión SQL (solo crea reglas, no borra las obsoletas), y debe revisarse periódicamente a mano que las reglas `ms-users-aca-*` coinciden con `outboundIpAddresses`, eliminando las sobrantes. No se aborda en MTEC-37.

## Permisos SQL del runtime

`scripts/azure/sql/grant-runtime-identity.sql` concede además `VIEW DEFINITION` sobre `dbo.external_identities` únicamente: `SqlSchemaValidator` lee `sys.computed_columns.definition` y, solo con `SELECT`, esa columna es `NULL` y el arranque falla con `BeanCreationException`. Verificado con `EXECUTE AS USER`.

## Operación

```bash
scripts/azure/aca/ms-users-aca.sh infra
scripts/azure/aca/ms-users-aca.sh image --sha <commit con "ms-users CI" en verde>
scripts/azure/aca/ms-users-aca.sh app --sha <commit>
scripts/azure/aca/ms-users-aca.sh sql-access
scripts/azure/aca/ms-users-aca.sh verify
```

`sql-access` debe ejecutarse antes de que la primera revisión pueda arrancar; al terminar crea una revisión nueva, porque una revisión `ActivationFailed` no se recupera con `restart`. Los 14 flags de autenticación quedan en `false`; el script nunca los activa.

## Evidencia de despliegue (2026-11-09, `main@08cac40`)

- Arranque desde 0 réplicas: `GET /health` → 200 en 36 s; registros de `ManagedIdentityCredential`, `DbValidate` (Flyway) y `UsersApplication`, sin `ERROR`; 0 reinicios.
- Java: Temurin 21.0.13, PID 1 `java -jar /opt/ms-users/app.jar`.
- La base usa `autoPauseDelay=60` min. El arranque en frío con la base pausada se mide en la sección siguiente.

## Arranque en frío con Azure SQL pausado (MTEC-37)

### Evidencia (Log Analytics, solo lectura, 2026-11-09)

Los registros ya estaban redactados (`runtime_event` + `exception_type`); no se leyó ningún mensaje del driver. Arranque de las 16:24:50 UTC (`ca-ms-users-stg--rf622013`):

| Hora (UTC) | Evento |
| --- | --- |
| 16:24:50.6 | Réplica asignada (0 → 1) |
| 16:25:05.5 | Contenedor iniciado |
| 16:25:08.8 | Hikari inicia el pool (`initializationFailTimeout` por defecto: un único intento) |
| 16:25:40.4 | `UnsatisfiedDependencyException` en `sqlDataSource` ≈ 31,6 s después: coincide con `loginTimeout=30` del driver. No hay registros de `ManagedIdentityCredential` antes del fallo, lo que indica que el login no llegó a pedir token |
| 16:25:41.5 | `ProcessExited`, código 1 |
| 16:25:51.5 | Contenedor reiniciado por Container Apps (≈ 10 s de espera) |
| 16:26:00–02 | Token de identidad, `DbValidate` (Flyway) y `Started UsersApplication`; primer `GET /health` 200 a las 16:26:02.8 |

El mismo patrón (salida con código 1 unos 35 s tras arrancar y reinicio 10 s después) aparece en todos los arranques en frío del día (08:26, 13:30, 14:33, 15:36, 16:25). Unos 22 s del total (espera de reinicio más un segundo arranque de la JVM) se pueden evitar.

### Corrección

`SqlStartupRetry` envuelve solo la construcción del pool en `SqlUserConfig.sqlDataSource`. Hikari sigue fallando rápido (un intento por construcción). Si la causa es transitoria (SQLState de clase `08`, `SQLTransientException` o los códigos de Azure 40613, 40197, 40501, etc.), reintenta dentro del mismo proceso con espera de 2, 4, 8 y 10 s. Hace como máximo 5 intentos y no empieza ninguno pasados 50 s. Con 30 s por intento el peor caso cabe dentro de la sonda Startup vigente (≈ 91 s): la JVM se rinde por sí misma antes de que la maten a mitad de un intento. Los errores no transitorios (login denegado 18456, base inexistente, identidad no disponible, perfiles) fallan en el primer intento, como antes.

No cambia el orden: pool → `SqlSchemaValidator` (Flyway `validate`, claves, índices) → resto de beans → Tomcat. Hasta que el contexto termina, Tomcat no acepta conexiones, así que durante la espera no se atiende ninguna petición ni se repite ninguna escritura. Cada intento deja un registro `sql_startup_retry` con `attempt`, `outcome`, `sqlState`, `vendorCode`, `delayMs` y `elapsedMs`, sin texto del driver.

### Límite conocido

No se ha medido en Azure un arranque con esta corrección, y **no se afirma que el primer acceso tras inactividad baje de 70 s**. Estimación: sin reinicio, el arranque observado habría terminado hacia las 16:25:46 (≈ 56 s desde la asignación), pero eso depende de cuánto tarde Azure en reanudar la base, y Azure no garantiza ese tiempo. Si la reanudación supera la ventana de reintentos, el proceso sale y Container Apps lo reinicia, igual que antes.

### Recuperación del cliente (market_tec)

- El proxy same-origin solo envía una escritura cuando ms-users respondió a un `GET` de calentamiento. Si no, contesta `503 BACKEND_WARMING` sin enviarla. Angular V2 repite únicamente ese 503, cada 3 s, dentro de su tope de 70 s, y nunca repite `TIMEOUT`, `504` ni errores de red.
- Hueco corregido en market_tec: Angular podía agotar sus 70 s mientras el proxy seguía esperando a ms-users, y el proxy enviaba igualmente el `POST`. El navegador ya no recibía la respuesta, de modo que por ejemplo una rotación de refresh podía perderse. Ahora Angular envía su presupuesto restante y el proxy no envía una escritura que no pueda terminar dentro de ese plazo: responde `BACKEND_WARMING`.
- Si a los 70 s ms-users sigue sin responder, el `GET /session` inicial termina en `unavailable` (sin POST) y la sesión local se conserva. Para entonces los `GET` de calentamiento ya han despertado ms-users, y la siguiente demanda de token (navegación o acción del usuario) se recupera sin repetir ninguna escritura.

## Coste estimado (30 días)

| Concepto | Estimación |
| --- | --- |
| ACR Basic | 0,1666 US$/día ≈ 5,00 US$ |
| Container Apps (escala a cero; 180 000 vCPU-s y 360 000 GiB-s gratis al mes) | 0 US$ hasta 100 h de réplica activa al mes; ≈ 0,076 US$ por hora adicional (0,5 × 0,000034 + 1 × 0,000004 US$/s). Con tráfico continuo 24/7 serían ≈ 47 US$: un monitor externo cada < 5 min rompería el presupuesto |
| Log Analytics (≤ 3 GB/mes, dentro de los 5 GB gratis; retención 30 días incluida) | 0 US$ |
| Key Vault (operaciones) | < 0,05 US$ |
| Azure SQL (oferta gratuita, `AutoPause`) | 0 US$ |
| **Total previsto** | **≈ 5–8 US$** |

Presupuesto `budget-ms-users-stg-25usd` (25 US$/mes sobre `acalderon-TEC`): avisos por correo al 50 % y 80 % real y al 100 % previsto. Es una alerta, no un límite de gasto.
