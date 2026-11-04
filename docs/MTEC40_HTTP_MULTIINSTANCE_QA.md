# MTEC-40: refresh HTTP entre instancias

## Base y topología

- Base: `origin/main` en `733f6c78c56f5704020ad6c2ff5f0e63f6d054b3`; rama `codex/mtec40-http-multiinstance` creada limpia. El checkout original tenía archivos ajenos sin seguimiento y se conservó.
- El fixture existente crea SQL Server 2022 aislado, TLS verificado, Flyway y dos JVM. La ampliación inicia Spring Boot en cada JVM con un listener HTTPS propio en loopback, keystore efímero y la misma base SQL y clave CSRF.
- El cliente Java confía únicamente en el certificado efímero de la fixture y valida `localhost`. El endpoint público configurado es `https://api.markettec.example`; esta prueba local valida TLS directo al listener, no una pasarela pública ni DNS de producción.
- La sesión y credenciales sintéticas se generan en SQL; procesos, contenedor, certificados y claves se cierran o eliminan al terminar. Ninguna respuesta o log del test imprime tokens.

## Hipótesis y criterios

| ID | Resultado esperado / prueba de refutación | Estado |
|---|---|---|
| H1 | Dos POST concurrentes al mismo refresh producen exactamente un 200 y un 401. | NO VERIFICADA: pendiente CI SQL. |
| H2 | La generación consumida no devuelve JWT ni `Set-Cookie`; el 401 usa `REFRESH_INVALID`. | NO VERIFICADA: pendiente CI SQL. |
| H3 | Replay posterior deja utilizable la credencial ganadora y una rotación adicional funciona. | NO VERIFICADA: pendiente CI SQL. |
| H4 | Refresh desconocido y CSRF incorrecto dan 401 sin rotación ni revocación. La razón interna exacta se comprueba además en el test multi-JVM existente. | NO VERIFICADA: pendiente CI SQL. |
| H5 | Cada respuesta concuerda con generación SQL y queda una sola generación activa. | NO VERIFICADA: pendiente CI SQL. |
| H6 | Los errores no contienen refresh, CSRF, JWT, cookie ni detalle SQL sensible. | NO VERIFICADA: pendiente CI SQL. |

El caso también comprueba `__Host-`, `Secure`, `HttpOnly`, `SameSite=Lax`, ausencia de `Domain`, Origin permitido/denegado, CORS con credenciales y preflight. La conexión HTTPS es real, sin `X-Forwarded-Proto` ni `MockMvc`.

## Ejecución y límites

- Local: `-Psql-integration -DskipTests test-compile` **PASS**; `-Dtest=SessionRefreshControllerTest test` **PASS**, 5/5.
- SQL/HTTP: **NO VERIFICADO** localmente. El host es ARM64 y la fixture exige Docker Linux x86-64 nativo; el socket Docker local también denegó acceso. No se atribuye este bloqueo a un defecto de seguridad.
- CI: el workflow existente `qa-mtec35-sql.yml` ejecuta `scripts/qa-mtec35-sql.sh` en Ubuntu x86-64, con inventario exacto y Flyway. Resultado de esta rama: pendiente de PR.
- No se han demostrado vulnerabilidades ni se ha modificado producción. No se prueban el proxy, DNS, APIM, Azure ni múltiples regiones. Un resultado favorable del fixture no demostraría ausencia general de vulnerabilidades.

## Checkpoint

Rama `codex/mtec40-http-multiinstance`; último resultado: compilación SQL y test de controlador correctos. Archivos: `IsolatedSqlServer.java`, `RefreshWorker.java`, `SqlUserStoreIT.java`, inventario y este informe. Siguiente paso: abrir Draft PR, esperar CI SQL, inspeccionar únicamente el fallo relevante si aparece y actualizar H1–H6 con evidencia.
