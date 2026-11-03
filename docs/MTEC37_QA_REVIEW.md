# MTEC-37 — Validación de la reconstrucción; re-QA pendiente

Fecha: 2026-11-01. **No es el informe original de QA ni una auditoría independiente.**
El original no pudo recuperarse íntegramente. Este archivo documenta comprobaciones
del agente que reconstruyó el candidato; no asigna QA PASS.

Base `650411bdf0a5b9375fbe6911595f3150d79c633d`, rama
`recovery/mtec37-appservice-readiness`. El historial parcial y la solicitud reportan
130 casos anteriores, pero los resultados que siguen se generaron nuevamente.

| Comprobación del candidato | Resultado observado |
|---|---|
| Java / Maven | Temurin 21.0.13+9; Maven Wrapper 3.9.11 |
| `./mvnw --batch-mode clean verify` | BUILD SUCCESS; 130 ejecutadas, 0 fallos, 0 errores, 0 omitidas |
| ArchUnit | 4 reglas ejecutadas y aprobadas; nombres cortos en los XML |
| JAR | Ejecutable, bytecode release 21, dependencias Actuator/JDBC/Azure Identity/Flyway incluidas |
| Smoke JAR | Heap máximo 256 MiB, perfil local, HTTP 200, correlación, probes internos 200 y rutas no expuestas 404 |
| Respuestas 503 | Probadas con cambio de ReadinessState en tests HTTP; vuelta a 200 y liveness 200 |
| SQL ausente/vacío | Tests de configuración y proceso JAR fallan explícitamente, sin fallback |
| Identidad no disponible | Simulada con mock; arranque rechazado y sin llamada a credenciales de desarrollo |
| Logging | JSON parseable, mensajes/campos sensibles omitidos, MDC aislado/restaurado, errores 500 correlacionados |
| Inventario estricto | 130 casos XML reales reconciliados; 12 comprobaciones separadas del verificador con fixtures sintéticos |
| YAML / shell | Workflow parseado y `bash -n` aprobados; main + PR/main + manual, contents read |
| Perfil Maven SQL | `-Psql-integration test-compile` aprobado; no equivale a ejecutar SQL |
| SQL Server real | No ejecutado: preflight exige Linux x86-64; equipo macOS aarch64 |
| Azure / Jira | Ninguna conexión SQL remota, migración, modificación o actualización |

Distribución real de casos: ver `MTEC37_RECOVERY_REPORT.md` y el manifiesto privado de
validación. Evidencia persistente en `~/Developer/MarketTec/recovery/mtec37-20261101/`:
`verify-mdc-final.log`, `jar-smoke-results.txt`, `verifier-checks.log`, `sql-test-compile.log`
y copia de los XML Surefire. Los fixtures sintéticos del verificador **no son resultados
SQL** y se guardan fuera del repositorio.

## Diferencias y revisión necesaria

Los nombres y el número de regresiones se reconstruyeron desde fragmentos, no desde los
archivos originales. Hay porciones aproximadas en formatter, filtro, tests, YAML y docs.
El filtro restaura todo el mapa MDC. El chequeo automático `db` de Actuator se desactiva
explícitamente; readiness solo usa disponibilidad Spring y no detecta directamente una
caída SQL posterior al arranque. La prueba con SQL simulado verifica ausencia de consultas
y 503 por REFUSING_TRAFFIC, no una conexión JDBC ni un indicador db remoto.
El formatter omite texto diagnóstico incluso durante errores de arranque; valorar esta
limitación durante re-QA. No valida escrituras directas fuera de Logback.

La re-QA debe inspeccionar código/archivos untracked completos, comparar contra la base,
repetir los comandos locales con el JDK persistente y revisar seguridad HTTP/logs, configuración,
semántica de probes y permisos de migración/runtime. Las 34 SQL requieren runner Linux
x86-64 con Docker y aceptación del EULA; una futura publicación necesita autorización
separada. La evidencia histórica de MTEC-35 no aprueba este candidato.

Dictamen de recuperación: **REBUILT — READY FOR RE-QA**. No QA PASS, no CI SQL nuevo,
no publicación y no despliegue.
