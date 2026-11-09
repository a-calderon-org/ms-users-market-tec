# MTEC-37 — Preparación de despliegue reconstruida

Fecha: 2026-11-01. Repositorio: `a-calderon-org/ms-users-market-tec`.
Base: `650411bdf0a5b9375fbe6911595f3150d79c633d`.
Esta es una reconstrucción basada en Git y fragmentos del historial, no el documento
original. Consultar [recuperación](MTEC37_RECOVERY_REPORT.md),
[validación del candidato reconstruido](MTEC37_QA_REVIEW.md) y
[re-QA de la subfase 2](MTEC37_REQA_REVIEW.md). Esta guía incorpora la corrección
de readiness de la re-QA; no acredita validación SQL completa ni despliegue.

> **2026-11-08:** el procedimiento operativo vigente, con el inventario Azure verificado en modo lectura,
> está en [MTEC37_AZURE_STAGING_EXECUTION.md](MTEC37_AZURE_STAGING_EXECUTION.md). Este documento queda como referencia histórica.

## Java 21 y alternativas de App Service

El proyecto compila con release 21 y Maven Wrapper 3.9.11. El JAR ejecutable
incluye Spring Boot 3.5.16, Actuator, JDBC, Azure Identity y Flyway SQL Server.
El smoke local usa Temurin 21.0.13+9 y heap máximo 256 MiB; no representa una prueba
de capacidad, CPU, RSS total, carga ni arranque en Azure.

Microsoft documenta soporte Java SE 21 en App Service en su
[política de runtimes](https://learn.microsoft.com/en-us/azure/app-service/language-support-policy).
El runtime Java SE es apropiado para este JAR con Tomcat embebido. Antes de una futura
creación deben confirmarse en el portal o catálogo de solo lectura el runtime Linux,
la región, la cuota y la elegibilidad de la suscripción. El historial y el usuario
reportaron F1 seleccionable en Canada Central; esa disponibilidad no se revalidó aquí.

| Alternativa | Evaluación y límites |
|---|---|
| Free F1 | Opción experimental, condicionada a disponibilidad real. CPU compartida; la tabla general publica 60 minutos CPU/día y 1.024 MB por plan. No asumir rendimiento sostenido, Always On, SLA ni equivalencia entre todas las variantes Linux/Windows. |
| Basic B1 Linux | Alternativa de pago: la página pública lista 1 core, 1,75 GB RAM y 10 GB de almacenamiento. Revisar si la carga necesita margen y características de red que F1 no ofrece. |

Fuentes públicas consultadas el 2026-11-01:
[límites de App Service](https://learn.microsoft.com/en-us/azure/azure-resource-manager/management/azure-subscription-service-limits#app-service-limits),
[precios Linux](https://azure.microsoft.com/en-us/pricing/details/app-service/linux/).
Los límites generales no certifican que la oferta esté disponible para esta suscripción.
El heap es solo parte de la memoria: reservar también metaspace, code cache, stacks,
buffers nativos, agentes, SDK y servidor. Medir RSS y latencia bajo carga antes de elegir plan.

## Costos y protección de la oferta gratuita

Precio regional actual de B1, crédito restante, cuotas y límites de Azure for Students:
**PENDIENTES**. La página pública renderizó el precio como `$-`; no se interpreta como cero.
El historial reportó USD 0,018/h (USD 13,14 por 730 horas), pero es una cotización histórica
sin validación actual y no un presupuesto aprobado. Calcular costo mensual como
tarifa regional vigente por horas del plan, más impuestos y servicios adicionales.
Un plan de pago puede seguir facturando aunque se detenga una aplicación: revisar el
[modelo de cobro](https://azure.microsoft.com/en-us/pricing/details/app-service/linux/).
Ninguna alternativa de pago queda autorizada por este documento.

Considerar también Azure SQL, almacenamiento y backup, egreso, Log Analytics/Application
Insights y retención, Private Endpoint, DNS, red y APIM si fueran necesarios. No activar
telemetría de pago automáticamente. La documentación de
[SQL Serverless](https://learn.microsoft.com/en-us/azure/azure-sql/database/serverless-tier-overview?view=azuresql)
explica que conexiones/actividad pueden reanudar la base y consumir cómputo; almacenamiento
puede facturarse durante la pausa según oferta. No se consultaron ni modificaron recursos Azure.
El estado histórico de oferta gratuita y pausa por agotamiento mensual debe revalidarse
con un operador antes de usar la base; no asumir saldo ni disponibilidad continua.

## Health, readiness y liveness

| Listener / ruta | Semántica |
|---|---|
| Principal `/health` | HTTP 200, estado del proceso y correlación; no consulta SQL. No certifica autenticación ni conectividad remota. |
| Principal `/actuator/**`, `/readyz`, `/livez` | HTTP 404; no se publican probes adicionales. |
| Loopback `127.0.0.1:${MANAGEMENT_PORT:9091}/actuator/health/liveness` | Estado de vida de Spring, sin acceso SQL. |
| Loopback `/actuator/health/readiness` | `readinessState` + `sqlReadiness` en perfiles SQL. JDBC disponible: 200/UP; caída: 503/DOWN; recuperación: 200/UP tras expirar caché. Rechazo del ciclo de vida: 503/OUT_OF_SERVICE. |
| Loopback `/actuator/health` | Agregado sin detalles/componentes. En perfiles SQL comparte el indicador JDBC y su caché con readiness; también puede conectar a SQL. |
| Loopback `/actuator/env`, `/actuator/metrics` | No expuestos; 404 genérico. JMX no expone endpoints. |

La re-QA corrigió la caída SQL posterior al arranque: el indicador de producción
obtiene una conexión del datasource real y ejecuta `Connection.isValid(2)`. Ante
conexión inválida, `SQLException` o pool cerrado devuelve DOWN sin detalles. No
publica estados de Spring y nunca participa en liveness. Los perfiles sin SQL
conservan readiness de proceso sin datasource. El `db` automático sigue deshabilitado
para evitar duplicación; no se habilitan migraciones ni otro pool.

El resultado UP o DOWN se comparte durante 30 s (`users.sql.readiness.cache-ttl`),
con acceso sincronizado para agrupar lectores simultáneos. No hay scheduler ni
reintentos propios. La siguiente solicitud después del TTL vuelve a comprobar SQL;
la detección y recuperación pueden tardar TTL + intervalo de sondeo + tiempo JDBC.
El pool permite hasta 35 s de espera de conexión y la validación solicita 2 s; esto
no constituye un límite absoluto de red y debe ajustarse al presupuesto de sondas
en la fase de despliegue. No reducir TTL a cero en producción sin evaluar carga;
los tests HTTP lo hacen para verificar transiciones sin sleeps y los unitarios
verifican expiración exacta mediante un reloj monotónico controlado.

[Microsoft documenta](https://learn.microsoft.com/en-us/azure/azure-sql/database/serverless-tier-auto-pause-resume?view=azuresql)
que una sesión/actividad puede impedir la pausa y un intento de login puede reanudar
Serverless. Por tanto, este readiness sí puede generar consumo: la caché reduce
sondeos redundantes pero no garantiza auto-pausa. Bajo demanda continua hay como
máximo un intento del indicador por TTL e instancia (aproximadamente 120/h con
30 s, además de validaciones internas del pool/driver y tráfico de negocio). No
usar readiness ni el agregado como heartbeat público permanente si se quiere
permitir pausa; el check de App Service permanece en `/health`, sin SQL. Esta es
una decisión de disponibilidad frente a ahorro que debe acordarse antes de desplegar.

El puerto de gestión permanece en loopback y sin detalles. Las pruebas HTTP SQL
usan fallos simulados del datasource/JDBC real del indicador, sin cambiar
`ReadinessState`; no equivalen a ejecutar Azure SQL ni las 34 pruebas SQL nativas.
Spring recomienda evaluar dependencias externas en readiness y mantenerlas fuera
de liveness en su [documentación de Actuator 3.5](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html).

## Logs JSON y correlación

`SafeJsonLogFormatter` implementa el formatter estructurado de Spring Boot desde el
arranque. Emite fecha, nivel, logger, correlation ID validado y tipo de excepción.
Solo conserva eventos constantes `request_completed`, `request_failed` y `runtime_event`;
para HTTP permite método, patrón de ruta, estado y duración. No emite rutas concretas,
query strings, cuerpos, tokens, cookies, contraseñas, mensajes libres, stack traces,
causas ni pares/MDC arbitrarios. Se reduce deliberadamente el detalle diagnóstico.
La API usada consta en la [documentación de Boot 3.5](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html).

La cabecera `X-Correlation-ID` acepta `[A-Za-z0-9._-]{1,128}`; cualquier otro valor se
reemplaza por UUID. Es metadato no confiable, nunca identidad ni autorización; el cliente
debe evitar datos personales en ella. El filtro aísla MDC durante la solicitud y restaura
el contexto completo del llamador incluso si escapa una excepción. No deja campos de
solicitudes previas en el siguiente contexto. Los errores HTTP inesperados usan 500 con
un envelope seguro; recursos ausentes responden 404 y métodos no admitidos 405.
No se implementan APIs/JWT nuevos. No se garantiza saneamiento de escrituras directas a
stdout/stderr, agentes externos o código futuro. Mantener wire/access logs y DEBUG/TRACE
sin saneamiento desactivados. Revisar cualquier ampliación de la lista permitida.

## SQL: identidad, TLS y perfiles

`sql-appservice` exige servidor/base explícitos no vacíos y usa exclusivamente
`ActiveDirectoryManagedIdentity` del driver, para Managed Identity asignada por el sistema.
No selecciona Azure CLI ni la cadena `ActiveDirectoryDefault`; no acepta identidad de
migración o credenciales SQL embebidas. `sql-dev` conserva su comportamiento de desarrollo.
Memoria, SQL de desarrollo, App Service e integración no pueden combinarse para fallback.
Configuración ausente/vacía, identidad no disponible o esquema incompatible impiden el arranque.

TLS: `encrypt=true`, `trustServerCertificate=false`, nombre de certificado
`*.database.windows.net`, timeouts de login/socket de 30 segundos. Pool máximo por defecto 3,
mínimo inactivo 0, sin keepalive, idle timeout 60 segundos; una conexión o actividad puede
impedir pausa. La [autenticación JDBC oficial](https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-using-azure-active-directory-authentication?view=sql-server-ver17)
distingue los modos Managed Identity y Default. El perfil está preparado localmente;
la identidad real, concesiones y conectividad Azure **no están implementadas ni validadas aquí**.

Separar una identidad de migración con permisos de DDL/historial de una identidad runtime
con SELECT/INSERT/UPDATE mínimos, lectura de historial y metadatos necesarios para el
validador. Runtime no necesita DELETE ni DDL. Acordar y auditar los permisos exactos con
el responsable de seguridad; no se ejecutó ninguna concesión.

## Red Azure SQL y migración V1

Managed Identity autentica; no sustituye conectividad ni reglas de red. Mantener firewall
restringido: no habilitar indiscriminadamente acceso de todos los servicios Azure.
Evaluar IPs de salida del App Service y cambios de escala o VNet/Private Endpoint/DNS
si el plan los soporta. Compatibilidad de red de F1, IP estable y costo de B1/red privada
son pendientes de diseño. No cambiar firewall, Entra-only ni políticas de gasto para probar.

Flyway automático permanece excluido en todos los perfiles. El runtime solo valida;
el único `migrate()` del proyecto pertenece a la fixture SQL aislada. V1 se conserva
idéntica a `origin/main`.

Una intervención posterior y autorizada debe: confirmar tenant/suscripción/base e identidad
de migración; revisar SQL V1, checksum y resultados SQL nativos del candidato; inspeccionar
catálogo e historial; detenerse ante tablas/datos inesperados; acordar ventana y recuperación;
ejecutar Flyway separadamente con `cleanDisabled(true)` y sin baseline automático;
verificar historial/validate/metadatos y solo entonces iniciar el runtime restringido.
No ejecutar V1 ni `repair`, `clean` o baseline contra Azure desde este flujo.

Rollback: conservar JAR/configuración anteriores y revertir la aplicación a una versión
compatible tras detener tráfico de forma controlada. No deshacer V1 con DROP, ni editar
una migración aplicada. Un defecto de esquema necesita migración aditiva revisada;
restaurar backup solo con un plan explícito que contemple pérdida de escrituras, ventana,
permisos, retención y costo. B1 no implica disponibilidad de slots; no basar rollback en ellos.

## CI y validación pendiente

Se conserva `.github/workflows/qa-mtec35-sql.yml` con nombre `ms-users CI`: PR hacia `main`,
push a `main` y ejecución manual. Ubuntu 24.04 x86-64, Java 21, Maven Wrapper, tests normales,
cuatro ArchUnit y 34 métodos SQL Server 2022 fijados por digest mediante Testcontainers.
El preflight falla sin arquitectura/Docker/EULA adecuados; no omite SQL silenciosamente.
El verificador exige 144 casos normales tras integrar MTEC-36, los casos críticos por
nombre, y los 34 SQL exactos sin errores, fallos, omisiones ni resultados flaky/rerun, aunque un reintento
termine aprobado. Reconcilia también el resumen Failsafe; el entrypoint ejecuta
las regresiones Python del verificador antes de Maven.
Permisos `contents: read`; sin deploy, credenciales Azure ni `continue-on-error`.
El workflow untracked de MTEC-34 solo se leyó como referencia y se conserva intacto.

El candidato pasó 137 pruebas y smoke local; las 12 regresiones Python del verificador
también pasaron. La suite SQL solo se compiló. El informe de la subfase 2 registra
**QA PASS WITH NOTES — preparación local**, sin aprobación global de MTEC-37.
Los 34 casos SQL del candidato deben ejecutarse en CI remoto más adelante, con autorización
de publicación separada. La ejecución histórica MTEC-35 no valida esta reconstrucción.

## Dependencias y pasos futuros

| Dependencia | Requisito pendiente de revisión |
|---|---|
| MTEC-36 | Autenticación backend/JWT y protección real de las rutas. |
| MTEC-12 | Seguridad APIM y protección del acceso directo al backend. |
| MTEC-15 | Contratos compartidos y consistencia de integración. |
| MTEC-40 | Evidencia de aprobación ADR-004 y sus condiciones vigentes. |

Estos son roles documentados en la preparación original; no son estados actuales de Jira.
No se consultó ni modificó Jira en esta recuperación. No afirmar que están terminados.

| Etapa | Estado o prerrequisito, riesgo/costo y evidencia esperada |
|---|---|
| 1. Re-QA local de subfase 2 | Completada en `MTEC37_REQA_REVIEW.md`; XML, logs, smoke y manifiesto locales. SQL y cierre integral pendientes. |
| 2. Publicación/CI SQL | Autorización distinta para commit/push/PR; run con 34 SQL reales y artefactos ligados al SHA publicado. |
| 3. Elegir región y plan | Verificar Java 21/F1/cuotas/precio/saldo; aprobación manual de cualquier costo. |
| 4. Crear App Service | Solo con autorización de infraestructura; evidencia de plan/runtime y presupuesto. |
| 5. Habilitar identidad runtime | Revisar mínimos privilegios y separación; aprobación y auditoría de identidad/concesiones. |
| 6. Configurar red | Diseño aprobado de firewall/IP/VNet/DNS; validar acceso restringido y costos. |
| 7. Aplicar Flyway controlado | SQL CI aprobado, inspección de destino, backup/ventana y autorización específica; historial V1 verificado. |
| 8. Preparar OIDC de CI | Federación y permisos mínimos revisados; sin secret estático ni despliegue automático en este workflow. |
| 9. Desplegar y probar | Dependencias de seguridad/contratos resueltas; checks HTTP, identidad y SQL autorizados. |
| 10. Ensayar rollback | JAR/config anterior compatibles y plan de recuperación aprobado; evidencia sin borrados automáticos. |

MTEC-37 permanece en preparación local; infraestructura, despliegue, seguridad y CI SQL del
candidato requieren trabajo y autorizaciones posteriores.
