# MTEC-40: refresh HTTP entre instancias

## Base y topología

- Base: `origin/main` en `733f6c78c56f5704020ad6c2ff5f0e63f6d054b3`; rama `codex/mtec40-http-multiinstance` creada limpia. El checkout original tenía archivos ajenos sin seguimiento y se conservó.
- El fixture existente crea SQL Server 2022 aislado, TLS verificado, Flyway y dos JVM. La ampliación inicia Spring Boot en cada JVM con un listener HTTPS propio en loopback, keystore efímero y la misma base SQL y clave CSRF.
- El cliente Java confía únicamente en el certificado efímero de la fixture y valida `localhost`. El endpoint público configurado es `https://api.markettec.example`; esta prueba local valida TLS directo al listener, no una pasarela pública ni DNS de producción.
- La sesión y credenciales sintéticas se generan en SQL; procesos, contenedor, certificados y claves se cierran o eliminan al terminar. Ninguna respuesta o log del test imprime tokens.
- CI [#41927457570](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41927457570): SQL Server `16.0.4185.3`, `linux/x86_64`, base `mtec35_isolated`, TLS verificado. Dos JVM/listeners anunciados: PID `3895`/HTTPS `33845` y PID `3937`/HTTPS `39241`.

## Hipótesis y criterios

| ID | Resultado esperado / prueba de refutación | Estado |
|---|---|---|
| H1 | Dos POST concurrentes al mismo refresh producen exactamente un 200 y un 401. | NO REPRODUCIDA: exactamente un ganador. |
| H2 | La generación consumida no devuelve JWT ni `Set-Cookie`; el 401 usa `REFRESH_INVALID`. | NO REPRODUCIDA: perdedor y replay sin credenciales. |
| H3 | Replay posterior deja utilizable la credencial ganadora y una rotación adicional funciona. | NO REPRODUCIDA: segunda rotación 200; replay no cierra la sesión. |
| H4 | Refresh desconocido y CSRF incorrecto dan 401 sin rotación ni revocación. El test multi-JVM existente comprueba `PROOF_INVALID` frente a `ATTEMPT_CONSUMED`. | NO REPRODUCIDA. |
| H5 | Las respuestas de los escenarios contradicen generación SQL o dejan múltiples generaciones activas. | NO REPRODUCIDA: generación 1/2 y una activa. |
| H6 | Los errores 401/403 probados filtran refresh, CSRF, JWT, cookie o detalle SQL. | NO REPRODUCIDA en esas respuestas. |

El caso también comprueba `__Host-`, `Secure`, `HttpOnly`, `SameSite=Lax`, ausencia de `Domain`, Origin permitido/denegado, CORS con credenciales y preflight. La conexión HTTPS es real, sin `X-Forwarded-Proto` ni `MockMvc`.

## Ejecución y límites

- Local: `-Psql-integration -DskipTests test-compile` **PASS**; `-Dtest=SessionRefreshControllerTest test` **PASS**, 5/5.
- SQL/HTTP: **NO VERIFICADO** localmente. El host es ARM64 y la fixture exige Docker Linux x86-64 nativo; el socket Docker local también denegó acceso. No se atribuye este bloqueo a un defecto de seguridad.
- CI: el workflow existente `qa-mtec35-sql.yml` ejecutó `scripts/qa-mtec35-sql.sh` en Ubuntu x86-64: **PASS** en `556bc81`, 285/285 pruebas Java y 57/57 SQL, cero fallos, errores u omisiones; conciliación estricta e inventario PASS. Artefacto [#13370171674](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41927457570/artifacts/13370171674).
- Vulnerabilidades confirmadas: ninguna en los escenarios ejecutados. Correcciones de producción: ninguna. No se prueban errores 5xx, el proxy, DNS, APIM, Azure ni múltiples regiones. Un resultado favorable del fixture no demuestra ausencia general de vulnerabilidades.

## Checkpoint

Rama `codex/mtec40-http-multiinstance`, Draft PR [#19](https://github.com/a-calderon-org/ms-users-market-tec/pull/19); último resultado: CI SQL completo PASS. Archivos: `IsolatedSqlServer.java`, `RefreshWorker.java`, `SqlUserStoreIT.java`, inventario y este informe. Siguiente paso: revisión humana del PR; no fusionar ni desplegar por este resultado aislado.
