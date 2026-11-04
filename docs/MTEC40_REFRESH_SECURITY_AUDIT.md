# MTEC-40 — Auditoría focalizada de refresh replay

## A. Estado auditado

- Base: `origin/main` en `0ccdebe` (04-11-2026). Alcance: autoridad de sesión, repositorio SQL, endpoint refresh, logging y pruebas relacionadas.
- Política C aprobada en MTEC-40: consumo de un uso, rechazo inmediato sin gracia ni reemisión, replay interno solo para la pareja histórica refresh+CSRF, respuesta pública `401 REFRESH_INVALID`, sesión ganadora conservada ante replay aislado. Revocación por compromiso requiere prueba independiente y autorización.
- Este informe describe el cambio de esta rama además de la base; completar SHA y CI del HEAD final al publicar.

## B. Matriz de hipótesis

| ID | Resultado | Prueba | Evidencia y límite | Severidad |
|---|---|---|---|---|
| H1: dos ganadoras | NO VERIFICADO en SQL | `concurrentRefreshAllowsExactlyOneWinner`; `opaqueRefreshRotatesOnceAcrossConcurrentSqlTransactionsAndRevocation` | La prueba en memoria da una ganadora; SQL local no arrancó por arquitectura ARM. | Alta si ocurre |
| H2: consumida reemite | NO REPRODUCIDA | `refreshRotatesBothCredentialsAndRejectsReplayOrCrossSession`; `httpRefreshReturnsCoherentSnapshotWithoutChangingSessionState` | Rechazo y ausencia de cookie; el JWT solo se emite en la primera rotación. | Alta si ocurre |
| H3: replay revoca ganadora | NO REPRODUCIDA | mismas pruebas y `sqlLogoutKeepsOnlyLastProofForIdempotentCleanup` (pendiente de SQL final) | En memoria la sesión ganadora sigue vigente. | Alta si ocurre |
| H4: prueba inválida se llama replay | NO REPRODUCIDA | `httpRefreshReturnsCoherentSnapshotWithoutChangingSessionState` | CSRF incorrecto y refresh desconocido se clasifican `proof_invalid`; solo pareja histórica correcta es `consumed_confirmed`. | Media si ocurre |
| H5: rollback parcial | NO VERIFICADO en SQL | `refreshRollbackRestoresPreviousCredentialAndSession`; `failedSqlRefreshRollsBackVerifierAndSessionDeadline` | Rollback en memoria probado; el caso SQL existente no se ejecutó localmente. | Alta si ocurre |
| H6: HTTP revela replay | NO REPRODUCIDA | `SessionRefreshControllerTest`; prueba HTTP integrada en `SessionAuthorityTest` | Consumido, desconocido y prueba CSRF inválida reciben `401 REFRESH_INVALID`, sin `Set-Cookie` ni clasificación interna. | Media si ocurre |
| H7: logs inseguros/inútiles | RIESGO DE DISEÑO corregido parcialmente | `refreshAuditEmitsCommittedRotationButNoFalseSuccessAfterSignerFailure`; `malformedCsrfHasUniformErrorAndCorrelatedSecretFreeAudit` | El log previo era texto sin clasificación ni identificador de sesión; ahora hay evento estructurado de allowlist. No hay persistencia durable. | Media |
| H8: dos instancias difieren | NO VERIFICADO | ninguna prueba con dos JVM | Dos threads y dos repositorios en una JVM no prueban multiinstancia. | Alta si ocurre |

## C. Vulnerabilidades confirmadas

Ninguna explotación de refresh replay fue confirmada por las pruebas ejecutadas. Se corrigieron dos incumplimientos observables del alcance: el evento de replay previo no era investigable de forma estructurada y el prechequeo HTTP de CSRF mal formado devolvía `403 CSRF_INVALID` en vez del error público uniforme. No se atribuye a estos hallazgos una explotación no demostrada.

## D. Riesgos no confirmados

- La política C conserva deliberadamente la generación ganadora ante replay aislado. Si quien robó el refresh ganó primero, puede conservarla hasta su vencimiento o revocación autorizada. La pareja refresh+CSRF no prueba posesión de dos secretos independientes porque el bootstrap puede entregar CSRF con una cookie vigente.
- Un JWT ya emitido no se invalida inmediatamente al revocar refresh. La cota residual de 300 s exige prueba extremo a extremo separada.
- El log estructurado puede perderse si falla el proceso o el transporte de logs; no es un evento persistido ni transaccional. La etiqueta de éxito se emite después de que retorna la transacción local, sin afirmar durabilidad del log.

## E. Auditoría y revocación

`refresh_audit` registra timestamp del formatter, correlation ID del MDC HTTP, tipo `session_refresh`, resultado (`rotated`, `consumed_confirmed`, `proof_invalid`, `session_closed`), política `C-1` y referencia seudónima de sesión cuando se conoce. El formatter solo permite esos valores. No serializa refresh, CSRF, JWT, cookies, Authorization, hashes de credenciales ni identificadores de usuario. Los tests inspeccionan el JSON final y el rechazo HTTP. Fuera de HTTP puede faltar correlation ID si el llamador no establece MDC.

`logout` exige la pareja vigente y revoca la sesión; una pareja consumida no puede cerrar la ganadora. `revoke(sessionId)` es una operación interna que supone un llamador autorizado, pero no recibe evidencia, actor, motivo ni correlación verificables y no registra una decisión de incidente. Por ello **no constituye todavía un procedimiento comprobable de revocación por compromiso**. Para usarla en ese caso se requiere: evidencia independiente conservada, decisión y actor competente registrados, alcance de sesión/familia y motivo, correlación con evento, llamada autorizada, registro de resultado y comprobación posterior de rechazo de refresh y bloqueo de nuevas emisiones. IP, User-Agent, Origin y número de replays no bastan. No se creó endpoint administrativo ni revocación automática.

## F. Pruebas

- Unidad/HTTP focalizadas: `SessionAuthorityTest` y `SessionRefreshControllerTest`, **45/45 PASS** en la suite final.
- Java completo: **285/285 PASS** fuera del sandbox (Tomcat necesita sockets locales), después del último ajuste.
- Inventario: `normal_count=285`, 12 pruebas del verificador Python PASS y los métodos obligatorios aparecen en los reportes Java. La conciliación estricta completa depende de SQL en CI.
- SQL focalizada: los métodos existentes de `SqlUserStoreIT` cubren carrera y rollback. El intento local falla en `migrateOnlyFreshIsolatedDatabase` antes de migraciones o assertions: Docker detecta `linux/aarch64`, mientras el test exige x86-64 nativo. La EULA del contenedor se autorizó para este intento. **SQL, Flyway y consumo histórico no verificados localmente**.
- Multiinstancia: no ejecutada en el PR #15. El incremento posterior del [PR #16](https://github.com/a-calderon-org/ms-users-market-tec/pull/16) ejecutó dos JVM independientes sobre SQL Server aislado y verificó carreras, replay y rollback; véase `docs/MTEC40_MULTIINSTANCE_REFRESH_QA.md`. Sigue sin existir prueba HTTP multiinstancia.
- CI completo del PR #15: pendiente al redactar esta auditoría; posteriormente [la ejecución 58 de `main`](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41910638813) pasó para `e99ab22`.

## G. Recomendación

Esta recomendación fue histórica para el PR #15. El PR #16 agregó la prueba de concurrencia entre dos JVM; su resultado y límites están en `docs/MTEC40_MULTIINSTANCE_REFRESH_QA.md`. Durabilidad de eventos, revocación por compromiso autorizada y seguridad de producción/APIM siguen fuera de la evidencia.
