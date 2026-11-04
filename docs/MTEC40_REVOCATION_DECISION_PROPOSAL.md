# MTEC-40 — Propuesta de decisión: revocación por compromiso confirmado

**Estado: PROPUESTA, pendiente de aprobación expresa del responsable del proyecto.** Base: `origin/main@a2184dfd` (merge PR #17). No autoriza implementación, operación ni despliegue. La política C de MTEC-40 **sí está aprobada**: un replay aislado se rechaza y audita, sin revocar automáticamente la generación ganadora.

## Comparativa para MarketTec v1

| Control | A. Procedimiento operativo controlado | B. Servicio interno de revocación |
| --- | --- | --- |
| Solicitante e identidad | Operador designado, autenticado con identidad institucional verificable; identidad y designación se comprueban antes de ejecutar. | Componente con identidad de servicio autenticada; habría que provisionar y verificar su credencial y ciclo de vida. |
| Autorización | Aprobación de una autoridad de incidente designada, registrada y ligada a incidente y `sessionId`; el operador no se autoriza a sí mismo. | Autorización del servicio y de la decisión de incidente, ambas independientes del cuerpo de la solicitud. |
| Evidencia | Expediente verificable y corroboración independiente del replay; validador humano designado. | Referencia a expediente verificable, validada por una autoridad confiable antes de aceptar la llamada; el servicio no puede afirmar evidencia por sí mismo. |
| Alcance | Solo un `sessionId` exacto, cotejado contra el expediente y la aprobación. | Solo un `sessionId` exacto, cotejado contra autorización y expediente. |
| Durabilidad y atomicidad | Ejecutor restringido registra decisión y revocación en una transacción SQL. | Caso de uso interno registra decisión y revocación en una transacción SQL. |
| Idempotencia y manipulación | `incidentId` único; mismo incidente y sesión devuelve resultado previo; cambio de sesión, actor, evidencia o aprobación se rechaza. | Misma clave y comprobaciones, además de protección de autenticación, autorización y transporte de servicio. |
| Dependencias y operación | Requiere verificar identidad institucional, custodia del expediente, designaciones y acceso restringido al ejecutor; no presupone capacidades Azure. Menor superficie permanente. | Requiere además servicio, identidad máquina, canal seguro, despliegue, rotación de credenciales y monitoreo; capacidades Azure aún no verificadas. |
| Riesgo residual | Error humano, demora de respuesta y abuso de una designación; mitigación con doble control y expediente. | Compromiso de credencial de servicio, autorización defectuosa o abuso automatizado; operación permanente más compleja. |

**Recomendación propuesta: A.** Para v1, el compromiso confirmado requiere valoración humana y no se ha demostrado volumen que justifique un servicio permanente. A conserva una operación puntual verificable y aprovecha el bloqueo de fila y la transacción existentes, pero necesita un **nuevo** control de acceso y registro de decisión. No equivale a ejecutar SQL manual ni a exponer `SessionAuthority.revoke(String)`: ese método hoy acepta solo un ID, sin actor ni evidencia. `SessionActionAuthorization` tampoco autoriza revocación. B puede reconsiderarse si la demanda operativa y la infraestructura verificadas lo justifican.

## Contrato mínimo propuesto para A

1. **Solicita / autoriza.** Una persona operadora designada presenta el expediente; una persona distinta, designada como autoridad competente del incidente, valida la evidencia y aprueba el `sessionId` exacto. El responsable del proyecto debe aprobar quién puede ocupar ambas funciones y el mecanismo de sustitución. Ninguna designación se infiere de email, dominio, cabecera, booleano ni ID enviado.
2. **Identidades.** Antes de ejecutar, un control confiable verifica la autenticación institucional vigente de ambas personas, sus designaciones en una fuente administrada y la aprobación íntegra ligada a `incidentId`, `sessionId` y referencia de evidencia. Medio de identidad, fuente de designaciones, vigencia, firma o prueba de integridad y acceso al ejecutor quedan por seleccionar y probar; si cualquiera falta, se rechaza. No se afirma que Azure ya provea estos controles.
3. **Evidencia.** Se acepta un expediente de incidente con fuente, instante, hallazgo verificable y corroboración ajena al replay aislado, por ejemplo exposición confirmada de credencial o informe del titular corroborado por una segunda fuente. La autoridad designada valida origen, integridad, relación con la sesión y suficiencia; IP, User-Agent, Origin, conteos o un log de replay solos no bastan. El ejecutor comprueba una referencia existente e íntegra, no texto libre que se autodeclare prueba.
4. **Identificador.** `incidentId` opaco, único e inmutable, emitido por el sistema de expedientes elegido; la decisión se liga a un solo `sessionId` de 32 hexadecimales. No hay revocación por `userId` ni de familia. Un expediente distinto para la misma sesión requiere nueva decisión comprobable.
5. **Registro.** Propuesta de fila SQL: `incidentId` único, `sessionId`, identidad verificada de solicitante y autoridad, identificador/versiones de sus designaciones, tipo/motivo controlado, referencia e integridad de evidencia, instante de aprobación y de ejecución del servidor, correlación, resultado (`revoked`/`already_revoked`) y versión de sesión anterior/posterior. Conservar 12 meses como **propuesta** sujeta a política de privacidad y retención aprobada; acceso mínimo, controlado y con consulta auditable. No guardar tokens, cookies, CSRF, JWT, secretos ni contenido sensible del expediente en esta fila.
6. **Ejecución y fallos.** Verificar identidades, designaciones, aprobación y expediente antes de abrir la transacción; revalidar vigencia al ejecutarla. En una transacción SQL, bloquear la sesión, insertar la decisión única y revocar esa misma fila. Error en cualquier paso: rollback de ambos; no informar éxito ni reintentar con alcance ampliado. Ante `incidentId` repetido, comparar todos los datos ligados a la aprobación: igualdad devuelve el resultado durable; diferencia se rechaza y audita. Sesión ya revocada registra `already_revoked` sin reactivarla. Carrera con refresh se serializa por bloqueo de fila; un refresh que terminó antes del commit puede haber emitido JWT.
7. **Comprobación.** Tras commit, lectura autoritativa muestra `revoked=true` y decisión ligada; refresh y nueva emisión para esa sesión se rechazan. Si falla la comprobación, tratar como incidente operativo y reconciliar contra la fila durable, sin declarar éxito. La lectura y el rechazo deben probarse con instancias independientes antes de operar.
8. **Puerta de aprobación.** El responsable debe aprobar A o B, las designaciones y separación de funciones, medio de identidad y verificación, criterios/fuente/validador de evidencia, identificador de expediente, campos y plazo de retención, acceso al ejecutor, tratamiento de incidentes críticos y estrategia de respuesta/reconciliación. Revisar además el punto de corte de operaciones en vuelo y la garantía residual de JWT antes de codificar o desplegar. Cualquier ampliación de alcance requiere otra decisión.

## Criterios de aceptación futuros y falsables

Se ejecutarán contra el mecanismo que se apruebe, con transacción SQL real e instancias independientes donde corresponde; **no son pruebas creadas ni evidencia actual**.

| Caso | Resultado exigido |
| --- | --- |
| Actor sin designación o identidad no verificable | Rechazo, sin fila de decisión ni cambio de sesión. |
| Evidencia ausente, falsa o solo replay | Rechazo, sin revocación; referencia alterada tampoco se acepta. |
| `sessionId` alterado, formato inválido o intento de familia | Rechazo; ninguna otra sesión cambia. |
| Reintento con mismo `incidentId` | Mismo alcance devuelve resultado previo sin segunda mutación; datos ligados distintos se rechazan. |
| Fallo tras insertar decisión y antes de revocar | Rollback: no hay decisión confirmada ni revocación; reintento válido puede completarse. |
| Carrera refresh / revocación en dos JVM | Orden serializable por sesión: si revocación confirma primero, refresh falla; si refresh confirma primero, solo sus tokens previos pueden subsistir y no hay emisión posterior al commit de revocación. |
| Sesión ya revocada | Resultado durable `already_revoked`, sin reactivación ni emisión. |
| JWT emitido antes de revocar | Puede validarse hasta expiración efectiva; medir la cota ordinaria aprobada de ≤300 s desde revocación con relojes, leeway, propagación y admisión reales. No afirmar invalidación inmediata. |

## Riesgos y límite de esta propuesta

Persiste la exposición de JWT previos hasta su expiración, el riesgo de solicitudes ya admitidas, la demora humana y la ausencia actual de identidad, expediente y control transaccional de decisión. La cota de 300 s y la respuesta a incidentes críticos requieren pruebas de infraestructura y decisión adicional. Política C permanece aprobada; **la alternativa A y este contrato no lo están**. MTEC-40 sigue **In Progress**.
