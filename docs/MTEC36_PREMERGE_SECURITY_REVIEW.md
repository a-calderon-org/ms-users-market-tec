# MTEC-36 — revisión técnica de seguridad previa al merge

Fecha: 04-11-2026 (America/Costa_Rica). Revisión ejecutada por Codex; **no es aprobación humana**. PR [ms-users #14](https://github.com/a-calderon-org/ms-users-market-tec/pull/14), base `main@3f776f6f609101a0d1db71afe4378b2c81fe11f7`. HEAD inicial revisado `ce3c37abaae4a5f1f1e30b4ee25a6a8b80588ad4`. El HEAD corregido es el commit que contiene este informe, identificable con `git rev-parse HEAD` en la rama y consignado con su CI en el PR. Contrato de referencia: [market_tec #25](https://github.com/a-calderon-org/market_tec/pull/25) `0.3.0-draft@3e1250a5f77ca90429de3c32b96bf3096b723ac8` (solo lectura, B07 pendiente).

## Alcance y Git

Se actualizaron `origin/main` y `origin/codex/mtec36-institutional-registration`. El PR tenía dos commits y 17 archivos: documento de implementación; inventario de pruebas; `GoogleAuthController`, `GooglePreflightFilter`; repositorio SQL de pendientes, validador de esquema; servicio de registro, puerto de envío y modelos/repo de pendientes; `GoogleLoginConfig`, `SqlUserConfig`, `application.yml`, Flyway V11; pruebas SQL, MockMvc y de servicio. No había cambios fuera de MTEC-36 ni reviews enviadas. El HEAD inicial era descendiente directo de `origin/main`, por lo que no había conflicto de integración en ese corte. La revisión trabajó en el worktree aislado de la rama, sin alterar los cambios sin seguimiento del árbol principal.

Los cambios posteriores de esta revisión son: validación independiente de arranque y remitente inerte en `GoogleRegistrationSafetyConfig`, ajuste de inyección en `GoogleLoginConfig`, pruebas de perfiles y de intento anterior, actualización del inventario y este informe.

## Matriz de amenazas y controles

| Amenaza | Control verificado | Límite |
|---|---|---|
| Activación accidental en perfil real | Flag `false` por defecto; nueva validación de arranque rechaza `true` en `sql-dev` y `sql-appservice` aun sin login/HTTP; perfiles mixtos rechazados. | La configuración futura de correo requiere nueva revisión. |
| Alta por ruta legacy o login | Alias legacy devuelve 503 apagado o 202 pendiente; ruta canónica distingue propósito persistido; `GoogleLoginService.complete` solo acepta `LOGIN`. | La clasificación pública 401/409/410 puede revelar estado a quien ya posee intento y cookie. |
| Falsificación/replay de Google | Verificador existente valida firma, issuer, audience/azp, nonce, tiempos, subject y correo verificado; nonce se consume. | Depende de la configuración y claves Google existentes. |
| Correo parecido o controlado por solicitante | Allowlist de dominio exacto; destinatario inmutable de la evidencia Google; acuse 202 uniforme. | No hay entrega real. |
| Robo, alteración o doble uso del enlace | 32 bytes aleatorios; solo hash ligado a intento/issuer/sub/correo; navegador y CSRF; POST; bloqueo SQL, estado y versión. | Persisten filas vencidas hasta definir retención. |
| Cuenta/vínculo/credenciales anticipados | `prepare` solo escribe intento/pendiente; `requestLink` solo verificador; `confirm` crea cuenta, vínculo, sesión, refresh y JWT en transacción. | Respuesta perdida tras commit no reemite credenciales. |
| Carrera o fallo de firma/SQL | SQL `SERIALIZABLE`, `UPDLOCK,HOLDLOCK`, versión, unicidad; rollback en fallo JWT probado en SQL real del HEAD inicial. | Dos repositorios en un proceso no equivalen a dos procesos. |
| Exposición de secretos | V11 guarda hash del enlace, nunca ID Token, secreto, JWT, refresh o CSRF plano; error HTTP genérico y logging de ruta/estado/correlación. | Correo, issuer/sub y correlación son datos sensibles retenidos; falta política de purga. |

## Feature flags y perfiles

`users.google.registration.enabled` lee `USERS_GOOGLE_REGISTRATION_ENABLED` y por defecto es `false`. El endpoint HTTP depende además de `users.google.http.enabled=true`; los servicios Google dependen de `users.google.login-enabled=true`. La barrera de producción ahora se crea **independientemente** de ambos flags. Con `true`, `sql-dev` y `sql-appservice` fallan al arrancar; `sql-integration` solo permite el remitente que descarta el secreto y necesita un `DataSource` aportado por el fixture. Un perfil mixto con perfil real se rechaza. Valor malformado falla la conversión de propiedad. Ausencia y `false` explícito dejan el remitente inerte y los endpoints de registro en 503 si HTTP se habilita. Sin HTTP, no se registra controlador.

Hallazgo corregido **P1**: en el HEAD inicial la validación estaba dentro de `GoogleLoginConfig`, condicionado a `login-enabled=true`; `registration.enabled=true` podía permanecer configurado sin fallo de arranque cuando login estaba apagado. No se observó un alta por ese camino, pues faltaban servicios y rutas, pero no satisfacía la garantía exigida de rechazo independiente. La validación se trasladó a una configuración activa en todos los perfiles SQL y se añadió prueba de arranque con HTTP/login apagados. `sql-integration` en un entorno real sin datasource falla al arrancar; aun con uno aportado, el remitente sigue siendo inerte. El uso deliberado de ese perfil fuera del fixture sería una mala configuración operativa a vigilar.

## Rutas HTTP y servicio

| Ruta | Flag apagado | Flag de prueba activo |
|---|---|---|
| `POST /v1/auth/attempts` `register` | 503 antes de insertar intento | 200, intento de registro y prueba de navegador; ninguna credencial |
| `POST /v1/auth/google` | Login de vínculo existente; intento `REGISTRATION` rechazado por propósito | Login 200 o registro 202 según propósito persistido |
| `POST /v1/auth/google/registrations` | 503 | 202 pendiente, igual que ruta canónica |
| `POST /v1/auth/email/requests` | 503 | 202 uniforme; solo destinatario original puede recibir la simulación |
| `POST /v1/auth/email/confirm` | 503, incluso para intentos previos | Única ruta de registro que puede responder con sesión/credenciales |

`begin` inserta intento con nonce, hash del navegador, propósito y plazo de 300 s. `prepare` verifica Google y consume nonce dentro de transacción; para correo elegible y vínculo inexistente inserta estado `PENDING`, sin crear cuenta. `requestLink` exige intento consumido y navegador original, bloquea pendiente, compara destinatario, guarda hash y pasa el secreto al remitente; no reemite. `confirm` comprueba plazo, propósito, navegador, enlace/estado, allowlist, identidad y correo histórico; bloquea fila y crea cuenta, vínculo, sesión y credenciales en la misma transacción. El estado pasa a `CONFIRMED`. Excepciones antes del commit revierten los cambios. Un segundo POST recibe 409; enlace alterado recibe 401; intento vencido, 410. Los errores no incluyen secretos. La correlación se valida como texto acotado y no es un secreto de autorización.

El login existente conserva resolución exacta por `(issuer,subject)`, cuenta/vínculo `ACTIVE`, nonce y propósito `LOGIN`; no enlaza por correo. La emisión RS256, cookie Secure/HttpOnly/SameSite, sesión 60/30/8, refresh replay y logout no se modificaron en este PR y pasan sus pruebas de regresión. El nuevo test demuestra que un intento `REGISTRATION` anterior no se convierte en login tras apagar el flag.

## Persistencia y atomicidad

V11 solo crea `pending_google_registrations` e índice de caducidad; no altera migraciones previas. FK al intento, PK por intento, checks de estado/verificador y versión no negativa. `SqlSchemaValidator` exige V11 por Flyway, PK/FK/checks y tipos; por ello una base anterior sin migrar falla cerrada al reiniciar. La migración puede aplicarse con el flag apagado sin abrir rutas de alta. `SqlPendingGoogleRegistrationRepository` exige transacción, usa `UPDLOCK,HOLDLOCK` y actualización condicional de estado/versión. La tabla retiene correo, issuer, subject, correlación y verificador hasta una futura política de purga. No hay token Google, secreto original del enlace, JWT, refresh ni CSRF plano en V11.

**Entrega y commit:** `requestLink()` invoca el remitente dentro de la transacción. Hoy el único remitente configurado descarta el secreto sin I/O, logging ni efecto externo, así que no hay correo real que pueda quedar huérfano. Con proveedor real, un envío confirmado seguido de rollback daría un enlace inutilizable; un reintento por deadlock podría duplicar el envío. Es **P2, bloqueante para habilitación productiva**. El siguiente incremento debe separar intención persistente y entrega con outbox transaccional (o protocolo equivalente), idempotencia, manejo de respuesta perdida y política de expiración/reenvío; revisar antes de conectar el proveedor. No se implementó outbox aquí.

## Resultados y hallazgos

El baseline remoto del HEAD inicial fue [CI #53](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41856989617): 279/279 Java y 55/55 SQL Server real en Linux x86-64, inventario estricto y Flyway aprobados. La revisión ejecutó localmente `./mvnw -q clean test`: **283/283 Java**, sin fallos/errores/omisiones; `python3 scripts/verify-mtec35-reports.py --normal-only` aprobó el inventario; `./mvnw -q -Psql-integration -DskipTests test-compile`, JSON, `git diff --check` y revisión de secretos aprobaron. Los cuatro casos Java adicionales son tres pruebas de configuración y una de intento de registro anterior; la prueba MockMvc existente se amplió sin sumar caso. Una ejecución inicial en sandbox falló en siete casos por `SocketException: Operation not permitted` al abrir Tomcat; se repitió fuera de esa restricción y pasó completa. El entorno local macOS/aarch64 no cumple el preflight SQL nativo. La evidencia de 55 SQL Server reales, Flyway e inventario completo del **HEAD final** debe provenir de CI remoto, no de compilación local.

| Severidad | Hallazgo | Estado |
|---|---|---|
| P0 | Ninguno demostrado | — |
| P1 | Validación de perfil real dependía de `login-enabled` | Corregido con prueba de arranque |
| P2 | Correo real/outbox, abuso/TTL/reenvío, Angular/APIM, B02/B07, prueba multiinstancia y purga de datos | Pendientes para producción |
| P3 | Documentación de `sql-integration` y límites de evidencia | Registrada aquí |

**Condiciones para producción:** contrato B07 aprobado y continuidad B02 definida; proveedor y outbox revisados; controles de abuso y TTL aprobados; página Angular y APIM con pruebas de navegador; pruebas SQL multiinstancia, despliegue Azure y observabilidad sin secretos; política de retención. El merge inactivo, si se recomienda tras CI final, no autoriza ninguna de estas activaciones ni cierra MTEC-36.

**Conclusión de merge: A — APTO PARA MERGE INACTIVO**, condicionada a que el CI del HEAD publicado confirme 283/283 Java y 55/55 SQL Server reales sin fallos, errores ni omisiones. El PR registrará la ejecución y el SHA que satisfagan esa condición. No fusionar ni aprobar en nombre de una persona revisora.
