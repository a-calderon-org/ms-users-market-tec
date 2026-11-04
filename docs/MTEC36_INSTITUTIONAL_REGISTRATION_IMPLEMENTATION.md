# MTEC-36 — registro Google con confirmación institucional

Estado de este corte: **Draft, deshabilitado por defecto y no habilitable en perfiles reales**. Base: `ms-users/main@3f776f6f609101a0d1db71afe4378b2c81fe11f7`. Contrato candidato: [market_tec PR #25](https://github.com/a-calderon-org/market_tec/pull/25) @ `3e1250a5f77ca90429de3c32b96bf3096b723ac8`, `0.3.0-draft`, sin conformidad B07. No se modificaron Angular, APIM ni el repositorio contractual.

## Auditoría previa a los cambios

| Superficie | Base observada | Objetivo y resultado de este corte |
|---|---|---|
| `GoogleAuthController` | `/v1/auth/google/registrations` respondía 200 `AuthResponse` tras alta inmediata. `/v1/auth/google` era login. | Ruta legacy conservada; 503 con flag apagado, 202 pendiente con flag de prueba. Ruta canónica detecta el propósito persistido y devuelve 202 para register. Login existente sigue 200. |
| `GoogleRegistrationService` | Consumía nonce, creaba cuenta/vínculo en una transacción; creaba sesión/JWT/refresh en otra. | `prepare` solo valida y retiene evidencia; `confirm` crea cuenta, vínculo, sesión y credenciales en una unidad transaccional. |
| `GoogleLoginService` / `SessionAuthority` | Login de vínculo exacto; intento bloqueado con nonce y hash de navegador; emisión de sesión dentro de su transacción. | Sin cambio funcional. La confirmación invoca la autoridad dentro de la transacción externa. |
| `InstitutionalEmailProof` | Allowlist exacta y primitiva de secreto para otro tipo de intento; sin integración en registro. | Se reutiliza únicamente la regla de dominios. El enlace de registro usa verificador separado, ligado a intento/issuer/sub/destinatario. |
| Persistencia | `google_login_intents` guardaba nonce, hash de navegador, propósito, plazo, consumo y versión; `UPDLOCK,HOLDLOCK` y actualización condicional. | Se reutiliza para el desafío Google y se añade `pending_google_registrations` 1:1 para evidencia posterior. No se guarda ID Token ni secreto del enlace. |
| `SqlUserStore` | Transacciones `SERIALIZABLE`, rollback y reintentos limitados de errores SQL conocidos; unicidad del vínculo por hashes issuer/sub. | Sin cambio. La unidad externa de confirmación incluye inserción, vínculo, sesión, emisión y consumo del enlace. |
| HTTP/configuración | Google HTTP solo en perfiles SQL con flags de opt in y topología HTTPS verificada. | Registro tiene flag adicional `users.google.registration.enabled=false`. Si se activa fuera de `sql-integration`, el arranque falla. |
| Pruebas anteriores | Java/SQL aprobaban alta inmediata y correo externo. | Sustituidas por pruebas de estado pendiente, dominio, enlace, rollback, ruta legacy y bloqueo. |

## Flujo implementado

1. `POST /v1/auth/attempts` con `method=google,purpose=register` crea intento de 300 s, nonce y cookie de navegador. Con el flag apagado responde 503 antes de crear estado. Ese plazo procede del intento existente y es **parámetro de prueba no aprobado para producción**.
2. `POST /v1/auth/google` o el alias `POST /v1/auth/google/registrations` exige origen, cliente web, cookie y CSRF. El verificador Google existente comprueba RS256/JWKS, issuer, audiencia, azp, nonce, tiempos, subject y `email_verified`. El servicio consume el nonce. Si el correo verificado pertenece a uno de los tres dominios exactos, guarda issuer, subject y destinatario en estado `PENDING`; si no, responde igualmente 202 sin crear estado de alta. Ninguna rama de esta etapa crea cuenta, vínculo, sesión o credenciales.
3. `POST /v1/auth/email/requests` recibe `attemptId,email`, exige el mismo navegador/CSRF y responde 202 uniforme. El email del body se compara con el destinatario Google retenido; no lo reemplaza. Para coincidencia elegible, crea 32 bytes aleatorios, almacena SHA-256 con separación de propósito e identidad, pasa el secreto temporal al puerto `InstitutionalLinkSender` y avanza a `LINK_ISSUED`. Una segunda solicitud no reemite; una política de reenvío y abuso sigue pendiente.
4. Solo `POST /v1/auth/email/confirm` con `attemptId,token` y el navegador original verifica el enlace y avanza a `CONFIRMED`. GET y HEAD no tienen manejador de confirmación. Rechaza vencimiento, propósito ajeno, navegador ajeno, enlace alterado, consumo previo, vínculo Google existente y correo histórico coincidente. El bloqueo de correo histórico es conservador hasta aprobar un protocolo de namespace/continuidad B02.
5. En la misma transacción SQL de confirmación se crea `userId` opaco, perfil verificado, vínculo Google activo, sesión, refresh y JWT. Una excepción de inserción, vínculo o firma revierte también el consumo del enlace. El resultado no se reemite al repetir el POST; si se pierde la respuesta, se exige un intento nuevo o recuperación aprobada. No hay fusión por correo ni privilegios por dominio.

## Persistencia y tratamiento de secretos

`V11__pending_google_registrations.sql` es aditiva y referencia el intento preexistente. Guarda estado, versión, expiración, correlación HTTP, issuer/subject, destinatario y **solo verificador** del enlace. El secreto existe únicamente en la llamada de entrega y en el POST de confirmación; no se guarda en tabla ni se imprime. El ID Token Google no se persiste. Las filas vencidas dejan de ser aceptadas por comparación temporal aunque permanezcan para una futura política de retención/purga. `UPDLOCK,HOLDLOCK`, versión y restricciones de clave única protegen el consumo entre transacciones; el test de dos instancias de repositorio en un mismo proceso está escrito, pero no ejecutado aquí.

## Entrega y activación

No existe proveedor de correo productivo configurado. El único adaptador conectado en `sql-integration` simula envío **descartando el secreto, sin entrega real**; las pruebas unitarias capturan la llamada mediante un fake. Activar el flag en `sql-dev` o `sql-appservice` falla al arrancar. El flujo no debe habilitarse hasta implementar y comprobar un remitente real con URL HTTPS hacia una página Angular controlada, entrega segura, observabilidad sin secretos, outbox o tratamiento de respuesta/commit inciertos, y controles globales, por origen, intento, destinatario y reenvío. No se fijaron límites de tasa ni TTL productivos arbitrarios. La llamada al puerto ocurre dentro de la transacción y **no constituye un outbox**; el adaptador real deberá resolver la ambigüedad entrega/commit antes de activación.

## Pruebas y límites de la evidencia

**Probado localmente:** suite Java completa `./mvnw -q test`: 279/279, 0 fallos/errores/omitidas. Incluye MockMvc para 202 sin credenciales y 503 por defecto, pruebas de Google/verificador existentes, dominios exactos, enlace alterado, navegador ajeno, doble confirmación, entrega fallida y rollback de fallo JWT con fake. Política C y límites de sesión pasan su regresión existente. `./mvnw -q -Psql-integration -DskipTests test-compile` compila, incluidas dos pruebas SQL nuevas.

**Probado en CI remoto:** [ms-users CI #52, run 41856445886](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41856445886) ejecutó `clean verify` en Ubuntu/Docker nativo `linux/x86_64`: **279/279 Java y 55/55 SQL PASS**, cero fallos, errores u omisiones; inventario estricto y migraciones Flyway PASS. Las dos pruebas SQL nuevas cubren confirmación concurrente con dos instancias de repositorio/transaction manager en **un mismo proceso** y rollback por fallo de firma JWT. El runner local `linux/aarch64` no permite repetir ese harness; la ejecución real SQL procede del CI.

**No probado:** concurrencia entre procesos/instancias reales, Azure, APIM, Angular, navegador real y correo real. Tampoco se probaron recuperación histórica ni entrega con proveedor externo.

**Pendiente/Bloqueado:** política B02 para cuentas existentes, inactivas, desvinculadas y conflictivas; namespace de acceso básico aislado; parámetros B07 de TTL/reenvío/abuso; proveedor de correo/outbox; página Angular; admisión APIM; pruebas multiinstancia. Los vínculos existentes siguen entrando por issuer/sub exacto sin ser convertidos en registros nuevos; no se bloquean masivamente. La continuidad de cuentas históricas sin la nueva prueba sigue requiriendo decisión y evidencia.

## Próximo incremento separado

MTEC-40: auditoría estructurada correlacionada de refresh replay sin secretos, revocación por compromiso confirmado mediante autorización registrada y pruebas multiinstancia. Este cambio no modifica la política C ni `401 REFRESH_INVALID`.
