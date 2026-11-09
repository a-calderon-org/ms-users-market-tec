# MarketTEC Fase II — ms-users: preparación para auditoría Sol 6.1

**Fecha:** 2026-11-08; revisado el mismo día tras la auditoría Sol 6.1 (§F) y la auditoría diferencial Codex (§G). **Ejecutor:** Claude Opus 5.5 (esfuerzo medio). **Repositorio:** `a-calderon-org/ms-users-market-tec`.
**Commit base:** `main@20898978d7f49c38e21b49187250bdd1821c370f` (igual a `origin/main` al iniciar).
**Referencia contractual (solo lectura):** `a-calderon-org/market_tec` `origin/main@236dd1cbdfe9e43656626a9579a0b70fcda2a00e`. El checkout local de `market_tec` estaba en `docs/mtec40-d07-d11-approved@11ee836` con cambios locales ajenos; los documentos se leyeron con `git show origin/main:<ruta>` y ese repositorio no se modificó.
**Cambios:** solo en el árbol de trabajo, sin staging, commits, ramas ni push.

## Los cinco niveles, por separado

| Nivel | Estado | Evidencia |
|---|---|---|
| 1. Backend funcionalmente implementado | **Sí**, para todo lo que autorizan las decisiones vigentes (ver §A y el inventario) | 392 pruebas Java OK. 65 SQL Server OK en la CI de PR #23 sobre la base; pendiente sobre este diff |
| 2. Backend listo para integración | **Parcial.** El código existe tras flags. La integración depende de las decisiones D-04, D-06, D-09 y D-11 y del adaptador Angular real | §B, §D |
| 3. Contratos o decisiones pendientes | **Sí, abiertos:** B07 sin aprobar; D-01, D-02, D-03, D-04, D-06, D-08 a D-14 | §D |
| 4. Listo para despliegue controlado | **No.** Faltan hosts same-site verificados (D-04), APIM MTEC-12, Azure MTEC-37, contención MTEC-41 y la ejecución SQL de este diff | [Manifiesto](MS_USERS_ACTIVATION_MANIFEST.md) |
| 5. Desplegado y validado | **No.** No se desplegó nada ni se validó en Azure | — |

## Inventario de capacidades (Fase 0)

| Capacidad | Código | Pruebas | Flag | Divergencia | Clasificación | Pendiente |
|---|---|---|---|---|---|---|
| Health/readiness | `HealthController`, `SqlReadinessHealthIndicator` | `HealthControllerTest`, `InternalHealthTest`, `SqlReadinessHttpTest` | ninguno | — | Implementado y verificado | Probe real en App Service (MTEC-37) |
| Intento de autenticación | `GoogleAuthController.java:114` | `GoogleAuthControllerTest` | `users.google.http.enabled` | M-01, M-02, M-03, M-04, M-05 | Implementado con divergencia contractual | D-09, D-04 |
| Login Google | `GoogleLoginService`, `GoogleAuthController.java:131` | `GoogleLoginServiceTest`, `NimbusGoogleIdentityVerifierTest`, SQL | `users.google.login-enabled` | M-06, M-08, M-09 | Implementado, pero deshabilitado | D-06 (`REGISTRATION_REQUIRED`) |
| Registro Google inmediato | `GoogleRegistrationService`, `GoogleAuthController.java:137` | `GoogleRegistrationServiceTest`, `GoogleRegistrationSafetyConfigTest`, SQL | `users.google.registration.enabled` | M-07, M-10 | Implementado, pero deshabilitado (coincide con A-04) | Activación RP |
| Sesión (lectura) | `SessionBootstrapController.java:52` | `SessionBootstrapControllerTest` | `users.session.http.enabled` | M-11, M-12, M-13 | Implementado con divergencia contractual | D-09, D-07 conformidad |
| Refresh (política C) | `SessionAuthority.refreshWithSnapshot`, `SessionRefreshController.java:62` | `SessionAuthorityTest` (40), `SessionRefreshControllerTest`, SQL multi-JVM | `users.session.refresh.enabled` | M-14, M-15, M-16 | Implementado, pero deshabilitado | D-10 (`continue`) |
| Logout | `SessionAuthority.logout`, `SessionLogoutController.java:53` | `SessionLogoutControllerTest` | `users.session.logout.enabled` | M-17, M-18 | Implementado, pero deshabilitado | D-12, D-10 (`scope=all`) |
| JWT interno / JWKS / metadata | `InternalJwtService`, `IdentityMetadataController.java:58,64` | `InternalJwtServiceTest` (24), `IdentityMetadataControllerTest` | `users.internal-jwt.enabled` | M-22, M-23 | Implementado con divergencia contractual | D-13 |
| Perfil propio | `UserProfileController.java:40,46,53,59` | `UserProfileControllerTest`, `ProtectedUserOperationsTest`, `UserServiceTest` | `users.api.http.enabled` | M-19, M-20, M-21 | Implementado con divergencia contractual | D-11 (catálogo), D-08 |
| Magic Link | `EmailLoginService`, `EmailAuthController.java:85,110`, `HttpEmailLinkSender` | `EmailLoginServiceTest` (15), `EmailAuthControllerTest` (12), `HttpEmailLinkSenderTest` (9) | `users.email.enabled` | M-24, M-25, M-26 | Bloqueado por decisión externa (A-05 exige 503; B02) | D-02 |
| Actividad de sesión | `AuthApiController.java:54` | `AuthApiControllerTest` | `users.api.http.enabled` | M-27 | Bloqueado por decisión externa (rechaza siempre con 403) | D-03 |
| Pruebas recientes (`proofs/*`) | `RecentProofService`, `AuthApiController.java:60-99` | `RecentProofAndMethodsServiceTest`, `RecentAuthenticationAuthorityTest` | `users.api.http.enabled` | M-28 a M-31, M-34 | Implementado con divergencia contractual | D-14 |
| Métodos de autenticación | `AuthMethodsService`, `AuthApiController.java:112,126` | `RecentProofAndMethodsServiceTest` | `users.auth-methods.linking.enabled` (POST) | M-32, M-33 | Bloqueado por decisión externa | D-14 |
| `refresh mode=continue`, `logout scope=all` | rechazo 400 | controladores | — | M-15, M-18 | No implementado (por decisión) | D-10 |

## A. Implementación realizada en esta tarea

| # | Estado anterior | Problema | Modificación | Archivos | Pruebas |
|---|---|---|---|---|---|
| A1 | `HttpEmailLinkSender` lanzaba `EmailDeliveryException` sin registrar nada, y `ApiErrors` lo convertía en 503 | Un proveedor caído o que rechaza los envíos no dejaba rastro en los logs (Fase 3 y Fase 5, operación) | Se añade el evento `email_delivery_audit` con `channel`, `outcome` y `providerStatus`, más una lista blanca estricta en `SafeJsonLogFormatter`. El constructor recibe un `channel` obligatorio (`login` o `proof`) | `adapters/out/mail/HttpEmailLinkSender.java`, `config/SafeJsonLogFormatter.java`, `config/EmailAuthConfig.java:47-60` | `HttpEmailLinkSenderTest` (6, nuevo): forma de la petición, secreto solo en el fragmento, Bearer, 2xx/no-2xx, IOException, interrupción, fallo cerrado de configuración y descarte de campos o valores ajenos a la lista blanca |
| A2 | El adaptador de correo no tenía ninguna prueba | El rollback y el orden «commit antes de enviar» no estaban demostrados | Solo pruebas, sin cambios de comportamiento: (a) un enlace cuya transacción hace rollback nunca se envía; (b) el envío ocurre después del commit y solo para un intento almacenado; (c) un fallo del proveedor tras el commit no emite sesión | `EmailLoginServiceTest.java` | 3 pruebas nuevas |
| A3 | GET/HEAD sobre las rutas de correo no estaban cubiertos | Riesgo de escáneres y prefetch de enlaces (Fase 3, punto 9) | Solo pruebas: GET y HEAD responden 405 y no tocan el servicio, la sesión ni el repositorio. El enlace usa un fragmento `#`, que nunca llega al servidor | `EmailAuthControllerTest.java` | `getAndHeadNeverReachTheServiceOrConsumeALink` |
| A4 | `EmailAuthController` aceptaba cualquier origen no vacío y dependía de la validación HTTPS de `GoogleAuthController` (misma propiedad) | Defensa en profundidad: si ambos adaptadores se separaran, los orígenes `http`, `*` o con ruta quedarían permitidos | Valida por sí mismo con `GoogleAuthController.validOrigin`, ahora visible en el paquete, y falla cerrado | `EmailAuthController.java:69-71`, `GoogleAuthController.java:275` | `constructionFailsClosedWithoutExplicitHttpsOrigins` |
| A5 | `GoogleHttpDisabledTest` cubría 6 de las 22 rutas | No había evidencia ejecutable de que, con todos los flags apagados, las operaciones de negocio sigan sin exponerse. La superficie completa la cubre ahora `HttpSurfaceInventoryTest` (§F) | Contrato ejecutable: los 14 flags de riesgo valen `false` por defecto y no hay perfil activo; las 21 operaciones restantes responden `404 ROUTE_NOT_FOUND` aunque lleven bearer, cookies, CSRF y Origin, sin `Set-Cookie` ni ACAO; el preflight no concede origen | `DefaultExposureContractTest.java` (nuevo) | 3 pruebas |
| A6 | No existía un manifiesto de activación | Requisito de la Fase 6 | Documento por flag con dependencias, configuración, decisión, pruebas, riesgo y rollback | `docs/MS_USERS_ACTIVATION_MANIFEST.md` | — |

**Revisado sin defecto reproducible**, por lo que no se cambió:

- Google: firma, `iss`, `aud`, nonce, intento ligado al navegador, consumo dentro de la transacción y registro con locks de rango sobre `profiles.correo` (`GoogleRegistrationService.java:62-101`).
- Política C: un replay aislado no revoca la sesión ganadora y responde `401 REFRESH_INVALID` (`SessionAuthority.java:192-240`).
- `exp` del JWT ≤240 s y acotado al deadline. El verificador rechaza `alg`, `jwk`, `jku`, `x5u`, `crit` y `nbf` inválidos (`InternalJwtService.java:52-118`).
- Logout limitado al par exacto vigente o cerrado (`SessionAuthority.java:300-318`).
- Magic Link: verificador SHA-256 con separación de dominio, navegador ligado por cookie y CSRF, umbral bajo `UPDLOCK,HOLDLOCK`, `CONTINUITY_REQUIRED` sin credenciales cuando el correo ya pertenece a una cuenta, sin fusión.
- Logs: lista blanca, sin mensajes de excepción.
- El registro histórico con enlace obligatorio (`PendingGoogleRegistration*`) no tiene rutas. Solo se usa `historicalEmailExists` como bloqueo de continuidad.

**Evaluado y no implementado:**

- **Outbox transaccional de correo.** El envío ya ocurre después del commit (A2) y una falla devuelve 503 sin emitir sesión. Un outbox con reintentos implicaría fijar política de reenvío, cuotas y TTL, que siguen sin aprobar (B02). Sin esa aprobación no está justificado.
- **Catálogo de errores, `ACCESS_TOKEN_EXPIRED`, `REGISTRATION_REQUIRED`, `purpose` obligatorio y mapeo único de `X-MTEC-Client`.** Son cambios de comportamiento que dependen de D-06, D-09 y D-11.

## B. Conciliación de contratos (operaciones ms-users)

Las líneas son de `src/main/java/com/markettec/users/adapters/in/web/`. El detalle de cada divergencia está en `market_tec/docs/MTEC15_B07_RECONCILIATION_MATRIX.md`; esta tarea no cambió ninguna clasificación.

| Operación | Java | Clasificación |
|---|---|---|
| GET `/health` | `HealthController.java:19` | Coincide con el contrato aprobado |
| POST `/v1/auth/attempts` | `GoogleAuthController.java:114` | Diverge y necesita decisión (D-09, D-04) |
| POST `/v1/auth/google` | `GoogleAuthController.java:131` | Diverge y necesita decisión (D-06: M-06) |
| POST `/v1/auth/google/registrations` | `GoogleAuthController.java:137` | Coincide con el borrador; alias aprobado en A-04, OpenAPI Draft |
| GET `/v1/auth/session` | `SessionBootstrapController.java:52` | Diverge y necesita decisión (D-09: M-11, M-12) |
| POST `/v1/auth/refresh` | `SessionRefreshController.java:62` | Coincide con el borrador (política C aprobada); `continue` deshabilitado (D-10) |
| POST `/v1/auth/logout` | `SessionLogoutController.java:53` | Coincide con el borrador; `scope=all` deshabilitado (D-10) |
| GET/PUT `/v1/me/profile` | `UserProfileController.java:40,46` | Coincide con el borrador; catálogo de errores pendiente (D-11) |
| GET/PUT `/v1/usuarios/{userId}` | `UserProfileController.java:53,59` | Coincide con el borrador; ruta pendiente (D-08) |
| GET `/.well-known/jwks.json`, GET `/.well-known/markettec-identity` | `IdentityMetadataController.java:58,64` | Diverge y necesita decisión (D-13) |
| POST `/v1/auth/email/requests`, `/confirm` | `EmailAuthController.java:85,110` | Deshabilitado intencionalmente (A-05). Sin `users.google.http.enabled` la ruta no existe: `404`. Con ese flag y el correo apagado: `503`, pero solo tras superar los controles previos (manifiesto, regla 6) |
| POST `/v1/auth/session/activity` | `AuthApiController.java:54` | Deshabilitado intencionalmente (403 siempre; D-03) |
| POST `/v1/auth/proofs/*` | `AuthApiController.java:60,76,88,99` | Diverge y necesita decisión (D-14) |
| GET/POST `/v1/me/auth-methods` | `AuthApiController.java:112,126` | Diverge y necesita decisión (D-14); el POST está deshabilitado (flag) |
| `refresh mode=continue`, `logout scope=all` | rechazo 400 | No implementado (D-10) |

## C. Evidencia técnica

- **Estado Git inicial:** `main` = `origin/main` = `2089897`. Había trabajo previo sin seguimiento, **preservado e intacto**: `.github/workflows/ci-ms-users.yml` y `.worktrees/`.
- **Archivos modificados (seguidos):** `scripts/mtec35-test-inventory.json`, `EmailAuthController.java`, `GoogleAuthController.java`, `ProtectedApiCorsFilter.java`, `SessionBootstrapController.java`, `SessionRefreshController.java`, `SessionLogoutController.java`, `HttpEmailLinkSender.java`, `EmailAuthConfig.java`, `SafeJsonLogFormatter.java`, `EmailAuthControllerTest.java`, `GoogleAuthControllerTest.java`, `ProtectedApiCorsFilterTest.java` y `EmailLoginServiceTest.java` (14 archivos, +330 / −58).
- **Archivos nuevos:** `HttpEmailLinkSenderTest.java`, `DefaultExposureContractTest.java`, `HttpSurfaceInventoryTest.java`, `HttpSurfaceInventory.java`, `HttpSurfaceInventoryMutationTest.java`, los fixtures de `src/test/java/com/markettec/inventoryfixtures/`, `BrowserOrigins.java`, `BrowserOriginsTest.java`, `docs/MS_USERS_ACTIVATION_MANIFEST.md` y este informe.
- **Entorno:** macOS arm64. JDK local Oracle 24.0.2, sin JDK 21 instalado. El `pom` compila con `release 21`; la CI usa Temurin 21. Docker 29.2.1.
- **Comandos y resultados:**
  - Base: `./mvnw --batch-mode clean verify` → `Tests run: 354, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.
  - Primera entrega: 368 pruebas (14 nuevas).
  - Tras la corrección Sol 6.1: `./mvnw --batch-mode clean verify` → `Tests run: 377, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Incluye ArchUnit (`HexagonalArchitectureTest`, 4) y el empaquetado `target/ms-users-0.1.0-SNAPSHOT.jar`. Las 23 pruebas nuevas respecto de la base se derivaron de los reportes Surefire comparándolos con las fuentes de `2089897`.
  - Tras la corrección Codex: `Tests run: 387, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS (+10, derivados de los reportes).
  - Tras el cierre de los tres bloqueos Codex: `Tests run: 392, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. Se añadieron 5 casos, derivados de los reportes; ArchUnit pasa y `unzip -t` confirma la integridad del JAR.
  - Inventario estricto: `python3 -B scripts/verify-mtec35-reports.py --normal-only` → `executed: 392`, 0 fallos, errores y omitidas. `python3 -B -m unittest discover -s scripts/tests` → 12 OK.
  - SQL: `./mvnw --batch-mode -Psql-integration test-compile` → BUILD SUCCESS (la suite compila).
- **SQL Server real: NO ejecutado sobre este diff.** El usuario aceptó la EULA (2026-11-08) y se ejecutó `MTEC35_SQLSERVER_ACCEPT_EULA=Y ./mvnw --batch-mode --no-transfer-progress -Psql-integration clean verify`. Resultado (sobre la primera entrega): 368/368 pruebas unitarias OK. `SqlUserStoreIT` falló en `IsolatedSqlServer.start()` (`SqlUserStoreIT.java:77`) antes de arrancar el contenedor, con el mensaje `SQL Server integration requires native x86-64 Linux Docker. Detected linux/aarch64; ARM emulation is not an accepted compatibility result.` Es la barrera intencional del repositorio, no un defecto del diff, y no se eludió. `scripts/qa-mtec35-sql.sh` exige igualmente un runner Linux x86-64. La última ejecución real registrada es la CI de PR #23 sobre la base: 65 pruebas OK. El diff no toca adaptadores SQL, migraciones ni consultas, pero la suite debe volver a correr en CI para cerrar la evidencia. No se usó ningún mock en lugar de SQL Server para declarar integración.
- **Secretos:** se buscaron en el diff y en los archivos nuevos patrones PEM, AWS, Slack, JWT, `password=` y `AccountKey`; no hubo coincidencias. Las pruebas solo usan marcadores (`provider-key-marker`) y verifican que nunca aparezcan en el log.
- **Correo:** ninguna prueba envía correo real ni sale de la máquina. El proveedor es un servidor TLS sintético en loopback, con un keystore generado en cada ejecución mediante `keytool`; sus hilos y sockets los crea y cierra la propia prueba.

## D. Dependencias externas (no son faltas de implementación Java)

| Dependencia | Qué bloquea | Responsable |
|---|---|---|
| B07: D-01, D-04, D-06, D-08, D-09, D-11 (catálogo), D-12, D-13 | Integración contractual del núcleo | RP, Angular, APIM, seguridad |
| B02 / D-02 | Magic Link (flag prohibido) y continuidad de buzones reasignados | RP, seguridad |
| B03 / D-03 | Protocolo de evidencia de actividad (endpoint en 403) | RP, seguridad |
| B04 / D-14 | `ProofGrant`, consumo único y vinculación | RP, seguridad |
| D-10 | `mode=continue` y `scope=all` | RP |
| Adaptador Angular real | Hoy el cliente usa mocks (`authCoreV2=false`, PR #34) | Angular (MTEC-10) |
| APIM MTEC-12 | Validación del JWT interno, políticas por operación, CORS, cuotas, preservación de `Set-Cookie`, backend no evadible | APIM, infraestructura |
| Azure MTEC-37 | App Service, Managed Identity, Key Vault, migraciones y probes | Infraestructura |
| Contención MTEC-41 | Retirar la subscription key como supuesto secreto de la SPA. **Sigue abierto** | APIM, seguridad |
| Proveedor de correo | Contrato, dominio remitente, SPF/DKIM y clave en Key Vault | Operación |
| Pruebas E2E | Navegadores reales (Web Locks D-07), cookies same-site, cota residual JWT ≤300 s | QA |
| Ejecución SQL de este diff | Suite SQL Server en CI Linux x86-64 | CI |

## F. Corrección diferencial tras la auditoría Sol 6.1

| Hallazgo | Corrección | Archivos | Pruebas |
|---|---|---|---|
| **P1:** el inventario estricto seguía en `normal_count=354` y el candidato ejecutaba 368, por lo que `verify-mtec35-reports.py --normal-only` fallaba | `normal_count=377`, el conteo real final. Los 23 casos nuevos se añaden a `required_normal_methods` con el esquema existente. El verificador no cambió: sigue detectando casos ausentes, omitidos, duplicados o fallidos, y exige los 65 casos SQL de `SqlUserStoreIT` | `scripts/mtec35-test-inventory.json` | Modo normal: 377, 0 errores. Modo completo: termina con `No XML test reports in failsafe-reports` porque exige reportes Failsafe reales de SQL Server, que solo produce la CI Linux x86-64. No se simularon reportes |
| **P2 timeout:** `HttpRequest.timeout` solo cubría la espera de cabeceras. Una respuesta 202 rápida con el cuerpo retrasado excedía el plazo | `sendAsync` más una espera acotada a 10 s en total (cabeceras y cuerpo). Ante timeout o interrupción se cancela el intercambio, lo que en el JDK ≥16 aborta la conexión. Se hace una sola petición, sin reintentos. Nuevos resultados `timeout` y `cancelled`, con `providerStatus` cuando se observó (aceptación incierta). La operación falla cerrado (503). No crea hilos: usa el executor del `HttpClient` | `HttpEmailLinkSender.java`, `SafeJsonLogFormatter.java` | `HttpEmailLinkSenderTest`: 202 completo, cabeceras 202 rápidas con cuerpo demorado, cuerpo infinito (chunked), proveedor mudo, 503, desconexión e interrupción. Cada caso verifica el plazo efectivo (unos 1,5 s en prueba) y que hubo una sola conexión |
| **P2 origen y HTTPS:** se aceptaba `https://fixture@app…`, y Google y Magic Link no exigían transporte seguro | `GoogleAuthController.validOrigin` (compartido con Magic Link) y `ProtectedApiCorsFilter` rechazan `userinfo`. Google (rutas y preflight) y Magic Link exigen `request.isSecure()`, igual que sesión, refresh y logout. `Forwarded` y `X-Forwarded-Proto` enviados por el cliente no son evidencia. No se cambió la terminación TLS ni la confianza en proxies | `GoogleAuthController.java`, `EmailAuthController.java`, `ProtectedApiCorsFilter.java` | `originsWithUserinfoOrNonOriginPartsAreRejected`, `constructionFailsClosedWithoutExplicitHttpsOrigins` (userinfo, mayúsculas, `/`, query, fragmento, comodín, `null`) y `insecureTransportIsRefusedAndForwardedHeadersAreNotEvidence` (Google y correo) |
| **P2 superficie:** `DefaultExposureContractTest` solo recorría una lista fija; `HEAD/OPTIONS /health`, `/error` y Actuator no estaban descritos | `HttpSurfaceInventoryTest` compara la superficie con un inventario; la primera versión solo leía `RequestMappingHandlerMapping` y los mappings por URL (insuficiente, ver §G). Se clasificaron los métodos implícitos, `/error` y el listener de gestión. `/error` **no** se convirtió en 404: un GET directo da 500 con solo `timestamp`, `status` y `error`, sin divulgación. Actuator sigue solo en loopback | `HttpSurfaceInventoryTest.java`, javadoc de `DefaultExposureContractTest` | `everyEffectiveMappingIsInventoried`, `implicitMethodsOnHealthAreReadOnlyAndGrantNoCors`, `directErrorRouteDisclosesNothing` |
| **Manifiesto** | Reescrito con: la superficie real; flags que actúan fuera de SQL, verificado arrancando el JAR (`internal-jwt` impide el arranque, `google.enabled` arranca sin rutas); claves CSRF obligatorias con perfil SQL; `USERS_SESSION_HTTP_HTTPS_TOPOLOGY_VERIFIED`; la dependencia de correo con JWT, Google HTTP y API; 404 en lugar de 503 cuando la ruta no está registrada; los estados persistido / cookie no entregada / aceptación incierta; que `users.api.http.enabled` registra también actividad y pruebas (D-03, D-14) | `docs/MS_USERS_ACTIVATION_MANIFEST.md` | — |

**Riesgo residual que depende de D-04:** en App Service, Spring Boot activa por defecto el manejo nativo de cabeceras reenviadas, y Tomcat confía entonces en `X-Forwarded-Proto` procedente de rangos de proxy internos. Antes de activar cualquier ruta hay que decidir `server.forward-headers-strategy` y garantizar que el backend no sea accesible salvo por el front-end y APIM.

## G. Corrección diferencial tras la auditoría Codex

**Causa de las dos evasiones del inventario HTTP:**

1. La prueba solo enumeraba `RequestMappingHandlerMapping` y los `AbstractUrlHandlerMapping`. No miraba `RouterFunctionMapping`, de modo que una `RouterFunction` nueva pasaba inadvertida. Tampoco miraba otros mappings de métodos handler: el de health de Actuator (`healthEndpointWebMvcHandlerMapping`), que no es un `RequestMappingHandlerMapping`, también quedaba fuera.
2. La identidad era `ClaseSimple#método [verbos] [rutas]` y se guardaba en un `TreeSet`. Una sobrecarga en otra clase con el mismo nombre simple, el mismo método, verbo y ruta pero con `params`, `headers`, `consumes` o `produces` distintos colapsaba en la misma clave.

**Corrección**, limitada al test y su utilidad:

- `HttpSurfaceInventory.effective(context)` recorre **todos** los `HandlerMapping` del contexto y sus ancestros. La primera versión de esta corrección confiaba en cualquier subclase; ver §H.
- **Mappings de métodos handler** (`AbstractHandlerMethodMapping`): identidad = bean, la representación de Spring del mapping-info con todas las condiciones y `Method.toGenericString()`.
- **`RouterFunctionMapping`**: visitor del framework, con predicados anidados. Las funciones de recursos y las desconocidas se registran como entradas propias.
- **Mappings por URL**: cada patrón, más los handlers raíz y por defecto.
- **Cualquier otro tipo**: `UNENUMERABLE`, que falla salvo autorización explícita.
- La comparación es un multiconjunto exacto y el error muestra lo no inventariado y lo que falta.
- El inventario esperado de la aplicación original son 6 entradas observadas: `/health`, `/**`, dos `/error` y dos de recursos estáticos. El mapping de Actuator del listener principal se enumera y está vacío.

**Pruebas negativas permanentes.** `HttpSurfaceInventoryMutationTest` arranca un contexto real por mutación, con fixtures fuera del component-scan en `com.markettec.inventoryfixtures` (nada se añadió a los controladores productivos). Cada caso usa el mismo `verify` y el mismo `EXPECTED` que el control positivo. Resultado de la ejecución: 7/7 OK.

| Mutación | Alcanzable (MockMvc) | Inventario |
|---|---|---|
| A: `RouterFunction` `GET /audit-uninventoried-functional` | 200 `audit-functional` | **Rechazado** (`FN routerFunctionMapping …`) |
| B: `HealthController#health(HttpServletRequest)` en otro paquete, `GET /health` con `params=auditProbe` | `GET /health?auditProbe=1` → 200 `audit-overload` | **Rechazado** (`params [auditProbe]`, firma completa); `MISSING: []` |
| C: `@GetMapping("/audit-uninventoried-annotated")` ordinario | 200 | **Rechazado** |
| D: `POST` sobre `/health` y alias `/audit-alias`, con `headers` y `consumes` | `POST /health` con cabecera → 200 | **Rechazado** (`POST [/audit-alias \|\| /health], headers […], consumes […]`) |
| E: `SimpleUrlHandlerMapping` con precedencia máxima | 200 | **Rechazado** (`URL auditUrlHandlerMapping …`) |
| F: `HandlerMapping` opaco no enumerable | 200 | **Rechazado** (`UNENUMERABLE auditOpaqueHandlerMapping …`) |
| Control positivo: aplicación original | — | **Aceptado** (`everyEffectiveMappingIsInventoried`) |

**Límite residual:** un filtro de servlet, un `Servlet` adicional o una ruta en el listener de gestión no son `HandlerMapping`. No los cubre esta prueba, sino `InternalHealthTest` (Actuator en loopback) y la revisión de filtros. Se documenta sin extender el alcance.

**P3, canonicalización de orígenes.** `BrowserOrigins.isCanonicalHttpsOrigin` es ahora el único validador. Lo usan Google y correo (vía `GoogleAuthController.validOrigin`), `ProtectedApiCorsFilter` y los tres controladores de sesión, que tenían la misma copia.

- Solo acepta la forma que el navegador serializa en `Origin`: `https://` más el host LDH en minúsculas.
- Rechaza mayúsculas, punto final, `:` vacío, puerto, `userinfo`, ruta (incluida `/`), query y fragmento (incluidos vacíos), comodín, `null`, IDN sin punycode e IP literales.
- Mantiene las exclusiones existentes, ahora sobre el host canónico: `localhost` y `*.invalid`. Añade `*.localhost`, que según RFC 6761 equivale a loopback. `invalid` exacto y los IPv4 de navegador se añadieron en §H.
- No resuelve DNS.
- **Decisión externa:** los orígenes con IP literal se rechazan porque no pueden formar el par same-site aprobado (A-12). Si algún entorno los necesita, hace falta una decisión D-04; mientras tanto se mantiene el rechazo.
- Pruebas: `BrowserOriginsTest`, 3 casos: 6 positivos y 40 variantes negativas, aplicadas tanto al validador como a las 6 listas permitidas y los 4 `public-api-origin` de los adaptadores.

## H. Cierre de los tres bloqueos de la última auditoría Codex

| Hallazgo | Causa | Corrección | Evidencia |
|---|---|---|---|
| **A.** Una subclase de `RequestMappingHandlerMapping`, instalada con `WebMvcRegistrations`, servía `GET /audit-unregistered-mvc` desde `getHandlerInternal` sin que el inventario lo viera | Se confiaba en `instanceof AbstractHandlerMethodMapping`: se suponía que cualquier subclase quedaba descrita por `getHandlerMethods()` | Registro `ENUMERATORS` por **clase exacta** (nombre completamente calificado), con siete implementaciones verificadas. Cualquier otra clase, incluidas las subclases, es `UNENUMERABLE` y no se lee su registro heredado | `OpaqueRequestMappingSubclassMutation`: `/audit-unregistered-mvc` → 200 y `/health` → 200; el inventario falla con `UNENUMERABLE requestMappingHandlerMapping …OpaqueRequestMappingHandlerMapping` (no por un error de arranque) |
| **B.** `PathPatternParser.setCaseSensitive(false)` hacía que `/HEALTH` respondiera 200 con el inventario intacto | La identidad solo usaba `RequestMappingInfo.toString()`, que no refleja la configuración del parser | Entrada `MATCH` por mapping con la semántica efectiva leída de las APIs de Spring 6.2: `usesPathPatterns`, `isCaseSensitive`, `isMatchOptionalTrailingSeparator`, `getPathOptions`, barra final, sufijo, prefijos, negociación, CORS e interceptores. Valores fijados: sensible a mayúsculas y sin separador opcional | `CaseInsensitiveMatchingMutation`: `/health` y `/HEALTH` → 200; el inventario falla por `MATCH … caseSensitive=false`. Revisando otras propiedades se encontró y cubrió una variante sin rutas nuevas, la CORS global con credenciales (`GlobalCorsMappingMutation`: preflight concedido a `https://evil.example`; el inventario falla por `cors=[/**]`) |
| **C.** `BrowserOrigins` aceptaba `https://invalid`, `https://0x7f000001` y `https://0xffffffff`, y con ellos configurados `ProtectedApiCorsFilter` concedía el preflight con credenciales | Solo se excluía `*.invalid`, y la detección de IPv4 seguía a `java.net.URI` en lugar del parser WHATWG de los navegadores | Se excluyen `invalid` y `*.invalid`. Se rechaza todo host cuyo último label «termina en número» según WHATWG (decimal, incluidos octal y abreviadas, o `0x`/`0X` hexadecimal, `0x` solo incluido), sin importar el valor ni el rango. No se resuelve DNS. Los seis consumidores siguen delegando en el mismo validador | `BrowserOriginsTest` (4 casos): las tres reproducciones exactas más 18 formas IPv4 de navegador, aplicadas al validador y a las 6 listas permitidas y los 4 `public-api-origin`. Los positivos siguen pasando, entre ellos `2024.markettec.example` y `0x7f.markettec.example`. `ProtectedApiCorsFilterTest`: no se pueden configurar, y con una lista legítima el preflight con esos orígenes da 403 sin `Access-Control-Allow-*` |

**Otras regresiones:** las 7 mutaciones anteriores siguen detectadas y el control positivo de la aplicación original pasa (`HttpSurfaceInventoryMutationTest` 10/10, `HttpSurfaceInventoryTest` 3/3).

**Limitación conocida:** `java.net.URI` rechaza por conservadurismo un host cuyo último label empieza por dígito (`app.0xfoo`), aunque un navegador lo trataría como dominio. Ningún TLD real empieza por dígito.

## I. Limitaciones conocidas del inventario HTTP (sin corregir)

Codex reprodujo tres limitaciones más de `HttpSurfaceInventory`. **No están resueltas en este incremento.** Son límites del inventario de prueba, no vulnerabilidades explotadas en la configuración productiva actual: todos los flags de riesgo siguen apagados y no se publica ninguna ruta de negocio.

1. **Identidad de clases homónimas:** la identidad del inventario puede no distinguir implementaciones distintas con el mismo nombre de clase.
2. **Semántica de patrones compilados:** la entrada `MATCH` describe el parser configurado, pero no la semántica efectiva de cada patrón ya compilado.
3. **CORS específico por handler:** solo se inventaría la fuente CORS global del mapping, no la configuración CORS por handler (por ejemplo `@CrossOrigin`).

Quedan pendientes para una revisión posterior al Draft PR.

## E. Veredicto

**LISTO PARA AUDITORÍA DIFERENCIAL CODEX**

La parte del backend que se puede implementar bajo las decisiones vigentes está completa y probada: 392 pruebas Java OK, inventario estricto normal OK (392) y la suite SQL compila. Lo que queda abierto son cambios de comportamiento reservados a decisiones externas (§D) o evidencia que solo puede producir otro entorno: la suite SQL Server de este diff en CI, Azure, APIM y el navegador real.

Esto **no** declara que ms-users esté desplegado, en producción, aprobado contractualmente ni listo para exposición real. MTEC-41 sigue bloqueando la exposición.

### Indicaciones para el auditor

1. Reproducir con `./mvnw --batch-mode clean verify` usando JDK 21 y ejecutar `python3 -B scripts/verify-mtec35-reports.py --normal-only` (esperado: 392).
2. Ejecutar `scripts/qa-mtec35-sql.sh` en un runner Linux x86-64 con la EULA aceptada por quien corresponda.
3. Revisar el diff con `git diff 2089897 -- src` y los archivos nuevos listados en §C.
4. Contrastar §B con `market_tec/docs/MTEC15_B07_RECONCILIATION_MATRIX.md` (M-01 a M-34).
