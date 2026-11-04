# MTEC-40 — exposición residual de JWT

## Base y alcance

- HEAD remoto de `main`: `fb4e1acadf183e661bbf66f72633bb89968ac300` (merge PR #19). CI de `main`: [run 41928673697](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41928673697), `success`.
- El checkout aislado partió de la referencia local `origin/main` `733f6c78c56f5704020ad6c2ff5f0e63f6d054b3`; Git en este entorno no pudo resolver `github.com`. Se cotejaron por SHA de blob contra el HEAD remoto los cuatro archivos de emisión, prueba, configuración y autoridad: idénticos. El incremento se publica sobre el HEAD remoto.
- Criterio MTEC-40: residual ordinario ≤300 s desde revocación confirmada, contando desfase, tolerancia y admisión. Ventanas de sesión 60 min / 30 min / 8 h; política C.

## Hipótesis

| ID | Estado | Evidencia y límite |
|---|---|---|
| J1 | NO REPRODUCIDA | Emisión RS256 acotada a 240 s; verificación rechaza `exp - iat > 240 s`. |
| J2 | NO REPRODUCIDA | El verificador local acepta antes de `exp`, rechaza exactamente en `exp` y después, incluso tras revocación simulada. |
| J3 | CONFIRMADA | Con reloj validador 61 s atrasado, el token se acepta a tiempo real `exp + 61 s - 1 ns`, más de 300 s desde revocación casi inmediata. Es un escenario controlado, no una medición de infraestructura. |
| J4 | NO REPRODUCIDA | Tras revocación confirmada, `current(sid)` deja de devolver sesión y la nueva emisión se rechaza; las pruebas de autoridad cubren revocación/logout. No se midió una carrera de emisión ya admitida antes del commit. |
| J5 | NO REPRODUCIDA | `exp` queda en el primer deadline efectivo: casos cercanos a 8 s renovable, 30 s inactividad y 60 s absoluto. |
| J6 | NO VERIFICADA | El consumidor real de `ms-users` vuelve a comprobar `exp` sin leeway y consulta sesión, pero no se inspeccionó ni probó el comportamiento temporal de APIM u otros servicios. |

## Presupuesto temporal

- Duración nominal máxima observada: **240 s**. Para emisión junto al instante de revocación, residual probado `239.999999999 s` (`revokedAt = iat + 1 ns`). El tiempo residual disminuye si la revocación ocurre después de emitir.
- Verificador criptográfico local: **0 s de tolerancia positiva explícita**; acepta mientras `clock.instant() < exp`. Un validador 60 s atrasado consume casi todo el margen: aceptación hasta antes de 300 s desde la emisión. Con 61 s de atraso, se supera el objetivo. Fórmula de cota: `residual < 240 s + atraso del validador + leeway positivo + demoras de admisión`. El presupuesto combinado restante es **≤60 s**, sujeto a prueba real y a cómo se define el corte de admisión.
- Un reloj adelantado rechaza antes; un reloj atrasado puede rechazar inicialmente por `iat` futuro y aceptar luego. Las solicitudes admitidas antes del commit de revocación y operaciones en vuelo tienen corte **NO VERIFICADO**.

## Pruebas y conclusión

- `./mvnw -q -Dtest=InternalJwtServiceTest test`: PASS antes de añadir regresión.
- `./mvnw -q -Dtest=InternalJwtServiceTest,SessionAuthorityTest,ProtectedUserOperationsTest test`: PASS, 22 + 40 + 6 = 68 casos; reloj fijo y RSA-2048 sintética. Se añadieron cuatro pruebas de residual, desfase, deadlines y TTL excesivo. No se cambió código productivo, Flyway, inventario SQL ni política C.
- Defectos reproducidos en `ms-users`: **ninguno**. Correcciones productivas: **ninguna**. J3 muestra una condición de infraestructura que debe acotarse, no una vulnerabilidad confirmada del emisor.
- **VERIFICADO LOCALMENTE:** con reloj compartido y leeway 0, residual <240 s; bajo supuesto de atraso ≤60 s y sin otras demoras positivas, residual <300 s. **Cumplimiento incondicional de ≤300 s: NO VERIFICADO.**
- **APIM y cumplimiento extremo a extremo: NO VERIFICADOS.** Faltan configuración y pruebas de tolerancia, sincronización de relojes, propagación/admisión y otros validadores distribuidos. No declarar `VERIFICADO END-TO-END` ni cerrar MTEC-40.
