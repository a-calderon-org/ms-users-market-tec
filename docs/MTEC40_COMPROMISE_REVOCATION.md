# MTEC-40 — Revocación por compromiso confirmado

**Base examinada:** `origin/main@5b935acf90eb89b14e0dad7ec6196592d37ef624` (merge del PR #16). Rama de trabajo: `codex/mtec40-compromise-revocation`. Política C aprobada en MTEC-40: replay aislado se rechaza sin gracia, reemisión ni revocación de la ganadora; error público `401 REFRESH_INVALID`.

## Frontera de confianza y bloqueo

No existe un mecanismo productivo verificable para autorizar una revocación **por compromiso**. `SessionAuthority.revoke(String)` es público y cambia una sesión conocida por ID sin actor, evidencia, motivo ni decisión. No tiene llamadores productivos actuales; las pruebas de unidad, SQL y el worker multi-JVM lo utilizan para verificar el estado y las carreras. `SessionActionAuthorization` cubre renovación y actividad, no revocación; el bean productivo niega esas dos acciones. El logout sí exige la pareja vigente refresh+CSRF, pero posesión de esa pareja no acredita un compromiso independiente ni una decisión de incidente. No hay endpoint de incidente.

1. **Solicitante:** no hay rol, servicio ni responsable de incidentes implementado o aprobado como solicitante de revocación por compromiso.
2. **Identidad y autorización:** ningún verificador autentica a ese solicitante ni comprueba su competencia para el alcance pedido. ID, cabecera, booleano o ubicación de red no bastan.
3. **Evidencia independiente:** el log `refresh_audit` clasifica replay, pero no es evidencia independiente ni durable; IP, User-Agent, Origin y conteo de replays tampoco autorizan revocación. Falta un origen de evidencia verificable y una referencia retenida.
4. **Alcance:** el almacenamiento identifica sesiones individuales; no hay identificador de familia de sesiones ni autorización para revocar todas las sesiones de un usuario. El único alcance técnicamente concreto hoy es un `session_id` exacto.
5. **Decisión y ejecución:** `revoke` usa transacción y bloqueo de fila; `replace` borra los verificadores vigentes y la sesión queda inválida para refresh/emisión posterior. No persiste decisión de incidente, actor, motivo, referencia, correlación ni resultado atómicamente con el cambio. Los logs de refresh no llenan ese vacío.

Por tanto, **la integración productiva de compromiso queda bloqueada**. No se creó endpoint, adaptador, autoridad administrativa, regla automática ni migración basada en una política no aprobada. La API pública interna existente sigue siendo un riesgo de diseño; ocultarla sin reemplazar el harness SQL y multi-JVM rompería pruebas valiosas y no establecería una frontera de confianza.

## Modelo propuesto para decisión

Antes de habilitar el caso de uso, el responsable debe aprobar: (a) identidad del solicitante y verificación servidor a servidor o de operador; (b) fuente y retención de evidencia independiente; (c) autoridad competente y alcance permitido; (d) retención/acceso del registro durable y respuesta ante incidentes críticos. El adaptador futuro deberá obtener una identidad **verificada por infraestructura confiable**, resolver una referencia de evidencia existente y auténtica, y autorizar un `session_id` exacto. Una petición con datos inventados por el llamador debe fallar cerrada. La familia completa exigiría un modelo y una aprobación separados.

El mínimo transaccional posterior sería una decisión persistida con identificador de incidente e idempotencia únicos, actor verificado, tipo/motivo controlado, referencia de evidencia, sesión exacta, instante de servidor, correlación y resultado. Verificación/autorización debe preceder la transacción; dentro de ella se bloquearía la fila de sesión, se insertaría la decisión y se revocaría la misma sesión, con rollback conjunto. Un reintento del mismo incidente y alcance devolvería el resultado previo; una clave reutilizada con alcance distinto fallaría. Después del commit, comprobar rechazo de refresh y de nueva emisión. No almacenar tokens, cookies, CSRF ni JWT. El diseño necesita pruebas SQL de fallos entre inserción y cambio, carreras con refresh y reintentos antes de afirmar durabilidad o seguridad.

## Hipótesis R1–R6

| ID | Clasificación | Evidencia y límite |
| --- | --- | --- |
| R1 | **RIESGO DE DISEÑO** | `revoke(String)` público no comprueba actor. Una prueba exploratoria de visibilidad falló antes de cambios; no se halló ruta productiva que permita a un actor externo invocarlo. No se confirmó explotación externa. |
| R2 | **RIESGO DE DISEÑO** | No existe verificador de evidencia independiente. El replay y su log no autorizan una decisión. Sin caso de uso productivo, no hay intento de evidencia falsificada que probar. |
| R3 | **RIESGO DE DISEÑO** | La operación actual limita la mutación a un ID, pero no comprueba derecho del solicitante sobre él. No hay familia modelada. Sin solicitante autenticado, no se puede afirmar aislamiento de alcance autorizado. |
| R4 | **NO REPRODUCIDA** | Pruebas existentes de autoridad/SQL y el CI multi-JVM del PR #16 serializan refresh y revocación por bloqueo de fila. Localmente pasaron las pruebas de autoridad; SQL local no estuvo disponible. No cubre JWT previamente emitidos, HTTP multiinstancia ni APIM. |
| R5 | **RIESGO DE DISEÑO** | La revocación de sesión es transaccional, pero no hay registro durable de decisión que pueda ser atómico con ella. El rollback de refresh SQL previo no prueba atomicidad de una decisión inexistente. |
| R6 | **RIESGO DE DISEÑO** | `revocationIsIdempotentAndNeverRevives` y `simultaneousRevocationsAreIdempotent` pasan: un segundo intento no incrementa la versión. Sin registro de decisión, reintentos pierden trazabilidad de quién/por qué/cuándo. |

**Vulnerabilidades confirmadas y corregidas:** ninguna explotación confirmada; ningún cambio productivo. La prueba exploratoria R1 confirmó la superficie pública interna, clasificada como riesgo de diseño. No se presenta como vulnerabilidad externa demostrada.

## Validación

- Base `main@5b935acf`: [CI posterior al merge #41921127149](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41921127149), job `sql-qa` **success**, incluidos Java, SQL Server, Flyway e inventario. El [CI del HEAD del PR #16 #41913383956](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41913383956) informó 285/285 Java y 56/56 SQL.
- Antes de cualquier edición, pruebas focalizadas `SessionAuthorityTest`, `SessionRefreshControllerTest`, `SessionLogoutControllerTest`: **51/51 PASS**. Prueba exploratoria R1: **1 fallo esperado** al comprobar que `revoke(String)` no fuese público. Se descartó el cambio de visibilidad al identificar tres llamadores SQL y uno en el worker; código y pruebas volvieron a la base. Repetición final sobre el árbol entregado: **51/51 PASS**.
- SQL local **no ejecutado**: Docker socket denegado. No se ejecutó un CI nuevo para este informe documental; el CI citado corresponde exactamente al código base.
- `git diff --check`: PASS. No se modificó la política C ni los límites 60/30/8.

## Riesgos y decisiones pendientes

La exposición de JWT ya emitidos permanece hasta su expiración efectiva; la garantía residual de ≤300 s y la respuesta diferenciada a incidentes críticos requieren verificación de validadores y topología. No hay retención durable de decisiones ni de todos los eventos refresh. El responsable debe aprobar la frontera de confianza, evidencia, alcance y retención antes de implementar o conectar revocación por compromiso. MTEC-40 permanece **In Progress**. **Veredicto de merge de este incremento documental: B**, apto para revisión como constancia del bloqueo; **C** para activar revocación por compromiso en producción.
