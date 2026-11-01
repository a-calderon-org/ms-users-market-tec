# MTEC-37 — Re-QA de la subfase 2

Fecha: **2026-11-01**, America/Costa_Rica. Dictamen emitido sobre el candidato local
de esta ejecución, sin heredar aprobaciones de la reconstrucción ni de MTEC-35.

**Dictamen local: QA PASS WITH NOTES — preparación local.**

**Dictamen global: QA PARTIAL — BLOCKED para el cierre integral de MTEC-37.**
Las **34 pruebas SQL reales en Linux x86-64**, la validación de identidad/red/esquema
en Azure y el despliegue siguen pendientes. El resultado local no los sustituye ni
autoriza publicación o inicio de la subfase 3.

## Candidato y preservación

- Worktree usado: `~/Developer/MarketTec/worktrees/ms-users-mtec37`.
- Rama conservada: `recovery/mtec37-appservice-readiness`.
- HEAD conservado: `650411bdf0a5b9375fbe6911595f3150d79c633d`.
- El cwd inicial del chat apuntaba al checkout MTEC-34. Se localizó el worktree
  persistente mediante `git worktree list`; no se hizo checkout ni se creó otro.
- El candidato previo, sus XML Surefire y el log previo se preservaron **antes** de
  editar/ejecutar Maven clean en `qa-evidence/mtec37-reqa-20261101/`.
  `before-reqa.tar.gz` y `backup-sha256.txt` documentan la copia fuente anterior.
- Los respaldos de recuperación anteriores y los registros de worktrees se
  conservaron. Los informes `MTEC37_QA_REVIEW.md` y `MTEC37_RECOVERY_REPORT.md` no
  se reescribieron: sus resultados y limitaciones son históricos.

## Defectos originales y correcciones

| Defecto confirmado | Corrección y ubicación del candidato actual |
|---|---|
| Readiness solo incluía `readinessState`; una caída SQL posterior al arranque no cambiaba su respuesta. La prueba simulaba el rechazo manual de Spring. | `src/main/resources/application.yml:47` activa `readinessState,sqlReadiness` exclusivamente en `sql-dev`, `sql-appservice` o `sql-integration`. `src/main/java/com/markettec/users/config/SqlHealthConfig.java:12` registra el indicador usando el datasource del perfil. `src/main/java/com/markettec/users/adapters/out/sql/SqlReadinessHealthIndicator.java:31` obtiene/cierra una conexión y ejecuta `isValid(2)`; fallos JDBC/pool producen DOWN sin detalles. |
| La prueba anterior podía pasar sin ejecutar ninguna validación JDBC. | Se eliminó esa prueba de `src/test/java/com/markettec/users/adapters/in/web/InternalHealthTest.java`. `src/test/java/com/markettec/users/adapters/in/web/SqlReadinessHttpTest.java:36` verifica por HTTP UP/200 → DOWN/503 → UP/200, tanto con `SQLException` al obtener conexión como con `isValid=false`. El indicador y el perfil SQL son los de producción; se sustituyen datasource/store para evitar cualquier conexión remota o validación de esquema. |
| El verificador aceptaba `<flakyError>` y no resolvía namespaces. | `scripts/verify-mtec35-reports.py:10` rechaza `failure`, `error`, `skipped`, `flakyFailure`, `flakyError`, `rerunFailure` y `rerunError` en todo el árbol XML por nombre local, aunque los contadores sean cero. `:53` procesa `testsuite` y `testsuites`, namespaces por defecto/prefijo, identidades duplicadas/ausentes y conteos. `:26` reconcilia el resumen Failsafe y rechaza timeout, código fallido, flakes, mensaje de fallo o campos duplicados. |
| La cobertura de MDC/correlación era secuencial o de invocación directa del filtro. | `src/test/java/com/markettec/users/adapters/in/web/ConcurrentRequestLoggingTest.java:42` añade dos casos HTTP concurrentes reales con Tomcat, barrera y comprobaciones dentro y fuera del filtro en los hilos del servidor. El filtro y formatter existentes quedaron intactos; pasaron las regresiones nuevas y previas. |

La prueba manual de `ReadinessState` permanece únicamente como cobertura independiente
del ciclo de vida de Spring (`InternalHealthTest.java:43`); ya no se presenta como prueba
de caída SQL. En la nueva prueba JDBC se comprueba que Spring continúa en
`ACCEPTING_TRAFFIC` mientras readiness devuelve 503.

## Salud SQL y Azure SQL Serverless

La corrección consulta disponibilidad real a través de JDBC cuando se pide el
indicador. No ejecuta escrituras, migraciones, consultas de negocio, scheduler,
reintentos propios ni un pool adicional. `db` automático permanece desactivado para
evitar duplicación. `/health` y `/actuator/health/liveness` no consultan SQL. Los
perfiles no SQL conservan readiness de proceso y no requieren datasource.

El indicador cachea tanto UP como DOWN durante **30 s**, configurables mediante
`users.sql.readiness.cache-ttl`. Un bloqueo sincronizado agrupa lectores simultáneos;
el agregado `/actuator/health` y readiness comparten ese mismo indicador/caché.
Solo una solicitud posterior a la expiración vuelve a validar. Los tests HTTP usan
TTL cero exclusivamente para observar transiciones sin esperar; los unitarios
prueban el límite exacto de 30 s con tiempo monotónico controlado y ocho lectores
concurrentes con una sola adquisición/validación JDBC.

Microsoft documenta que sesiones/actividad impiden auto-pausa y que un intento de
login puede reanudar una base Serverless. La caché reduce sondeos redundantes pero
**no garantiza auto-pausa ni costo cero**. Con sondeo continuo y TTL 30 s se realiza
como máximo un intento del indicador por TTL/instancia, aproximadamente 120/h;
esto no cuenta validaciones internas del driver/pool ni tráfico de negocio.
Usar `/health` para el heartbeat público de App Service. Mantener sondeos de
readiness o del agregado permanentemente activos puede mantener SQL despierto.
La política de tráfico/disponibilidad frente a ahorro debe acordarse antes del
despliegue. Fuente: [auto-pausa y reanudación de Azure SQL](https://learn.microsoft.com/en-us/azure/azure-sql/database/serverless-tier-auto-pause-resume?view=azuresql).

La observación de caída/recuperación puede demorarse **TTL + intervalo del consumidor + tiempo JDBC**. El pool existente permite 35 s para obtener conexión y la llamada
`isValid` pide 2 s; no se certifica un timeout absoluto de red ni una latencia de
sonda adecuada para Azure. Esta limitación queda documentada para la validación de
despliegue. Liveness sigue limitado al estado del proceso, conforme a la separación
de dependencias externas descrita por [Spring Boot Actuator 3.5](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html).

## Pruebas nuevas y política estricta

| Archivo y líneas | Casos ejecutados y aserciones principales |
|---|---|
| `src/test/java/com/markettec/users/adapters/in/web/SqlReadinessHttpTest.java:36` | 2 casos parametrizados. Disponibilidad inicial, caída posterior y recuperación; respuesta de salud exacta sin detalles; liveness y health público siguen 200 y no aumentan adquisiciones JDBC; conexiones cerradas. |
| `src/test/java/com/markettec/users/adapters/out/sql/SqlReadinessHealthIndicatorTest.java:19` | 4 casos: expiración exacta y caché UP/DOWN, cierre de conexiones, ocho lectores comparten validación, excepciones sin detalles privados y rechazo de TTL negativo. |
| `src/test/java/com/markettec/users/adapters/in/web/ConcurrentRequestLoggingTest.java:42` | 2 casos parametrizados, uno sin MDC previo y otro con contexto envolvente. Cada uno envía dos oleadas de dos solicitudes solapadas: 8 solicitudes reales en total, con éxitos 200 y errores 500. |
| `scripts/tests/test_verify_mtec35_reports.py:61` | 12 métodos unittest. Incluyen 84 combinaciones negativas de los siete elementos no aprobados × Surefire/Failsafe × tres formas de namespace × raíz simple/agregada; positivos, mezcla de namespaces, contadores, XML ausente/malformado, identidades, sustitución de métodos y resúmenes Failsafe fallidos. |

La prueba concurrente restringe Tomcat a dos workers y exige solapamiento con
`CyclicBarrier`. Comprueba la reutilización de los mismos hilos en la segunda oleada;
un filtro envolvente de fixture observa el MDC antes y después del filtro de
producción **en el hilo del servidor**, no solamente en el cliente. Dentro del
controlador se exige un contexto exclusivo de la solicitud a ambos lados de la
barrera. Se verifica limpieza/restauración completa, ausencia de campos previos,
correlación en cabecera/cuerpo/log, sustitución de IDs inválidos/ausentes y un único
evento de finalización por petición. Authorization, cookies, query, campos MDC y
mensajes de excepción contienen marcadores que no pueden aparecer en logs ni
respuestas. Se cubre también que la finalización recupere el ID original después
de una mutación del MDC hijo. No hay sleeps ni orden dependiente del scheduler;
las esperas tienen límites de fallo explícitos.

La política del verificador exige aprobación en la **primera ejecución**. Los
reintentos que terminaron aprobados se rechazan, según los elementos descritos por
[Apache Surefire](https://maven.apache.org/components/surefire/maven-surefire-plugin/examples/rerun-failing-tests.html).
Todos los fixtures son sintéticos y se crean en `TemporaryDirectory`; no se editan
reportes reales. `scripts/qa-mtec35-sql.sh:15` incorpora estos unittest al entrypoint
que invoca el workflow existente. `scripts/mtec35-test-inventory.json:2` exige ahora
137 casos normales; `:71` fija los nuevos nombres críticos. Los 34 métodos SQL se
conservaron idénticos. El aumento es **130 − 1 prueba defectuosa + 8 casos nuevos = 137**.
Las 12 pruebas Python se contabilizan por separado.

## Ejecución local y reconciliación real

Entorno observado: **macOS Darwin 27.0.0 arm64**, Temurin **21.0.13+9**, Maven Wrapper
**3.9.11**, Python **3.15.0**. El Java predeterminado del equipo era 24; Maven y el
JAR se ejecutaron con el JDK 21 persistente de `~/Developer/MarketTec/tools/`.

| Comprobación ejecutada | Evidencia y resultado |
|---|---|
| `./mvnw --batch-mode clean verify` | **BUILD SUCCESS**, finalizado 2026-11-01 17:30:42 −06:00, 11,553 s. Surefire: 137 ejecutadas, 0 fallos, 0 errores, 0 omitidas. |
| `python3 -B -m unittest discover -s scripts/tests -v` | **12/12 OK**. No son pruebas SQL ni se suman a Surefire. |
| `python3 -B scripts/verify-mtec35-reports.py --normal-only` | **PASS**, 137 casos reconciliados contra XML reales e inventario obligatorio; cero resultados flaky/rerun/failure/error/skipped. |
| `python3 -B scripts/verify-mtec35-reports.py` | **Código 1 esperado**: `MTEC35 STRICT QA FAILED: No XML test reports in failsafe-reports`. Se conserva como evidencia de que el cierre SQL no está aprobado. |
| `./mvnw --batch-mode -Psql-integration test-compile` | **BUILD SUCCESS**, 16 fuentes de prueba compiladas; no se invocó Failsafe ni se ejecutó SQL. |
| `bash -n scripts/qa-mtec35-sql.sh` y `git diff --check` | Aprobados. |
| `javap -verbose .../UsersApplication.class` | Major version **65**, Java 21. |
| Integridad | V1 y la lista de 34 métodos SQL son idénticas al candidato anterior; rama/HEAD y registros de worktrees conservados. |

Recuento extraído de **14 XML Surefire reales**, no de fuentes compiladas:

| Suite | Casos |
|---|---:|
| UsersApplicationTests | 1 |
| ConcurrentRequestLoggingTest | 2 |
| HealthControllerTest | 3 |
| InternalHealthTest | 3 |
| RequestLoggingTest | 7 |
| SqlReadinessHttpTest | 2 |
| InMemoryUserStoreTest | 4 |
| SqlReadinessHealthIndicatorTest | 4 |
| SqlUserStoreTest | 25 |
| UserServiceTest | 21 |
| HexagonalArchitectureTest | 4 |
| LocalUserConfigTest | 2 |
| SqlUserConfigTest | 13 |
| UserDomainTest | 46 |
| **Total Surefire** | **137** |

**Failsafe: no hay XML ni resumen real en esta ejecución.** Las 34 SQL están
**pendientes**, no se anotan como aprobadas ni omitidas por un runner. Los mocks
JDBC de dos pruebas HTTP y los fixtures XML del verificador no cambian este estado.

## Smoke del JAR ejecutado

Se ejecutó `scripts/smoke-mtec37-jar.py:31` con Java 21, `-Xmx256m`, perfil `local`
y dos puertos efímeros en **127.0.0.1**. La autorización de ejecución fue específica
para el worktree persistente y loopback; no se desactivó globalmente el sandbox.
No hubo impedimento de puerto pendiente. El script finaliza solamente el proceso
JAR que creó, también ante errores, y registra PASS solo si concluyen todas las
comprobaciones.

Resultado final: **PASS**, 13 comprobaciones HTTP; puertos principal **49476** y
gestión **49477**. Health público 200; cabecera válida propagada e ID sobredimensionado
reemplazado por UUID; probes del listener principal 404; health/readiness/liveness
internos 200; env/metrics internos 404. Los **35 eventos de log** fueron JSON
parseable, incluyeron finalizaciones correlacionadas y omitieron los marcadores
sensibles de ruta/query/Authorization/cookie.

El primer intento terminó **FAIL del harness** al exigir únicamente `status` en el
health agregado: Actuator incluye también `groups: [liveness, readiness]` en esa ruta.
Se corrigió esa expectativa concreta, conservando la prohibición de detalles y
componentes. No fue un fallo de aplicación ocultado mediante reintentos. Se conserva
el intento fallido en `jar-smoke-attempt-1/` y el exitoso en `jar-smoke/`; no se
alteraron reportes Maven ni se repitieron pruebas fallidas con otra política.

JAR probado, SHA-256:
`6aa5ac73a81982d69354ec45bec1694de7bead333df600708ec5d8beef3cc56a`.

Reproducción desde la raíz del worktree, también en una terminal local si una
sesión futura no permite loopback:

```bash
export JAVA_HOME="$HOME/Developer/MarketTec/tools/jdk-21.0.13+9/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw --batch-mode clean verify
python3 -B -m unittest discover -s scripts/tests -v
python3 -B scripts/verify-mtec35-reports.py --normal-only
python3 -B scripts/smoke-mtec37-jar.py --java "$JAVA_HOME/bin/java"
```

El smoke usa memoria local: **no valida SQL, MI, migraciones, recursos Azure ni
capacidad del plan App Service**. La caída/recuperación JDBC se acredita por las
pruebas HTTP descritas, no por este smoke.

## Evidencia, archivos y limitaciones pendientes

La evidencia persistente de esta ejecución está en
`qa-evidence/mtec37-reqa-20261101/` dentro del mismo worktree, excluida de Git por
`.gitignore:2`. Incluye copia del candidato anterior, XML y log previos,
`clean-verify.log`, `verifier-tests.log`, `reconcile-normal.log`,
`reconcile-full-pending.log`, `sql-test-compile.log`, ambos intentos del smoke,
copia de XML Surefire actuales, resultado normal, manifiesto de hashes y copia
fuente final. Los artefactos `target/` pueden borrarse en otro clean; las copias
de evidencia persisten. Son respaldos locales, sin garantía ante pérdida del disco.

Además de los archivos de código/prueba enumerados, se actualizaron
`README.md:542` y `docs/MTEC37_DEPLOYMENT_READINESS.md:64` para retirar la semántica
anterior de readiness sin SQL y explicar el costo de Serverless, la caché y los
límites del dictamen. Los demás cambios reconstruidos previos se conservaron.

Pendientes para cierre global:

1. Ejecutar las **34 SQL** del candidato real en **Linux x86-64 con Docker**, mediante
   el entrypoint existente y la autorización del EULA correspondiente; reconciliar
   los XML Surefire/Failsafe y el resumen real con el verificador completo. Compilar
   las fuentes aquí no satisface esa condición.
2. Validar MI, permisos mínimos, TLS, conectividad, esquema ya migrado y presupuesto
   de sondas/consumo de Serverless en el entorno autorizado. Nada de ello se midió
   ni cambió durante esta re-QA.
3. Resolver publicación/CI remoto y despliegue en una subfase posterior autorizada.
   No se inició automáticamente la subfase 3.

No hubo commit, staging, push, PR, merge ni modificación de Azure, SQL, APIM o Jira.
No se ejecutaron migraciones ni pruebas contra bases remotas. El checkout MTEC-34
y su workflow untracked conservan su contenido; su hash continúa siendo
`4daca123c41dd33f2fde92a6618beffec643c9c7f507d68de7cafdc4a1a88cec`.

La aprobación local queda limitada a las correcciones y comprobaciones ejecutadas
arriba: **QA PASS WITH NOTES — preparación local**. La ausencia de evidencia SQL
nativa mantiene **MTEC-37 completa sin aprobación global**.

Nota editorial de subfase 3: se omitieron rutas personales e identificadores privados
de sesión para publicación. Los originales íntegros permanecen en el respaldo local;
los resultados históricos anteriores no se han actualizado ni reutilizado como QA nuevo.
