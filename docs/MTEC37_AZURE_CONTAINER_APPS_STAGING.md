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
| Sondas | Startup `/health` (10 s × 30), Readiness (15 s × 3), Liveness (30 s × 3) |
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

`sql-access` debe ejecutarse antes de que la primera revisión pueda arrancar; al terminar crea una revisión nueva, porque una revisión `ActivationFailed` no se recupera con `restart`. Los 13 flags de autenticación quedan en `false`; el script nunca los activa.

## Evidencia de despliegue (2026-11-09, `main@08cac40`)

- Arranque desde 0 réplicas: `GET /health` → 200 en 36 s; registros de `ManagedIdentityCredential`, `DbValidate` (Flyway) y `UsersApplication`, sin `ERROR`; 0 reinicios.
- Java: Temurin 21.0.13, PID 1 `java -jar /opt/ms-users/app.jar`.
- La base usa `autoPauseDelay=60` min. El arranque en frío con la base pausada al mismo tiempo no se ha medido todavía; el arranque tolera hasta 5 min (sonda Startup).

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
