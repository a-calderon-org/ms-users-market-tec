# MTEC-40 — refresh entre JVM independientes

## SHA y topología

Base: `e99ab22852c12feb6ccc1f3f2e0b731a46f7de6e` (`origin/main`, PR #15 fusionado). Rama: `codex/mtec40-multijvm-refresh`.

`SqlUserStoreIT.independentJvmRefreshRotationReplayRollbackAndRevocation` reutiliza el SQL Server 2022 desechable de `IsolatedSqlServer`: Linux x86-64 nativo, TLS verificado, base aislada y migraciones Flyway. El test padre crea credenciales sintéticas y lanza dos procesos `java` mediante `ProcessBuilder`, con PID distintos del padre y entre sí. Cada hijo construye su propio `SQLServerDataSource`, `SqlUserStore`, `SqlSessionRepository` y `SessionAuthority`; ambos se conectan a la misma base. Comparten clave CSRF y reloj fijo. La configuración y los secretos pasan por stdin, nunca por argumentos de proceso; los hijos se destruyen al cerrar el test y el fixture elimina la base y el certificado temporal.

Cada hijo anuncia `READY <pid>` después de conectar a SQL. El padre espera ambos anuncios y envía `GO` por sendos stdin; espera resultados y salidas con límites de 30/40/10 segundos. El resultado del ganador se consume por pipe privado del test y no se registra. El runner de CI existente ejecuta la prueba dentro del inventario estricto SQL.

## Matriz de hipótesis y resultados

| ID | Resultado esperado y prueba que podría refutarlo | Resultado real | Clasificación |
| --- | --- | --- | --- |
| M1 | Escenario A: dos hijos con la misma pareja; una sola rotación, generación SQL 1, dos filas históricas y una sola sin consumir. | Compila; ejecución SQL pendiente. | NO VERIFICADO |
| M2 | A/B: el perdedor y el replay reciben solo rechazo; el ganador conserva la única pareja utilizable. | Compila; ejecución SQL pendiente. | NO VERIFICADO |
| M3 | B: replay de la pareja consumida no altera la sesión ganadora ni sus límites 60/30/8. | Compila; ejecución SQL pendiente. | NO VERIFICADO |
| M4 | C: fallo sintético del firmador después de la escritura revierte generación y puntero; la pareja original puede rotar después. | Compila; ejecución SQL pendiente. | NO VERIFICADO |
| M5 | A–D: procesos con memoria independiente observan el mismo estado SQL; D compite con `revoke` y comprueba cierre serializado. | Compila; ejecución SQL pendiente. | NO VERIFICADO |

Se prueban además refresh desconocido y CSRF incorrecto como `PROOF_INVALID`, frente a `ATTEMPT_CONSUMED` solo para la pareja histórica correcta. El harness no concede gracia ni reemite esa pareja. La respuesta pública `401 REFRESH_INVALID` pertenece a las pruebas HTTP existentes de una sola instancia; este caso usa autoridad/SQL directamente.

## Vulnerabilidades, correcciones y límites

Vulnerabilidades confirmadas: ninguna hasta ejecutar el caso SQL. Correcciones productivas: ninguna. El harness no prueba HTTP multiinstancia, APIM, Azure SQL ni relojes divergentes. La revocación concurrente usa la operación interna existente, no una decisión de revocación por compromiso.

## CI y riesgos pendientes

Local macOS ARM: `test-compile` del perfil SQL pasó; suite Java 285/285 y verificación de inventario normal pasaron. La primera ejecución Java dentro del sandbox falló al impedir el bind local de Tomcat; la repetición autorizada pasó 285/285. SQL Server nativo x86-64 y el nuevo caso multi-JVM: pendientes del CI. El estado del CI de `main` tras el merge no se pudo obtener con el conector GitHub (sin workflows/status asociados al SHA de merge). La decisión sigue siendo **C — evidencia insuficiente** hasta obtener resultados SQL del HEAD final.
