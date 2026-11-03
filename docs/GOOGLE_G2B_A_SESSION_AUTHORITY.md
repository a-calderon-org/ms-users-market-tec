# Google G2B-A: autorización interna de vínculos

El login interno acepta únicamente un ID Token validado para el intento y nonce vigentes, seguido de un vínculo exacto `(GOOGLE, issuer, subject)` con `link_status=ACTIVE` y un usuario `ACTIVE`. El propietario persistido del vínculo proporciona el `userId`; el correo verificado de Google no se usa para buscar, crear ni fusionar cuentas. El vínculo se consulta dentro de la transacción que consume el intento, crea la sesión y firma el JWT. Un fallo revierte el consumo y la sesión. La sesión conserva sus ventanas 60/30/8 y el JWT su techo de 240 segundos.

`V5` añade `link_status` con restricción SQL. Los vínculos anteriores quedan `PENDING` y requieren una decisión explícita para activarse; la migración no los habilita automáticamente. Los vínculos nuevos insertados por los flujos existentes de registro verificado o vinculación autorizada se guardan como `ACTIVE`. `DISABLED`, `REVOKED` y `CONFLICT` no autorizan login. El esquema de cuenta conserva `ACTIVE`/`DEACTIVATED`; una cuenta desactivada tampoco autoriza login.

## Condiciones HTTP pendientes

El contrato G2A local declara `/v1/auth/google` como `proposed` y `AuthResponse` con `token`, `tokenType`, `expiresAt` y `user`. Todavía faltan decisiones verificadas sobre:

- Hosts reales de Angular, APIM y ms-users, orígenes permitidos y política CORS.
- Entrega del secreto de navegador y atributos de cookie, incluidos `Secure`, `HttpOnly`, alcance y `SameSite`.
- Mecanismo CSRF para comienzo, intercambio y refresh, ligado al intento.
- Transporte y rotación de refresh, con tratamiento de replay y carreras.
- Forma completa y semántica de `AuthResponse`, incluido el usuario retornado, expiración del token, errores y transporte del refresh.

Ninguna ruta pública se habilita con este cambio. No se atribuyen roles TEC, historial no acreditado ni grants B04 por el login.
