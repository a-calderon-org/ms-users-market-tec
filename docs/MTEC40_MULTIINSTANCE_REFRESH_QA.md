# MTEC-40 — refresh entre JVM independientes

## SHA y topología

Base: `e99ab22852c12feb6ccc1f3f2e0b731a46f7de6e` (`origin/main`, PR #15 fusionado). Harness probado: `305e925597abd83933d8cf351fdf1099daa12962`. Rama: `codex/mtec40-multijvm-refresh`.

`SqlUserStoreIT.independentJvmRefreshRotationReplayRollbackAndRevocation` reutiliza el SQL Server 2022 desechable de `IsolatedSqlServer`: Linux x86-64 nativo, TLS verificado, base aislada y migraciones Flyway. El test padre crea credenciales sintéticas y lanza dos procesos `java` mediante `ProcessBuilder`, con PID distintos del padre y entre sí. Cada hijo construye su propio `SQLServerDataSource`, `SqlUserStore`, `SqlSessionRepository` y `SessionAuthority`; ambos se conectan a la misma base. Comparten clave CSRF y reloj fijo. La configuración y los secretos pasan por stdin, nunca por argumentos de proceso; los hijos se destruyen al cerrar el test y el fixture elimina la base y el certificado temporal.

Cada hijo anuncia `READY <pid>` después de conectar a SQL. El padre espera ambos anuncios, verifica PID distintos y envía `GO` por sendos stdin; espera resultados y salidas con límites de 30/40/10 segundos. En el CI del HEAD `a2931b8`, los hijos tuvieron PID **3587 y 3611**. El resultado del ganador se consume por pipe privado del test y no se registra. El runner de CI existente ejecuta la prueba dentro del inventario estricto SQL.

## Matriz de hipótesis y resultados

| ID | Resultado esperado y prueba que podría refutarlo | Resultado real | Clasificación |
| --- | --- | --- | --- |
| M1 | Escenario A: dos hijos con la misma pareja; una sola rotación, generación SQL 1, dos filas históricas y una sola sin consumir. | Una ganadora, otra rechazada; SQL conservó una sola generación vigente. | NO REPRODUCIDA |
| M2 | A/B: el perdedor y el replay reciben solo rechazo; el ganador conserva la única pareja utilizable. | Perdedor y replay `ATTEMPT_CONSUMED`; solo la pareja ganadora recuperó CSRF. | NO REPRODUCIDA |
| M3 | B: replay de la pareja consumida no altera la sesión ganadora ni sus límites 60/30/8. | La sesión permaneció igual y la pareja ganadora siguió utilizable. | NO REPRODUCIDA |
| M4 | C: fallo sintético del firmador después de la escritura revierte generación y puntero; la pareja original puede rotar después. | Generación 0 y una fila sin consumir tras el fallo; la pareja original rotó después. | NO REPRODUCIDA |
| M5 | A–D: procesos con memoria independiente observan el mismo estado SQL; D compite con `revoke` y comprueba cierre serializado. | PID independientes, SQL común y sesión cerrada tras D. | NO REPRODUCIDA |

Se prueban además refresh desconocido y CSRF incorrecto como `PROOF_INVALID`, frente a `ATTEMPT_CONSUMED` solo para la pareja histórica correcta. El harness no concede gracia ni reemite esa pareja. La respuesta pública `401 REFRESH_INVALID` pertenece a las pruebas HTTP existentes de una sola instancia; este caso usa autoridad/SQL directamente.

## Vulnerabilidades, correcciones y límites

Vulnerabilidades confirmadas: ninguna en estas condiciones. Correcciones productivas: ninguna. El harness no prueba HTTP multiinstancia, APIM, Azure SQL ni relojes divergentes. La revocación concurrente usa la operación interna existente, no una decisión de revocación por compromiso.

## CI y riesgos pendientes

Local macOS ARM: `test-compile` del perfil SQL pasó; suite Java 285/285 y verificación de inventario normal pasaron. La primera ejecución Java dentro del sandbox falló al impedir el bind local de Tomcat; la repetición autorizada pasó 285/285. [CI del PR #16, ejecución 60](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41912775701) pasó en Ubuntu x86-64 nativo con SQL Server 2022 TLS: 285 Java, 56 SQL, cero fallos, errores u omisiones. Flyway, inventario estricto y limpieza pasaron. El caso nuevo está en los 56 ejecutados. [CI de `main` posterior al merge, ejecución 58](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41910638813), pasó para `e99ab22`. Riesgos pendientes: HTTP multiinstancia y las condiciones de producción fuera de este incremento.
