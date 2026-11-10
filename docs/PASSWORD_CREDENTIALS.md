# Contraseñas institucionales (correo + contraseña) y continuidad B02

Incremento de autenticación de MarketTEC. Reutiliza la arquitectura hexagonal, SQL, sesiones, JWT y Magic Link existentes; no
añade servicios ni mecanismos de prueba de identidad. Apagado por defecto: `USERS_PASSWORD_ENABLED=false`.

## Qué hace

| Operación | Ruta | Autorización |
|---|---|---|
| Iniciar sesión | `POST /v1/auth/password/login` `{email,password}` → `AuthResponse` + cookie `__Host-mtec-refresh` | anónima; origen HTTPS exacto, `X-MTEC-Client: web`, límite por ruta y por dirección |
| Solicitar enlace de recuperación | `POST /v1/auth/password/recovery/requests` `{email}` → `202` + cookie de intento | anónima; mismo acuse para direcciones conocidas, desconocidas y limitadas |
| Canjear enlace (registro o recuperación) | `POST /v1/auth/email/confirm` (existente) | misma cookie de navegador + CSRF del intento; el propósito se guarda en servidor |
| Estado | `GET /v1/me/password` → `{configured, setupAvailable}` | Bearer |
| Primera contraseña | `POST /v1/me/password` `{newPassword}` → `201` | Bearer + CSRF de sesión + concesión de un solo uso |
| Cambiar contraseña | `PUT /v1/me/password` `{currentPassword,newPassword}` | Bearer + CSRF de sesión + contraseña actual |

El inicio de sesión normal **no** exige Magic Link. El mismo `userId`, JWT, sesión, refresh y logout de los demás métodos.

## Almacenamiento (migración `V14__password_credentials.sql`, aditiva)

* `password_credentials(user_id PK/FK, password_hash, hash_version, created_ms, updated_ms, version)`: solo hash Argon2id
  autodescriptivo (`$argon2id$v=19$m=19456,t=2,p=1$…`, perfil mínimo OWASP). Subir los parámetros = nuevo
  `hash_version`; los hashes antiguos se verifican con sus propios parámetros y se actualizan en el siguiente login correcto.
  Nunca se guarda ni se registra la contraseña en claro.
* `email_login_attempts.purpose` (`LOGIN` por defecto | `RECOVERY`): los enlaces de recuperación y de acceso no son intercambiables.
* `password_setup_grants(session_id PK/FK, user_id, expires_ms, consumed)`: concesión de un solo uso, ligada a la sesión creada
  por el enlace confirmado, válida 10 minutos.
* `password_attempt_throttle(key_hash, failures, window_start_ms, locked_until_ms)`: clave = SHA-256 de la dirección normalizada
  (o del `userId` para cambios), de modo que direcciones inexistentes se limitan igual que las existentes.

Hash: concurrencia acotada (`USERS_PASSWORD_HASH_MAX_CONCURRENT`, por defecto 4; cada hash retiene ~19 MiB). Saturado → `503`.

## Límites y respuestas

* 5 fallos en 15 min por dirección → bloqueo 15 min, `429 RATE_LIMITED` + `Retry-After` (incluso con la contraseña correcta;
  idéntico para cuentas inexistentes). Fallos de otro tipo: `401 INVALID_CREDENTIALS` uniforme (desconocida, solo Google, sin
  contraseña, desactivada o contraseña errónea; también se gasta el trabajo de hash).
* Contraseña: 10–128 puntos de código, sin caracteres de control, ≥4 distintos, no común ni derivada de la dirección → `422 WEAK_PASSWORD`.
* Rutas anónimas: límite por instancia/minuto (`PublicAuthRateLimitFilter`).

## Sesiones al cambiar la contraseña

Cambiar la contraseña **revoca todas las demás sesiones** de la cuenta y conserva la actual. Los JWT de acceso ya emitidos no se
revocan individualmente (vencen solos; ver `MTEC40_JWT_RESIDUAL_EXPOSURE_QA.md`).

## Continuidad de identidad (B02)

B02 aprueba passkeys y códigos de recuperación de un uso como prueba de continuidad, pero **sus protocolos no están aprobados ni
implementados** (y quedan fuera de este incremento). Un enlace al buzón actual prueba control del buzón ahora, no titularidad del
historial de la cuenta, porque la dirección pudo reasignarse. Reglas implementadas:

1. **Cuenta nueva**: el enlace confirmado crea la cuenta y concede establecer la primera contraseña desde esa sesión.
2. **Cuenta Magic Link existente sin contraseña**: un enlace confirmado (acceso o recuperación) mantiene el `userId` y concede la primera contraseña.
3. **Cuenta con contraseña**: un enlace de recuperación **no** la reemplaza ni inicia sesión: `409 CONTINUITY_REQUIRED`, enlace gastado,
   sin sesión ni concesión. Un enlace de acceso ordinario sigue iniciando sesión (comportamiento Magic Link existente) pero **no** concede
   fijar ni reemplazar contraseña; cambiarla exige la contraseña actual.
4. **Google**: nunca se vincula ni se fusiona por coincidencia de correo. Una dirección usada por una cuenta Google devuelve
   `409 CONTINUITY_REQUIRED` en el enlace de registro, no se crea identidad institucional ni se envía enlace de recuperación; la cuenta
   Google no puede tener contraseña.
5. **Acceso básico aislado** (cuenta nueva que no hereda historial): requiere protocolo/namespace/perfil inicial aún no aprobados.

### Bloqueo real (no resuelto aquí)

*Olvidé mi contraseña* con una cuenta que **ya** tiene contraseña no puede resolverse de forma automática y segura sin una prueba
de continuidad aprobada (passkey/código de recuperación) o un procedimiento excepcional aprobado. No se inventó ningún atajo. El
usuario conserva el acceso con su contraseña actual o con el enlace de acceso Magic Link existente; reemplazar la contraseña olvidada
queda bloqueado hasta aprobar B02/D-02.

## Activación

`USERS_PASSWORD_ENABLED=true` requiere el perfil SQL, `users.internal-jwt.enabled`, orígenes HTTPS exactos
(`USERS_PASSWORD_ALLOWED_ORIGINS`, por defecto los de Magic Link/Google) y, para las rutas Bearer, `USERS_API_HTTP_ENABLED=true`.
`POST /v1/auth/password/recovery/requests` además necesita `USERS_EMAIL_ENABLED=true` y `USERS_EMAIL_RECOVERY_LINK_BASE_URL`
(https, sin query ni fragmento); sin ellos responde `503`. La migración V14 debe aplicarse antes de desplegar este build: la validación
de esquema al arrancar falla cerrada si falta.

## Pruebas

* Unitarias (sin base de datos): `PasswordPolicyTest`, `Argon2idPasswordHasherTest`, `PasswordCredentialServiceTest`,
  `PasswordIdentityContinuityTest` (B02), `PasswordAuthControllerTest`, `PasswordApiControllerTest`, `PasswordAuthConfigTest`.
* Integración SQL (SQL Server real en CI, solo Linux x86-64): `SqlUserStoreIT` — migración V13→V14 sobre esquema existente,
  repositorios, flujo registro→contraseña→login→cambio, recuperación/continuidad, Google y limitación compartida entre instancias.
