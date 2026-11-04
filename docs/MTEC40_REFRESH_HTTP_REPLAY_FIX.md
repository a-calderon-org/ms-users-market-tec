# MTEC-40 — Detección interna de refresh replay por HTTP

Fecha: 2026-11-04 (America/Costa_Rica). Base: `origin/main` en `24d6e2f51f1b22967737eb889052e6c1b2c96238`; no había commits posteriores al corte de la verificación anterior. Este documento describe una corrección técnica, no la aprobación de una política de revocación.

## Causa raíz y comportamiento anterior

`refreshWithSnapshot()` buscaba primero la sesión por el hash refresh **vigente**. Después de una rotación, el hash antiguo solo existe en `session_credential_generations`, por lo que ese primer lookup lanzaba `PROOF_INVALID` antes de llamar a `refresh()`. La operación HTTP devolvía correctamente `401 REFRESH_INVALID`, pero la clasificación interna `ATTEMPT_CONSUMED` y su aviso sanitizado eran inalcanzables desde el endpoint. `refresh()` directo sí detectaba la pareja consumida.

## Corrección

`refresh()` y `refreshWithSnapshot()` usan ahora `rotateLocked()` dentro de una sola transacción. La búsqueda de la credencial vigente y, si falta, la consulta de la generación consumida comparten la ruta de bloqueo del repositorio. Solo se clasifica `ATTEMPT_CONSUMED` cuando el refresh histórico existe, la sesión aún está vigente y el hash del CSRF presentado coincide en tiempo constante. Los valores inexistentes o la pareja con CSRF incorrecto siguen siendo `PROOF_INVALID`. Para HTTP, la validación del usuario activo y `renewedAt` ocurre antes de rotar, y el snapshot se toma dentro de la misma transacción. Se eliminó la segunda búsqueda y la transacción anidada del wrapper.

No se introdujo revocación automática, gracia de replay, recuperación de credenciales consumidas ni cambio de plazos. `SessionRefreshController` mantiene el resultado público uniforme `401 REFRESH_INVALID`; no expone la razón interna. El aviso `Consumed refresh generation reused` es una cadena fija sin refresh, CSRF, cookie, hash, JWT ni otro secreto. Es un log local, no un evento durable de auditoría.

## Evidencia automatizada local

`SessionAuthorityTest.httpRefreshReplayReachesInternalConsumedClassification` pasa por MockMvc y la autoridad real con credenciales sintéticas. Captura exactamente un aviso interno tras el replay con CSRF correcto; un CSRF incorrecto y un refresh inexistente no agregan avisos. Comprueba `401 REFRESH_INVALID`, ausencia de `Set-Cookie`, un solo JWT emitido, generación única consumida, sesión ganadora activa e identidad exacta del estado de sesión, incluidos `renewedAt`, `lastActivityAt` y los deadlines 60/30/8. Comprueba además que una llamada al wrapper clasifica `ATTEMPT_CONSUMED`. Esta prueba habría fallado antes de la corrección por falta del aviso y por `PROOF_INVALID` en el wrapper.

Se comprobó esa regresión de forma explícita: al ejecutar solo esta prueba contra el `SessionAuthority.java` anterior, falló (`expected: <1> but was: <0>` en el aviso interno); con el código corregido, pasó (1/1). El archivo original se restauró antes de terminar.

Las otras pruebas de `SessionAuthorityTest` y `SessionRefreshControllerTest` cubren rotación válida, malformación, ausencia de cookie o CSRF, expiración, revocación, dos solicitudes simultáneas, retry después del commit, rollback del firmador y rollback después de consumir la generación. La prueba de retry representa una respuesta perdida después del commit; no simula una pérdida de red real. Las pruebas SQL de `SqlUserStoreIT` se actualizaron para competir usando `refreshWithSnapshot()`, verificar la clasificación del perdedor y comprobar rollback del firmador a través del wrapper.

- Prueba focal: 39 ejecutadas, 0 fallos, 0 errores, 0 omitidas.
- Pruebas HTTP y de autoridad focales: 43 ejecutadas, 0 fallos, 0 errores, 0 omitidas.
- Suite normal completa: 279 ejecutadas, 0 fallos, 0 errores, 0 omitidas (ejecución con permiso de sockets locales efímeros). La primera ejecución en sandbox dio 7 errores de apertura de socket de Tomcat; desaparecieron al repetirla con ese permiso.
- Fuentes de integración SQL: `-Psql-integration -DskipTests test-compile` completó correctamente.
- SQL Server real: el primer run Linux x86-64 ejecutó 55 pruebas SQL, 0 fallos, 0 errores y 0 omitidas; Flyway validó las 10 migraciones. El build Maven pasó. La conciliación estricta del workflow falló porque `scripts/mtec35-test-inventory.json` aún esperaba 275 casos normales y se ejecutaron 279. El inventario se actualizó a 279 y ahora exige explícitamente los cuatro casos HTTP incorporados. La suite normal y la conciliación local `--normal-only` pasaron con 279/279; el rerun completo SQL está pendiente.

La rama se publicó con autorización explícita y se abrió el Draft PR [#13](https://github.com/a-calderon-org/ms-users-market-tec/pull/13). El primer workflow [run 41792127009](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41792127009) conservó el [artefacto QA](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41792127009/artifacts/13304156125); su resultado global es FAIL por el inventario desactualizado, no por fallos de tests SQL.

## Validación SQL y límites

El workflow `.github/workflows/qa-mtec35-sql.yml` ejecuta `scripts/qa-mtec35-sql.sh` en `ubuntu-24.04` x86-64 con SQL Server real, Flyway, Surefire, Failsafe y conciliación estricta mediante `verify-mtec35-reports.py`; conserva reportes como artefacto. El primer run verificó 279 casos normales y 55 SQL, todos sin fallos, pero la conciliación rechazó correctamente el conteo esperado antiguo. La ejecución con el inventario corregido aún debe confirmar el estado global PASS. Ningún test en memoria sustituye la verificación de bloqueo, concurrencia y rollback SQL.

La verificación anterior (`docs/MTEC40_REFRESH_SECURITY_VERIFICATION.md` en el worktree de lectura) identificó ausencia de sender-constraining criptográfico y no verificó políticas APIM desplegadas. Esos riesgos permanecen. El contrato OpenAPI `0.3.0-draft` descrito en `docs/GOOGLE_G2B_B3_REFRESH.md` aún anuncia revocación al detectar replay, mientras esta implementación preserva la sesión ganadora. No se modificaron OpenAPI ni ADR-004. La elección de política de replay, la conciliación del contrato, retención y tratamiento de eventos, y cualquier cambio de APIM requieren decisiones separadas; no se aprueban alternativas A, B o C aquí.
