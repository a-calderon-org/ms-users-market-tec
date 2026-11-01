# MTEC-37 — PR manual de preparación

Base: `main`

Compare: `recovery/mtec37-appservice-readiness`

Título sugerido: `feat(users): prepare App Service readiness, observability and CI`

## Descripción preparada

Prepara el microservicio para Java 21 en Azure App Service: health público de proceso,
liveness independiente y readiness JDBC dinámico con caché compartida de 30 segundos.
Añade logs JSON con campos permitidos, aislamiento/restauración de MDC y propagación
validada de `X-Correlation-ID`, con cobertura HTTP concurrente y respuestas seguras.

El perfil `sql-appservice` usa Microsoft Entra Managed Identity asignada por el sistema,
destinos explícitos y TLS verificado, sin fallback a credenciales de desarrollo.
Se documenta la separación entre permisos runtime mínimos y la identidad de migración
Flyway; el arranque runtime valida el esquema y no ejecuta migraciones.

El workflow exige Java 21 en Ubuntu x86-64, SQL Server real mediante Testcontainers,
validación Flyway e inventario de pruebas ejecutadas. El verificador rechaza fallos,
errores, omisiones y cualquier resultado flaky/rerun. Falla sin Docker/Testcontainers;
usa permisos `contents: read` y publica reportes diagnósticos sin secretos Azure.

## Validación nueva de subfase 3 — 2026-11-01

- Temurin 21.0.13+9 y Maven Wrapper 3.9.11 en macOS ARM64.
- `./mvnw --batch-mode clean verify`: BUILD SUCCESS, **137 pruebas**, cero fallos,
  errores u omisiones; reconciliadas desde **14 XML Surefire reales**.
- **4 reglas ArchUnit** aprobadas, incluidas en las 137.
- **12 tests Python** del verificador aprobados y reconciliación normal estricta aprobada.
- **13 comprobaciones HTTP** del smoke JAR aprobadas; 35 eventos JSON parseables,
  sin marcadores sensibles. Perfil local, Java 21, heap máximo 256 MiB.
- Migración V1, adaptador SQL, suite SQL e inventario de **34 métodos SQL** intactos
  respecto de la base `650411bdf0a5b9375fbe6911595f3150d79c633d`.
- `origin/main` coincide con la base durante la auditoría; no se integraron cambios.

La evidencia nueva persiste localmente bajo `qa-evidence/mtec37-publication-20261101/`,
excluida de Git, junto con una copia previa del candidato y los reportes anteriores.
Se verificó el SHA-256 original de `before-reqa.tar.gz` sin modificar el archivo ni
su manifiesto. Se conservan los respaldos privados de recuperación y los worktrees.
No se publican JAR, target, archivos de evidencia ni transcripciones privadas.

## Pendientes y límites

Las **34 pruebas SQL del candidato están pendientes del nuevo PR** en GitHub Actions
Linux x86-64. Los resultados históricos MTEC-35 y las pruebas locales con mocks no
constituyen aprobación SQL del candidato. No recomendar merge hasta que SQL remoto
y todos los checks requeridos pasen y se revise el diff final sin defectos críticos.

No hubo despliegue ni modificación de recursos Azure, identidades, firewall, SQL remoto,
APIM o Jira. El PR se creará manualmente; esta publicación no completa MTEC-37.
Managed Identity real, migración Azure, conectividad y seguridad APIM requieren sus
propias verificaciones posteriores. No hay equivalencia acreditada con App Service.

Free F1 mantiene límites de CPU/memoria, disponibilidad y características que deben
revalidarse antes de desplegar; el smoke de 256 MiB no certifica capacidad ni latencia
Azure. Los sondeos JDBC pueden reanudar SQL Serverless y generar consumo. Revisar
los límites de la caché, los timeouts y costos en la guía de preparación.

Dependencias pendientes de verificación: **MTEC-36** (autenticación backend/JWT),
**MTEC-12** (seguridad APIM/acceso directo), **MTEC-15** (contratos de integración)
y **MTEC-40** (aprobación ADR-004). No se consultó su estado actual en Jira.

El Re-QA vigente está en `MTEC37_REQA_REVIEW.md`; los informes de recuperación y QA
previos se conservan como evidencia histórica, sin heredar su aprobación.
