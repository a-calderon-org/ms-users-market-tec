# Google G2B B3: bloqueos HTTP (reconstrucción B3-A)

Este documento reconstruye el estado B3-A a partir del commit base
`670122be1173262275dc4c0b80924bf6320d192c` y las comprobaciones
recreadas. No es una recuperación literal del archivo del worktree desaparecido.
El documento B1 original de ese trabajo no está disponible; no se reconstruye
ni se le atribuyen decisiones nuevas.

## Rotación técnica corregida

`SessionAuthority.refresh` consume las pruebas de refresh y CSRF vigentes,
rota ambos verificadores y emite credenciales nuevas dentro de la transacción.
La rotación técnica ya no invoca `requireRenewal` ni `renewAt`, y no registra
actividad. Conserva `renewableUntil`, `lastActivityAt`, `idleUntil`,
`absoluteUntil` y la versión de la sesión. Los límites existentes siguen
siendo 60 minutos de ventana, 30 minutos de inactividad y 8 horas absolutas.
Una renovación explícita sigue pasando por `renew` y `requireRenewal`.

En la configuración SQL actual, `requireRenewal` rechaza siempre. No existe
una dependencia de Spring Security en el POM ni un adaptador de autorización
humana conectado a esa decisión. La rotación técnica no debe interpretarse
como autorización de renovación.

## Pendientes B3

- Historial de generaciones de credenciales para distinguir y tratar replay:
  pendiente. La implementación actual conserva solo los verificadores vigentes.
- Semántica CSRF ante replay de refresh: pendiente de decisión contractual.
  La prueba vigente rechaza la credencial consumida; no define revocación de
  una sesión todavía válida por ese evento.
- Bootstrap `GET /v1/auth/session`: pendiente.
- Logout HTTP idempotente: pendiente. El método interno actual rechaza una
  credencial ya consumida; no establece la respuesta de un endpoint repetido.
- Pruebas sobre SQL Server real para esta reconstrucción: pendientes.

Las rutas B3 siguen deshabilitadas por defecto. Las pruebas HTTP verifican
su ausencia sin habilitar endpoints ni definir sus respuestas futuras.
