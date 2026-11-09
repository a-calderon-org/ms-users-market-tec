# ms-users — Manifiesto de activación de flags

**Fecha:** 2026-11-08, revisado tras la auditoría Sol 6.1. **Base:** `main@20898978d7f49c38e21b49187250bdd1821c370f` más el diff local descrito en [PHASE2_SOL61_AUDIT_READINESS.md](PHASE2_SOL61_AUDIT_READINESS.md).

**Estado:** inventario técnico. **No es una aprobación operativa ni autoriza activar nada.** Cada activación requiere la aprobación indicada en la columna «Contrato / decisión» y la evidencia de «Pruebas previas». Este documento no fija valores secretos.

## 1. Superficie HTTP real con los valores por defecto

Valores por defecto: sin perfil SQL y los 13 flags de riesgo en `false`. La superficie la fija `HttpSurfaceInventoryTest` mediante `HttpSurfaceInventory`, que consulta todos los beans `HandlerMapping` del contexto y sus ancestros, con la misma búsqueda que el `DispatcherServlet`.

**Qué se enumera.** Solo se enumeran mappings cuya **clase de ejecución es exactamente** una de las verificadas: `RequestMappingHandlerMapping`, `AdditionalHealthEndpointPathsWebMvcHandlerMapping` (Actuator), `RouterFunctionMapping`, `SimpleUrlHandlerMapping`, `BeanNameUrlHandlerMapping`, `WelcomePageHandlerMapping` y `WelcomePageNotAcceptableHandlerMapping`. Se comparan por nombre completamente calificado. De los dos de welcome page se revisó el bytecode: delegan en la resolución del registro y como mucho la restringen.
- Una **subclase** de cualquiera de estas clases no se considera de confianza, porque puede servir rutas desde `getHandlerInternal` sin registrarlas.
- Todo mapping no verificado aparece como `UNENUMERABLE <bean> <clase>` y hace fallar la prueba salvo que se autorice conscientemente en el inventario, con un enumerador verificado.

**Identidad de cada entrada:**
- **Mappings de métodos handler:** la representación de Spring de la condición (método, patrones, `params`, `headers`, `consumes`, `produces` y condición propia) más la firma genérica del método.
- **Rutas funcionales:** cada hoja del visitor, con los predicados anidados.
- **Mappings por URL:** cada patrón con el tipo de su handler, más los handlers raíz y por defecto.

**Semántica de matching.** Cada mapping enumerado aporta además una entrada `MATCH` con la configuración efectiva que decide qué peticiones acepta un patrón:
- si usa `PathPattern` o `PathMatcher`;
- del parser: sensibilidad a mayúsculas, separador final opcional, separador y decodificación;
- coincidencia por barra final y por sufijo;
- prefijos de ruta y estrategias de negociación de contenido;
- fuente CORS e interceptores.

La aplicación actual fija que el matching es **sensible a mayúsculas** y no acepta separador final opcional. Cambiar cualquiera de estos valores exige actualizar el inventario conscientemente.

La comparación es un multiconjunto exacto y no deduplica entradas. `HttpSurfaceInventoryMutationTest` demuestra en contextos reales que la prueba rechaza nueve mutaciones:
- una ruta funcional;
- una sobrecarga de `health()` con `params`;
- un `@GetMapping` ordinario;
- otro verbo con un patrón alias y condiciones;
- un `SimpleUrlHandlerMapping`;
- un `HandlerMapping` opaco;
- una subclase de `RequestMappingHandlerMapping` que sirve una ruta oculta desde `getHandlerInternal`;
- un parser insensible a mayúsculas;
- una configuración CORS global con credenciales.

**Fuera del alcance de este inventario**, porque no son `HandlerMapping`: los filtros de servlet, los servlets adicionales, los handlers del contenedor y el listener de gestión. Este último lo cubre `InternalHealthTest`. El inventario **no** afirma cubrir toda la superficie HTTP.

| Elemento | Tipo | Comportamiento | Evidencia |
|---|---|---|---|
| `GET /health` | Ruta explícita | 200 `HealthResponse` | `HealthControllerTest` |
| `HEAD /health` | Método implícito de Spring MVC | 200 sin cuerpo, sin CORS ni cookies | `implicitMethodsOnHealthAreReadOnlyAndGrantNoCors` |
| `OPTIONS /health` | Método implícito de Spring MVC | 200 con `Allow: GET, HEAD, OPTIONS`; no concede CORS | idem |
| `/**` (`NotFoundController`) | Fallback explícito, todos los métodos | `404 ROUTE_NOT_FOUND` con envelope uniforme | `DefaultExposureContractTest`, `RequestLoggingTest` |
| `/error` (`BasicErrorController`) | Manejo de errores de Spring Boot | Un `GET /error` directo, sin atributos de error, responde 500 con solo `timestamp`, `status` y `error`: sin mensaje, excepción, traza ni datos de la petición. Es un status engañoso, no una divulgación, y no se forzó a 404 | `directErrorRouteDisclosesNothing` |
| `/**`, `/webjars/**` (handlers de recursos estáticos) | Configuración automática de Boot | No hay recursos empaquetados; la respuesta es 404 | `everyEffectiveMappingIsInventoried` |
| `routerFunctionMapping` | Rutas funcionales de Spring MVC | No hay ninguna `RouterFunction` registrada; cualquiera que aparezca se enumera y hace fallar la prueba | idem + `FunctionalRouteMutation` |
| `healthEndpointWebMvcHandlerMapping` (Actuator en el listener principal) | Rutas adicionales de health | Se enumera y está vacío, porque `management.endpoint.health.probes.add-additional-paths=false` | idem |
| Listener de gestión `127.0.0.1:${MANAGEMENT_PORT:9091}` | Actuator, **solo loopback** | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` sin detalles. No se publica en el puerto principal | `InternalHealthTest` |

Las 21 operaciones de negocio de ms-users de la matriz B07, distintas de `getUsersHealth`, responden `404 ROUTE_NOT_FOUND` aunque la petición lleve bearer, cookies, CSRF y Origin. No emiten `Set-Cookie` ni conceden CORS, y su preflight no concede origen (`DefaultExposureContractTest`).

## 2. Reglas generales

1. **El perfil SQL no hace inerte todo flag.** Los controladores de autenticación, la sesión y el Magic Link exigen uno de los perfiles `sql-dev | sql-appservice | sql-integration`. Fuera de SQL, dos flags tienen efecto, ambos verificados arrancando el JAR:
   - `users.internal-jwt.enabled=true` registra `IdentityMetadataController`, que no tiene perfil, pero el keyring solo existe con perfil SQL. **El arranque falla**, de modo que falla cerrado.
   - `users.google.enabled=true` crea el verificador Google y su `HttpClient`, que descarga el JWKS de Google cuando se usa. **No registra rutas.**
2. **Con cualquier perfil SQL**, `USERS_CSRF_KEYS_JSON` y `USERS_CSRF_ACTIVE_KID` son obligatorios aunque todos los flags estén apagados. Lo exige el bean `CsrfKeys` (`SqlUserConfig.java:120-123`); sin ellos el arranque falla.
3. **Barreras de evidencia de topología.** `users.google.http.same-site-verified` (`USERS_GOOGLE_HTTP_SAME_SITE_VERIFIED`) y `users.session.http.https-topology-verified` (`USERS_SESSION_HTTP_HTTPS_TOPOLOGY_VERIFIED`) solo pueden ponerse en `true` con hosts reales demostrados (D-04). Si los adaptadores correspondientes están activos y estos flags no, el arranque falla.
4. **Transporte entrante.** Los adaptadores Google, Magic Link, sesión, refresh y logout exigen `request.isSecure()`. Ese valor lo decide el contenedor, nunca una cabecera `Forwarded` o `X-Forwarded-Proto` enviada por el cliente; las pruebas lo verifican con MockMvc. **Pendiente de D-04:**
   - En Azure App Service, Spring Boot detecta la plataforma y, salvo que se fije `server.forward-headers-strategy`, activa el manejo nativo de cabeceras reenviadas. Tomcat confía entonces en `X-Forwarded-Proto` procedente de rangos de proxy internos.
   - La estrategia de proxy y la imposibilidad de acceso directo al backend son decisiones de infraestructura que no se fijaron aquí.
5. **Rollback.** Basta volver el flag a `false` (o eliminar la variable) y reiniciar. Ningún flag ejecuta migraciones ni borra datos. Desactivar rutas no revoca las sesiones ya emitidas: para eso hay un procedimiento operativo aparte (MTEC-40).
6. **`404` frente a `503`.**
   - `404 ROUTE_NOT_FOUND` significa que la ruta **no está registrada**: su flag está apagado o falta su perfil. No se ejecuta ningún control propio de la operación.
   - `503 AUTH_TEMPORARILY_UNAVAILABLE` significa que la ruta **está registrada**, la petición **superó los controles previos** del adaptador y una capacidad posterior no está disponible.
   - Ejemplo de controles previos en Magic Link: transporte seguro, `Origin` permitido, `X-MTEC-Client: web` y forma del cuerpo. En la confirmación, además, la cookie de intento y el CSRF.
   - Una petición que falla un control previo recibe ese error (`403`, `400`) y no `503`.
7. **Prohibidos hasta que se resuelva la decisión indicada:**
   - `users.email.enabled` (D-02/B02).
   - `users.auth-methods.linking.enabled` (D-14/B04).
   - `users.api.http.enabled`, mientras D-14 no decida si las rutas `proofs/*` pueden exponerse (ver la fila del flag).
   - `users.google.registration.enabled`, sin conformidad D-06.

## 3. Flags

| Flag (variable) | Defecto | Dependencias (el arranque falla si faltan) | Configuración obligatoria | Contrato / decisión | Pruebas previas | Qué registra / riesgo | Rollback |
|---|---|---|---|---|---|---|---|
| `users.internal-jwt.enabled` (`USERS_INTERNAL_JWT_ENABLED`) | `false` | Perfil SQL; fuera de SQL el arranque falla | `USERS_INTERNAL_JWT_ISSUER` (https), `USERS_INTERNAL_JWT_AUDIENCE`, `USERS_INTERNAL_JWT_PUBLIC_JWKS_URI`, `USERS_INTERNAL_JWT_ACTIVE_KID`, `USERS_INTERNAL_JWT_KEYS_JSON` (RSA-2048 privada, Key Vault) | A-06 y A-07 aprobados; `iss`/`aud`, caché y rotación pendientes (D-13, M-22, M-23) | `InternalJwtServiceTest`, `IdentityMetadataControllerTest`; vectores contra APIM y backends (MTEC-12) | Publica `/.well-known/jwks.json` y `/.well-known/markettec-identity`; un `aud` compartido permite replay entre servicios | `false`; si APIM ya valida tokens, retirarlo antes de APIM |
| Claves CSRF (`USERS_CSRF_ACTIVE_KID`, `USERS_CSRF_KEYS_JSON`) | vacío | **Obligatorias con cualquier perfil SQL**, con o sin flags | Claves HMAC versionadas, como máximo 16 (Key Vault) | A-08 | `CsrfKeysTest`, `SessionAuthorityTest` | Derivan el CSRF de sesión, refresh y logout | Rotar el `kid` activo y conservar el anterior hasta que venzan las sesiones (8 h) |
| `users.google.enabled` (`USERS_GOOGLE_VERIFIER_ENABLED`) | `false` | — (actúa incluso fuera de SQL) | `USERS_GOOGLE_AUDIENCES`; issuers y JWKS de Google por defecto | A-01, A-04 | `NimbusGoogleIdentityVerifierTest`, `CachedGooglePublicKeysTest` | Solo crea el verificador y su cliente HTTP; sin rutas | `false` |
| `users.google.login-enabled` (`USERS_GOOGLE_LOGIN_ENABLED`) | `false` | Perfil SQL, `users.google.enabled`, `users.internal-jwt.enabled` | `USERS_GOOGLE_LOGIN_AUDIENCE` | A-04; M-06 (`REGISTRATION_REQUIRED`) pendiente de D-06 | `GoogleLoginServiceTest`, SQL `SqlUserStoreIT` | Emite sesiones para vínculos ACTIVE existentes | `false` |
| `users.google.registration.enabled` (`USERS_GOOGLE_REGISTRATION_ENABLED`) | `false` | `users.google.login-enabled` y `users.google.http.enabled` (`GoogleRegistrationSafetyConfig`) | — | A-04 aprobado (registro inmediato, `200 AuthResponse`); activación por RP (D-06) | `GoogleRegistrationServiceTest`, `GoogleRegistrationSafetyConfigTest`, concurrencia SQL | Crea cuentas nuevas | `false`; las cuentas creadas se conservan |
| `users.google.http.enabled` (`USERS_GOOGLE_HTTP_ENABLED`) | `false` | Login Google; `USERS_GOOGLE_HTTP_SAME_SITE_VERIFIED=true`; `USERS_GOOGLE_HTTP_PUBLIC_API_ORIGIN` y `USERS_GOOGLE_HTTP_ALLOWED_ORIGINS` en forma canónica: `https://` más el host en minúsculas, sin `userinfo`, puerto ni `:` vacío, ruta, query, fragmento, comodín, punto final ni IP literal. Se rechazan los hosts que un navegador interpretaría como IPv4 (regla WHATWG «ends in a number»: decimal, octal, abreviada, `0x…`) y se excluyen `localhost`, `*.localhost`, `invalid` y `*.invalid` (`BrowserOrigins`) | — | D-04, D-06, D-09 (M-01 a M-05) | `GoogleAuthControllerTest`, `GoogleHttpDisabledTest`, `DefaultExposureContractTest`; prueba de navegador real | Registra `/v1/auth/attempts`, `/v1/auth/google`, `/v1/auth/google/registrations` **y `EmailAuthController`** (`/v1/auth/email/requests`, `/confirm`) con sus preflights. Con el correo apagado, esas dos rutas responden `503 AUTH_TEMPORARILY_UNAVAILABLE` solo a peticiones que superan sus controles previos (regla 6) | `false` |
| `users.google.http.same-site-verified` (`USERS_GOOGLE_HTTP_SAME_SITE_VERIFIED`) | `false` | — | — | D-04 (hosts reales) | Evidencia de hosts SWA y APIM same-site HTTPS | Ponerlo en `true` sin evidencia anula la barrera | `false` (el arranque falla si Google HTTP sigue activo) |
| `users.session.http.enabled` (`USERS_SESSION_HTTP_ENABLED`) | `false` | Perfil SQL; `USERS_SESSION_HTTP_HTTPS_TOPOLOGY_VERIFIED=true`; `USERS_SESSION_HTTP_ALLOWED_ORIGINS` y `USERS_SESSION_HTTP_PUBLIC_API_ORIGIN` HTTPS | Claves CSRF | D-07 (política aprobada), D-09 (M-11, M-12) | `SessionBootstrapControllerTest` | `GET /v1/auth/session`: lectura que entrega CSRF | `false` |
| `users.session.http.https-topology-verified` (`USERS_SESSION_HTTP_HTTPS_TOPOLOGY_VERIFIED`) | `false` | — | — | D-04 | Igual que `same-site-verified` | Igual | `false` |
| `users.session.refresh.enabled` (`USERS_SESSION_REFRESH_ENABLED`) | `false` | Topología de sesión verificada; `users.internal-jwt.enabled` | Claves CSRF | A-09, A-10 (política C); D-10 para `mode=continue` | `SessionRefreshControllerTest`, `SessionAuthorityTest`, SQL multi-JVM (MTEC-40) | Rota el refresh y emite JWT | `false`: cada navegador se reautentica cuando vence su JWT |
| `users.session.logout.enabled` (`USERS_SESSION_LOGOUT_ENABLED`) | `false` | Topología de sesión verificada | Claves CSRF | D-12; `scope=all` pendiente (D-10/D-14) | `SessionLogoutControllerTest` | Solo revoca | `false` |
| `users.api.http.enabled` (`USERS_API_HTTP_ENABLED`) | `false` | Perfil SQL; `users.internal-jwt.enabled`; `USERS_API_HTTP_ALLOWED_ORIGINS` HTTPS | — | D-08, D-11 (catálogo); **D-03** para la actividad; **D-14** para las pruebas de métodos | `UserProfileControllerTest`, `AuthApiControllerTest`, `ProtectedApiCorsFilterTest` | **No registra solo Perfil.** Registra `GET/PUT /v1/me/profile` y `/v1/usuarios/{userId}`, `POST /v1/auth/session/activity` (siempre 403), los cuatro `POST /v1/auth/proofs/*` (formas divergentes, M-28 a M-31), `GET /v1/me/auth-methods` y `POST /v1/me/auth-methods` (503 salvo con linking). Las rutas `proofs/email/*` responden 503 si el correo está apagado | `false` |
| `users.gateway.user-token-relocation.enabled` (`USERS_GATEWAY_USER_TOKEN_RELOCATION_ENABLED`) | `false` | Autenticación de Container Apps delante de ms-users y APIM generado con `--gateway-auth-resource` (market_tec `infra/apim`) | — | MTEC-12/D04; contrato del encabezado `X-MTEC-User-Authorization` **Proposed** | `GatewayUserTokenFilterTest` | No registra rutas: Java lee el JWT interno de `X-MTEC-User-Authorization` en lugar de `Authorization` (que lleva el token Entra de APIM); cada valor sigue verificándose | `false` (solo si la autenticación de Container Apps está desactivada) |
| `users.auth-methods.linking.enabled` (`USERS_AUTH_METHOD_LINKING_ENABLED`) | `false` | `users.api.http.enabled` | — | **B04/D-14 sin aprobar: prohibido** | `RecentProofAndMethodsServiceTest` | Vincula identidades a cuentas existentes | `false` |
| `users.email.enabled` (`USERS_EMAIL_ENABLED`) | `false` | Perfil SQL; **`users.internal-jwt.enabled`** (el servicio necesita `InternalJwtService`); `USERS_EMAIL_PROVIDER_ENDPOINT` (https), `USERS_EMAIL_PROVIDER_API_KEY` (Key Vault), `USERS_EMAIL_FROM`, `USERS_EMAIL_LOGIN_LINK_BASE_URL` y `USERS_EMAIL_PROOF_LINK_BASE_URL` (https, sin query ni fragmento) | Proveedor de correo contratado | **A-05; B02/D-02 sin aprobar: prohibido** | `EmailLoginServiceTest`, `EmailAuthControllerTest`, `HttpEmailLinkSenderTest` | Envía correo real y emite sesiones por posesión del buzón. **Las rutas solo existen con `users.google.http.enabled`** (login) o `users.api.http.enabled` (pruebas). Sin esos flags responden **404, no 503** | `false`: las rutas registradas vuelven a 503 |

## 4. Estados de un envío de Magic Link

El envío ocurre **después** del commit del intento y nunca se reintenta automáticamente.

| Situación | Fila del intento | Cookie de navegador | Respuesta | ¿Enlace canjeable? | `email_delivery_audit` |
|---|---|---|---|---|---|
| El proveedor acepta (2xx completo) | Persistida | Entregada | 202 | Sí, desde ese navegador | `accepted` + status |
| El proveedor rechaza (no-2xx) | Persistida (cuenta para el límite de 3 cada 15 min) | **No entregada** | 503 | No: la confirmación exige la cookie | `provider_rejected` + status |
| Fallo de transporte o desconexión | Persistida | No entregada | 503 | No | `transport_failed` |
| Plazo de 10 s vencido (cabeceras o cuerpo) | Persistida | No entregada | 503 | **Aceptación incierta:** el correo puede llegar, pero el enlace no se puede canjear sin la cookie | `timeout`, con el status si se llegó a observar |
| Petición interrumpida o cancelada | Persistida | No entregada | 503 | Igual que el caso anterior | `cancelled` |
| Transacción revertida | No existe | No entregada | Error | No se envía nada | — |

En todos los casos `accepted` significa que el proveedor aceptó el mensaje por HTTP, no que llegara al buzón. Reenvío, deduplicación y outbox dependen de B02 y no se implementaron.

## 5. Valores de política fijados en código

Requieren aprobación antes de exponerse:

- Magic Link: TTL de 10 min y 3 solicitudes por dirección cada 15 min (`EmailLoginService.java:28-30`); número máximo de fallos por intento en `EmailLoginAttempt.MAX_FAILURES`.
- Plazo total del envío al proveedor: 10 s (`HttpEmailLinkSender.DEFAULT_DEADLINE`).
- Intento Google y de registro: 300 s (`GoogleLoginService.java:49`, `GoogleRegistrationService.java:51`).
- JWT: TTL de 240 s como máximo, acotado al primer plazo de la sesión (`InternalJwtService.java:59-63`).
- Sesión 60/30/8: `Session.java` (A-03).
- `Cache-Control: public, max-age=30` en JWKS y metadata (M-22).

## 6. Observabilidad

- `request_completed`: método, plantilla de ruta, status y duración.
- `refresh_audit`: resultado de la política C (`C-1`) y `sessionRef` (16 hex).
- `email_delivery_audit`: `channel` (`login` o `proof`); `outcome` (`accepted`, `provider_rejected`, `transport_failed`, `timeout` o `cancelled`); y `providerStatus`, solo si se observó. No incluye destinatario, enlace, secreto, clave ni cuerpo.
- Cualquier otro evento se registra como `runtime_event` con solo `exception_type`.
