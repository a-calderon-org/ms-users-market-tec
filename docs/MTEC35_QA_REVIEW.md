# MTEC-35 — QA de persistencia SQL en runner aislado

Fecha: **2026-10-31, America/Costa_Rica**. Repositorio:
`a-calderon-org/ms-users-market-tec`.

**Dictamen definitivo: QA PASS WITH NOTES.** Las verificaciones críticas de esta
revalidación aprobaron sobre **SQL Server 2022 CU18, versión real 16.0.4185.3**:
111 pruebas normales y 34 SQL, sin fallos, errores, flakes ni omitidas. Los 34 casos
incluyen los 29 recibidos y cinco adicionales. La identidad Entra de ejecución
restringida en Azure no se certifica aquí; sí se probó una identidad SQL local con
permisos limitados. Esa comprobación remota permanece pendiente antes de operar en
Azure y no se sustituye por el resultado del contenedor.

## Evidencia definitiva y estado Git

| Elemento | Evidencia |
|---|---|
| Worktree | `/private/tmp/markettec-mtec35-worktree` |
| Rama | `feat/mtec35-azure-sql-persistence` |
| HEAD inicial | `db8e352e876a006d8ca5034baae0e4a714e9097d` |
| Implementación recibida preservada | Commit `baa58ff` separado de los cambios de QA |
| Commit de código validado y publicado | `100e55b58bcadb0070e6523cf427a842b2fc6276` |
| GitHub Actions run ID | **41275121477** |
| Evento / estado | `push` / `completed`, `success`, rama y SHA comprobados mediante API |
| Run | [Ejecución definitiva](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41275121477) |
| Workflow ejecutado | [qa-mtec35-sql.yml en el commit validado](https://github.com/a-calderon-org/ms-users-market-tec/blob/100e55b58bcadb0070e6523cf427a842b2fc6276/.github/workflows/qa-mtec35-sql.yml) |
| Artefacto | [Surefire, Failsafe y resumen JSON](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41275121477/artifacts/13070648137) |
| Ventana del run | 2026-10-31 22:58:54–23:00:35, America/Costa_Rica (04:58:54–05:00:35 UTC del 1 de noviembre) |
| Runner | Estándar `ubuntu-24.04`, Ubuntu 24.04.5 LTS, Linux x86-64 nativo, 2 vCPU, 8 317 935 616 bytes RAM |
| Docker / Java / Maven | 28.0.4 / Temurin 21.0.13+9 x64 / wrapper 3.9.11 |
| Dependencias comprobadas en XML | Testcontainers y módulo mssqlserver 1.21.4; Flyway core/sqlserver 11.7.2; JDBC 12.10.2.jre11; Spring JDBC/TX 6.2.19 |
| Imagen real descargada | `mcr.microsoft.com/mssql/server@sha256:ea73825f3d88a23c355ac2f9fdc6bd960fec90171c12c572109b36a558f77bb8` |
| Tag asociado verificado en MCR | `2022-CU18-ubuntu-22.04`, config `linux/amd64`, etiqueta Microsoft `16.0.4185.3` |
| Motor confirmado por consulta | `SERVERPROPERTY('ProductMajorVersion')=16`, `ProductVersion=16.0.4185.3`, antes de crear la base y ejecutar Flyway |
| Base / collation | `mtec35_isolated` efímera, vacía antes de V1 / `SQL_Latin1_General_CP1_CI_AS` |
| Azure / PR / merge / deploy / Jira | Ninguno modificado, creado o ejecutado |

El worktree MTEC-34 permaneció en `feat/mtec34-user-domain`, HEAD
`43374633a5bc7d230aef3e8b8f6f389ec8e0a913`, con su archivo CI sin seguimiento intacto.
Se auditaron ambos estados Git, remotos, worktrees y ausencia de conflictos. No hubo
reset, clean de Git, rebase, force push, cambio de rama ni cambios en otro repositorio.
La publicación usa exclusivamente la rama MTEC-35. El cierre del informe se publica
como cambio documental posterior: el código y workflow evaluados siguen siendo los
del SHA indicado; `docs/**` está excluido del trigger para evitar otro run innecesario.

## Ejecución estricta y recuentos reconciliados

```bash
# Solo después de aceptar el EULA, sobre Linux x86-64 con Docker nativo:
MTEC35_SQLSERVER_ACCEPT_EULA=Y bash scripts/qa-mtec35-sql.sh

# El script ejecuta exactamente:
./mvnw --batch-mode --no-transfer-progress -Psql-integration clean verify
python3 scripts/verify-mtec35-reports.py
```

El usuario autorizó expresamente el EULA de SQL Server 2022 Developer para el QA
efímero. La fixture exige esa variable y configura `MSSQL_PID=Developer`.
Información del editor: [imagen oficial y licencia](https://hub.docker.com/r/microsoft/mssql-server),
[módulo Testcontainers](https://java.testcontainers.org/modules/databases/mssqlserver/).

| Suite / evidencia XML | Ejecutadas | Aprobadas | Fallos | Errores | Omitidas |
|---|---:|---:|---:|---:|---:|
| Surefire: nueve XML `TEST-*.xml` | 111 | 111 | 0 | 0 | 0 |
| Failsafe: `SqlUserStoreIT` | 34 | 34 | 0 | 0 | 0 |
| Total | **145** | **145** | **0** | **0** | **0** |

Las 111 normales se desglosan en dominio 46, casos de uso 21, memoria 4, adaptador
SQL con mocks 25, configuración SQL 5, configuración local 2, HTTP health 3,
arranque 1 y arquitectura 4. Los mocks no se contaron como evidencia del motor.
Failsafe summary declara `completed=34`, `errors=0`, `failures=0`, `skipped=0`,
`flakes=0`. Se cotejaron individualmente los 34 nombres/classname contra
`scripts/mtec35-test-inventory.json` y contra `mtec35-qa-results.json`, además de
reconciliar atributos y elementos testcase en cada XML. No se confundió compilación,
descubrimiento ni error de `@BeforeAll` con métodos SQL ejecutados.

ZIP descargado: `/private/tmp/mtec35-run4-reports.zip`, 57 730 bytes, SHA-256
`32b65ffd46c7828c6ffe73837139bf464aca43805d848b64adb1db5c04a6eb44`, coincidente con
GitHub. Evidencia extraída fuera del repositorio:
`/private/tmp/mtec35-remote-evidence/run-41275121477/`. Log:
`/private/tmp/mtec35-run4-job.log`. El artefacto remoto tiene retención de siete días;
el workflow y el comando permiten reproducir la validación.

El verify normal de desarrollo no activa el perfil SQL y puede ser verde sin SQL.
El perfil SQL existente ya fallaba al faltar Docker, sin assumptions ni @Disabled.
Se mantiene ese comportamiento, Surefire y Failsafe fallan si no descubren pruebas,
y el modo estricto añade comprobaciones de plataforma/Docker y exige ambos conjuntos
de XML, sus recuentos exactos y los 34 métodos aprobados. Un clean de Maven evita
informes antiguos. Se probaron localmente rechazos por informes ausentes, skips y
casos faltantes, además del rechazo de este Mac ARM64 y del arranque sin aceptación.
El workflow no tiene continue-on-error ni exclusiones de pruebas críticas.

## Resultados funcionales en SQL Server 2022

| Verificación crítica | Resultado |
|---|---|
| Flyway V1 desde cero | PASS: cero tablas antes de migrar, una migración aplicada y segunda migración con cero cambios; historial con una V1 exitosa, checksum validado y cero pendientes |
| Esquema y adaptador | PASS: tablas, PK, FK confiables, NOT NULL, CHECK, tipos/tamaños, hashes persistidos e índice de propietario comprobados mediante metadatos y operaciones reales |
| Unicidad de identidad | PASS: PK compuesta de hashes UTF-16LE completos, sin truncar; duplicado entre proveedores rechazado y FK efectivas |
| Collation | PASS: base CI_AS comprobada; issuer/subject BIN2 y hashes preservan diferencias de mayúsculas, acentos, normalización Unicode y claves largas |
| Persistencia | PASS: usuario, todos los campos del perfil, identidad, precisión de nanosegundos, recuperación por otra instancia, actualización y desactivación idempotente |
| Rollback | PASS: fallo tras las escrituras de usuario/perfil/identidad; fallo intermedio real de SQL; fallo anidado FK capturado; restauración de estado/perfil/versión; savepoint de negocio que conserva la unidad exterior |
| Frontera transaccional | PASS: TransactionTemplate/UserTransactions posee la unidad real; sesión JDBC con aislamiento 4 (SERIALIZABLE), sin transacción ambiental residual; no se depende de un proxy @Transactional inexistente |
| Concurrencia | PASS: altas simultáneas idempotentes sin huérfanos, vínculos a distintos propietarios con un ganador, coordinación vínculo/desactivación y versión con un escritor obsoleto |
| Deadlock y reintento | PASS: locks compartidos/exclusivos opuestos provocan víctima y reintento de la unidad completa; ambos resultados finales y versiones se verifican |
| Fallo posterior al commit | PASS: commit SQL real seguido de excepción inyectada con código reintentable; callback ejecutado una sola vez y datos confirmados recuperables |
| Reinicios | PASS: contexto Spring y motor SQL; persistencia conservada después de restart real del contenedor, con actualización del endpoint efímero |
| Runtime restringido local | PASS: validación de esquema y persistencia con SELECT/INSERT/UPDATE de las tres tablas, SELECT del historial y VIEW DEFINITION; db_owner/CREATE TABLE/ALTER dbo/DELETE ausentes y DDL/DELETE efectivamente rechazados |
| Arquitectura | PASS: cuatro reglas ArchUnit, incluidas dentro de las 111 normales |

No se modificó V1: SHA-256 sigue siendo
`de308e0f419cb80e2762c3fd6d7cf3afb1118a9d4679b98bd67e79660d318206`.
No se modificó el aislamiento: el contrato de UserTransactions exige operaciones
serializables de cuenta/identidad y la unidad abarca varias tablas; las carreras,
locks y reintentos ahora tienen evidencia real. El alta necesita coordinar la
lectura de una identidad ausente con su inserción; el vínculo debe coordinar estado
de cuenta, reserva de identidad y verificación de perfil frente a desactivación.
La actualización además dispone de comparación de agregado y versión para rechazar
escritores obsoletos. Una consulta aislada podría necesitar garantías menores, pero
las mismas lecturas participan en callbacks multitabla; no se introdujo una política
de aislamiento por operación sin evidencia que preserve ese contrato. No se rebajó
el aislamiento para acelerar los tests. Esto no es un benchmark de capacidad ni
demuestra comportamiento con cualquier carga distribuida.

## Correcciones y trazabilidad de los intentos

Se conservaron los 29 métodos SQL y sus expectativas. Se añadieron cinco casos:
historial Flyway explícito, aislamiento JDBC real, deadlock determinista, error
posterior al commit real y permisos de runtime local. La implementación previa y
sus correcciones transaccionales permanecen en el commit inicial separado.

| Run / commit | Resultado observado y tratamiento |
|---|---|
| [41274402664](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41274402664) / `a789a5c` | Failure: 111 normales aprobadas; 34 SQL ejecutadas, 20 aprobadas, 2 fallos y 12 errores. El restart dejó el endpoint anterior; los 13 casos siguientes heredaron conexiones rechazadas. Después se comprobó que el motor usado era 17.0: no es evidencia de SQL 2022. |
| [41274755407](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41274755407) / `2eb1a24` | Success: 111 normales y 34 SQL aprobadas. Demostró cambio de puerto 32769 -> 32770 y recuperación tras corregir el mapping. Se descartó como acreditación SQL 2022 al detectar `mssql/server:latest` y versión 17.0 en el log. |
| [41275039715](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41275039715) / `224ea7f` | Failure: 111 normales aprobadas; cero métodos SQL ejecutados, un error de preparación de clase. Testcontainers 1.21.4 rechazó tag+digest antes de crear el contenedor SQL. Se intentó cancelar para ahorrar cómputo, pero ya estaba completo (API 409). |
| [41275121477](https://github.com/a-calderon-org/ms-users-market-tec/actions/runs/41275121477) / `100e55b` | **Success definitivo: 111 normales y 34 SQL aprobadas sobre 16.0.4185.3, cero omitidas; ambos verificadores, cleanup y artefacto success.** |

Se corrigieron tres defectos de la fixture de QA: Docker puede cambiar el puerto de
HostPort=0 al reiniciar, por lo que ahora se inspecciona el mapping actual y se
actualizan todos los datasource; la constante heredada MSSQLServerContainer.IMAGE
ocultaba la constante externa, por lo que el constructor usa explícitamente
IsolatedSqlServer.IMAGE; el parser 1.21 requiere referencia solo por digest para
reconocer el repositorio del módulo. El nombre real de Testcontainers y la versión
consultada al motor se registran, y cualquier major distinta de 16 aborta antes de
crear la base o migrar. La construcción y compatibilidad del argumento final se
comprobaron localmente sin Docker antes del último push.

Los dos primeros runs arrancaron latest por el error de referencia, aunque la
intención y la autorización de licencia recibida eran para 2022. Sus contenedores
fueron eliminados; no se presentan como autorización ni evidencia del motor solicitado.
No hubo cambio de expectativas para ocultar esos errores. setup-java se actualizó
a v5 al observar la deprecación de v4, manteniendo el pin por SHA.

## Seguridad, alcance y pendientes

El workflow se limita a push de la rama MTEC-35, runner estándar y timeout de 20
minutos; permiso único `contents: read`, acciones oficiales fijadas por SHA y
checkout sin credenciales persistentes. No contiene secrets Azure, id-token: write,
servicios de despliegue ni runner self-hosted. El conector GitHub devolvió 404 para el
repositorio; se utilizó el acceso Git/API local ya configurado, sin publicar tokens.

La fixture usa el módulo Microsoft, espera por log y SELECT con TLS/certificado
verificado, timeouts de arranque/login/socket/test, contraseñas aleatorias exclusivas
del contenedor y base vacía con nombre comprobado. El puerto se publica únicamente
en 127.0.0.1 y se reinspecciona tras restart; no hay exposición SQL a Internet.
No hay volúmenes persistentes. Stop/limpieza temporal de la fixture, Ryuk y el paso
final por etiqueta del contenedor finalizaron correctamente; el paso comprueba cero
contenedores SQL etiquetados restantes. La aceptación EULA requiere opt-in explícito.

La revisión de contenido versionable no encontró tokens GitHub/JWT, claves privadas,
credenciales Azure/Entra, datos reales ni artefactos generados. El candidato de texto
PASSWORD era construcción de DDL con un parámetro aleatorio enlazado, no una contraseña
literal. Solo se publicaron fuentes/configuración y documentación; los XML, ZIP,
certificados, claves, logs y binarios quedan fuera de Git. Las URL JDBC del motor
son locales; el servicio conserva sus propiedades Azure sin ejecutarlas aquí.
No se conectó a `sql-markettec-dev.database.windows.net`, no se ejecutó AzureSqlProbe,
ni se crearon recursos o migraciones remotas, ni se habilitó SQL auth en Azure.

La evidencia de persistencia requerida para revisar el merge está completa. Para el
cierre operativo de MTEC-35 sigue pendiente acreditar la identidad Entra de ejecución
con permisos mínimos en su entorno autorizado, separada del migrador. El test local
no prueba esa configuración remota. No se configura ni prueba Azure en esta tarea.
No quedan casos SQL críticos pendientes en el runner aislado. La revisión humana y
cualquier migración/operación futura requieren su propio alcance; no se crea PR,
no se fusiona, no se actualiza Jira ni se inicia otro ticket automáticamente.

Azure modificado: **No**. PR creado: **No**. MTEC-36/MTEC-37, frontend, OpenAPI y
`market_tec`: **sin cambios**.

<!-- MTEC35_REMOTE_REVIEW_END -->


La sección siguiente conserva la revisión anterior como evidencia histórica. Sus
recuentos y bloqueos no sustituyen el resultado de la revalidación que encabeza el informe.

# MTEC-35 — QA Review independiente de persistencia SQL

Fecha: 2026-10-31, America/Costa_Rica. Alcance: subfase 2, repositorio
`a-calderon-org/ms-users-market-tec`. Revisión directa del código, V1, configuración,
pruebas y sus XML; el informe de implementación se trató como evidencia histórica,
no como prueba de corrección.

**Dictamen: QA PARTIAL — BLOCKED.** Las correcciones y las 111 pruebas normales pasan.
No se ejecutaron las 29 pruebas SQL reales ni V1 en un motor aislado. La persistencia
no está completamente validada ni autorizada para integrarse como tal.

## A. Estado Git

- Worktree auditado: `/private/tmp/markettec-mtec35-worktree`.
- Rama inicial y final: `feat/mtec35-azure-sql-persistence`.
- HEAD inicial y final: `db8e352e876a006d8ca5034baae0e4a714e9097d`.
- Entrada: 5 archivos existentes modificados y 15 nuevos, sin staging.
- Salida: los mismos 5 existentes modificados y 16 nuevos; el nuevo archivo adicional
  es este informe. Índice vacío: `git diff --cached --stat` sin salida.
- Se ejecutaron `pwd`, ambos formatos de `git status`, `git branch --show-current`,
  `git rev-parse HEAD`, `git diff --check`, `git diff --stat` y
  `git worktree list --porcelain`. El otro worktree sigue en MTEC-34, HEAD
  `43374633a5bc7d230aef3e8b8f6f389ec8e0a913`; no se editó.
- No se cambió de rama, sincronizó main, hizo commit, push, PR, merge, despliegue,
  reset, rebase, git clean ni modificación Jira. Los cambios recibidos se preservan.
- `git diff --check` sin errores. `--stat` normal no incluye archivos nuevos sin
  seguimiento; no se usó ese resumen para contar los entregables.

Existentes modificados:

```text
.env.example
README.md
pom.xml
src/main/java/com/markettec/users/config/LocalUserConfig.java
src/main/resources/application.yml
```

Nuevos sin seguimiento:

```text
docs/MTEC35_IMPLEMENTATION.md
docs/MTEC35_QA_REVIEW.md
src/main/java/com/markettec/users/adapters/out/sql/AzureSqlConnection.java
src/main/java/com/markettec/users/adapters/out/sql/AzureSqlProbe.java
src/main/java/com/markettec/users/adapters/out/sql/SqlIdentityKey.java
src/main/java/com/markettec/users/adapters/out/sql/SqlSchemaValidator.java
src/main/java/com/markettec/users/adapters/out/sql/SqlUserStore.java
src/main/java/com/markettec/users/adapters/out/sql/UserPersistenceException.java
src/main/java/com/markettec/users/config/SqlUserConfig.java
src/main/resources/application-sql-dev.yml
src/main/resources/application-sql-integration.yml
src/main/resources/db/migration/V1__initial_users_schema.sql
src/sql-test/java/com/markettec/users/adapters/out/sql/IsolatedSqlServer.java
src/sql-test/java/com/markettec/users/adapters/out/sql/SqlUserStoreIT.java
src/test/java/com/markettec/users/adapters/out/sql/SqlUserStoreTest.java
src/test/java/com/markettec/users/config/SqlUserConfigTest.java
```

## B. Arquitectura y hallazgos

Las referencias `archivo:línea` siguientes son relativas a este worktree y corresponden
al código final. Severidad P1: integridad; P2: robustez/validación; nota: límite conocido.

| Severidad / estado | Hallazgo, evidencia y corrección |
|---|---|
| P1, corregido; motor pendiente | Un fallo al revertir un savepoint podía ser capturado por el callback exterior y permitir su commit. La prueba agregada falló antes del cambio: no se lanzó la excepción esperada y se admitió la ruta de commit. Ahora se marca rollback-only exterior y se traduce el error. `src/main/java/com/markettec/users/adapters/out/sql/SqlUserStore.java:48`; regresión `src/test/java/com/markettec/users/adapters/out/sql/SqlUserStoreTest.java:159`. |
| P1, corregido; motor pendiente | Un error SQL anidado puede abortar la transacción completa, no solamente el savepoint. Capturarlo no debe permitir confirmar una unidad que perdió escrituras exteriores. Ahora los fallos técnicos anidados invalidan también al padre; los fallos de negocio siguen usando el savepoint. `SqlUserStore.java:52`; regresión `SqlUserStoreTest.java:137`; prueba SQL preparada `src/sql-test/java/com/markettec/users/adapters/out/sql/SqlUserStoreIT.java:440`. |
| P2, corregido | El catch de `DataAccessException` no distinguía callback de commit. Un gestor que tradujera el error de commit a un código reintentable repetía el callback cuatro veces. Se reprodujo con un gestor simulado: esperado 1, obtenido 4. Ahora solo puede repetirse la misma excepción del callback después del rollback. `SqlUserStore.java:66`; regresión `SqlUserStoreTest.java:127`. La composición actual usa `DataSourceTransactionManager`, que normalmente traduce commit a `TransactionSystemException`; no se afirma haber observado un commit duplicado en SQL real. |
| P2, corregido | En este entorno Mockito no podía autoanexarse a la JVM, tanto con Java 24 como con Java 21: 25 errores de infraestructura de pruebas. Surefire ahora inicia Mockito como agente explícito, con su dependencia resuelta por Maven. `pom.xml:68`. La ejecución normal final pasa sin argumentos especiales. |
| P2, corregido en configuración/cobertura | Faltaba comprobar Linux en el motor Docker y declarar la collation de QA; faltaban carreras de vinculación entre propietarios, fallo SQL intermedio real, comparación con memoria y reinicio del motor. Fixture ampliada y cinco casos SQL agregados; solo compilados. `src/sql-test/java/com/markettec/users/adapters/out/sql/IsolatedSqlServer.java:37`; `SqlUserStoreIT.java:395`. Failsafe ahora falla si el perfil no descubre casos: `pom.xml:126`. |
| Bloqueo, pendiente | No existe un daemon Docker accesible ni un SQL Server aislado utilizable. Se verificó fuera del sandbox con `docker info`: `Cannot connect to the Docker daemon`. El perfil falla en `@BeforeAll`, antes de crear certificados, contenedor, base o ejecutar Flyway. |
| Nota | El validador verifica estructura, tipos, nulabilidad, PK y presencia/habilitación de restricciones e índices; no es una comparación exhaustiva contra cualquier DDL alterado manualmente. FK/CHECK se reconocen principalmente por nombre y los hashes por un patrón de definición: `src/main/java/com/markettec/users/adapters/out/sql/SqlSchemaValidator.java:37` y `:82`. Un checksum Flyway correcto tampoco acredita ausencia de cambios manuales en el catálogo. Se requiere revisar el catálogo antes de una migración remota. |

Dominio y aplicación no importan Spring, JDBC ni SQL. No se modificaron sus clases,
puertos ni el adaptador de memoria. SQL implementa `UserRepository`,
`ExternalIdentityRepository` y `UserTransactions` sin entidades JPA paralelas.
Las cuatro reglas ArchUnit pasan. Estas reglas existentes no enumeran explícitamente
`java.sql`/`javax.sql`; su ausencia en el núcleo se comprobó además por inspección.

`LocalUserConfig.java:17` conserva `local` y añade el alias explícito `in-memory`.
`SqlUserConfig.java:27`, `:34` y `:51` separan SQL/memoria y rechazan combinaciones
incompatibles. Sin datasource o con esquema incompatible falla el arranque SQL;
no hay fallback a memoria. Sin perfil solo se conserva la fundación HTTP/health.
La autorización real de vinculación continúa denegada; las concesiones de los tests
no se instalaron en la composición del servicio.

## C. Persistencia, migración y concurrencia

### Esquema y correspondencia con el dominio

V1 se leyó íntegramente. No fue modificada durante esta revisión. SHA-256 del archivo:
`de308e0f419cb80e2762c3fd6d7cf3afb1118a9d4679b98bd67e79660d318206`.

| Comprobación | Resultado estático / límite real |
|---|---|
| PK | `users.user_id`, `profiles.user_id`, PK compuesta de hashes de identidad; V1:14, :33, :50. |
| FK / relaciones | Perfil 1:0..1 desde la FK/PK; identidad N:1 al usuario; sin cascadas destructivas. La existencia obligatoria de perfil para toda alta se garantiza mediante la transacción del adaptador, no mediante una FK inversa; V1:34, :51. |
| NOT NULL / CHECK | Todos los datos persistidos son no nulos; estados/proveedores y versión no negativa; segundos de registro no negativos, nanosegundos válidos y actualización no anterior al registro; V1:15, :35, :52. Los constructores siguen imponiendo las demás invariantes. |
| Tipos / tamaños | ID ASCII de 40 caracteres; correos ASCII hasta 254; nombres NVARCHAR con capacidad para dos unidades UTF-16 por punto de código; URI e identidad NVARCHAR(MAX); segundos bigint y nanos int conservan precisión de Instant. |
| Unicidad | No se impone unicidad de correo/nombre ajena al dominio. La PK de dos SHA-256 impone unicidad efectiva issuer/subject entre proveedores. |
| Índices | PK para búsquedas por usuario/identidad e índice `ix_external_identities_user` para propietario/FK; V1:57. No hay búsquedas por correo que justifiquen otro índice. |
| Versiones | `bigint` no negativa y actualización condicionada por versión dentro de la unidad completa; `SqlUserStore.java:188`. |
| Compatibilidad SQL Server | T-SQL dirigido a SQL Server 2022/Azure SQL, sin DROP/TRUNCATE/DELETE ni reescritura de datos. Compatibilidad DDL efectiva, opciones SET y metadatos: **bloqueados hasta ejecutar el motor**. |

### Collation y correos

La collation general indicada es `SQL_Latin1_General_CP1_CI_AS`. La solución existente
no compara identidad con esa collation: issuer/subject tienen BIN2 y la PK calcula
hashes independientes sobre la representación binaria UTF-16LE completa. No concatena,
trunca, pliega mayúsculas ni normaliza Unicode. `SqlIdentityKey.java:11` reproduce el
hash desde Java y `SqlUserStore.java:221` comprueba la clave completa al recuperarla.
Una colisión criptográfica hipotética falla cerrada; no reasigna identidades.

Las pruebas Java distinguen mayúsculas, formas Unicode y claves largas. La prueba
SQL `identityKeysAreExactCaseSensitiveAndHaveNoLengthTruncation` y la PK real están
**sin ejecutar**. La fixture ahora crea explícitamente la misma collation CI de Azure
y la comprueba antes de V1; no se cambió arbitrariamente la semántica del dominio.

`Email.java:35` normaliza únicamente el dominio a minúsculas. Conserva local-part,
puntos, alias con `+` y el spelling verificado original al persistir. No hay reglas
Gmail ni fusión de cuentas por correo; `SqlUserStore.java:168` y `:244`.

### Flyway

Hay una única migración versionada, V1, en el directorio convencional; orden determinista.
`application.yml:8` excluye autoconfiguración de Flyway y datasource; no existe Hibernate
ni generación automática de esquema. `SqlSchemaValidator.java:21` solo llama
`validate()`, con `cleanDisabled(true)` y `baselineOnMigrate(false)`.

Las únicas llamadas a `migrate()` están en `SqlUserStoreIT.java:61`, sobre la base
creada por la fixture aislada. Se espera una migración desde vacío y cero al repetir.
Ambas afirmaciones siguen **bloqueadas**. V1 permanece sin publicar en Git y no se
reescribió; una versión aplicada/publicada debe conservarse y corregirse con otra
migración. No se ejecutó Flyway contra `sqldb-ms-users-dev`.

### Fronteras transaccionales reales

Se usa `TransactionTemplate`, no `@Transactional`; no hay dependencia de proxies ni
problema de self-invocation. `JdbcTemplate` y `DataSourceTransactionManager` comparten
el mismo datasource y la conexión ligada al hilo. El mismo `SqlUserStore` abastece
los tres puertos. Se rechazan transacciones exteriores cuyo aislamiento no controla
el adaptador; las anidadas propias usan `PROPAGATION_NESTED`. El uso de conexión ligada
al hilo y savepoints corresponde al
[contrato de Spring](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html).

| Operación | Frontera y garantía prevista |
|---|---|
| `insert` | Usuario y perfil en una unidad; duplicado de ID produce `USER_ALREADY_EXISTS`; fallo de perfil revierte la fila inicial. |
| `registerVerifiedIdentity` | Lectura de clave, alta, perfil verificado e identidad en una unidad del caso de uso; el reintento completo vuelve a resolver la clave ganadora sin conservar la cuenta perdedora. |
| `insertOrGet` / vinculación | Comprueba propietario activo y proveedor; devuelve evidencia persistida en repetición; vínculo y verificación del perfil comparten transacción en el caso de uso. |
| `replace` / actualización | Relee el agregado esperado, compara su valor, actualiza `WHERE version=?`, incrementa versión y escribe perfil; snapshot distinto o fila no actualizada produce `CONCURRENT_MODIFICATION`. |
| Desactivación | Reemplazo transaccional que conserva identidades reservadas; repetir mantiene el mismo estado aunque pueda aumentar la versión interna. |
| Lecturas | Dentro del caso de uso comparten unidad; llamadas aisladas abren una transacción propia. |
| Fallos / anidación | RuntimeException/Error que escapan revierten; fallo técnico de repositorio capturado marca rollback-only; fallo SQL o del savepoint anidado invalida al padre. Fallos de negocio anidados pueden revertir solo al savepoint. |

La versión interna cumple el compare-and-set por valor del puerto; no se entrega un
token de versión al llamador y no se detectan todos los ciclos históricos ABA. Esto
coincide con memoria y no es una promesa nueva de versionado público.

La protección conservadora del padre también considera el
[código oficial del driver 12.10.2](https://github.com/microsoft/mssql-jdbc/blob/v12.10.2/src/main/java/com/microsoft/sqlserver/jdbc/SQLServerConnection.java):
`rollback(Savepoint)` condiciona su sentencia a que siga existiendo una transacción.
Su retorno sin error no acredita por sí solo que las escrituras exteriores sobrevivan
a un error que ya haya abortado toda la transacción en el servidor.

Atomicidad, rollback JDBC efectivo, bloqueo entre conexiones e idempotencia concurrente
son **garantías previstas por el código**, no resultados SQL aprobados en esta sesión.

### Isolation level

Se conserva `SERIALIZABLE` porque `UserTransactions.java:8` exige operaciones
serializables. La combinación de registro por clave ausente y vinculación frente a
desactivación necesita coordinar múltiples lecturas/escrituras. UNIQUE y FK por sí
solas no hacen serializable toda esa unidad, especialmente el estado activo del dueño.

No todas las operaciones aisladas necesitan ese nivel en teoría: un INSERT de usuario
con PK y un SELECT por ID pueden diseñarse con READ_COMMITTED; una actualización puede
usar CAS y un bloqueo dirigido. Cambiarlo globalmente sin revisar el protocolo expondría
carreras entre verificación del propietario, inserción y desactivación. No se cambió.

El coste potencial es retención de locks de lectura/rango, deadlocks de conversión,
menor concurrencia y agotamiento de reintentos bajo contención. El pool pequeño limita
conexiones por proceso, no elimina esos riesgos. No hay medición real de latencia,
deadlocks o consumo en la oferta gratuita: falta el entorno SQL aislado. El timeout es
30 s **por intento**, no un límite total de los cuatro intentos.

### Reintentos y traducción

Máximo cuatro intentos; jitter entre `10*intento` y `30*intento` ms antes del siguiente.
Solo códigos SQL Server 1205, 1222, 3960, 41302, 41305, 41325, 2601 y 2627. Se repite
la unidad completa después del rollback; no una sentencia aislada ni una transacción
abortada. La corrección impide repetir errores de apertura/commit/rollback traducidos.

No se repiten indisponibilidad 40613, autenticación 18456, truncamiento ni conexión
incierta dentro del callback. Un 40613 es compatible con indisponibilidad/reanudación;
no acredita por sí mismo un error de autenticación, como explica la
[documentación de errores transitorios de Azure SQL](https://learn.microsoft.com/en-us/azure/azure-sql/database/troubleshoot-common-errors-issues?view=azuresql).
Una reconexión previa al trabajo
y de solo lectura es distinta de repetir escrituras cuyo commit se desconoce.

Las excepciones que salen de la unidad exterior son razones de dominio o
`UserPersistenceException` con mensajes neutros y sin causa SQL pública. Dentro del
callback aún pueden verse `DataAccessException`; capturarlas no permite confirmar una
unidad técnica fallida. No se modificó el contrato HTTP. Los callbacks reintentables
no deben publicar efectos externos ni consumir concesiones fuera de la transacción.

## D. Pruebas y reconciliación

Java por defecto: Oracle 24.0.2; Maven Wrapper 3.9.11. Versión objetivo: Java 21.
La instrumentación explícita sigue la
[configuración documentada por Mockito 5.17](https://javadoc.io/static/org.mockito/mockito-core/5.17.0/org.mockito/org/mockito/Mockito.html#0.3).
Se usó el JDK Temurin 21.0.13+9 ya instalado en
`/private/tmp/mtec34-tools/jdk-21.0.13+9/Contents/Home`; no se instaló software global.

| Ejecución | Evidencia y resultado |
|---|---|
| `java -version`, `./mvnw --version`, `./mvnw --batch-mode clean verify`, sin cambios QA, Java 24 | 105 descubiertas: 80 aprobadas, 25 errores de inicialización Mockito, 0 fallos de aserción, 0 skipped; BUILD FAILURE. |
| Mismo verify con Java 21, antes del agente | Mismo recuento y fallo de autoanexión; no era suficiente cambiar solamente de JDK. |
| Agente explícito por CLI, antes de corregir dos defectos y con sus dos regresiones añadidas | 107 casos: 105 aprobados y 2 fallos de aserción; demuestra los defectos, no una suite aprobada. |
| `./mvnw --batch-mode clean verify`, Java 21, código corregido | **111 aprobadas, 0 fallos, 0 errores, 0 skipped; BUILD SUCCESS**. Agente configurado en POM, sin flags adicionales. |
| `./mvnw --batch-mode -Psql-integration clean verify`, Java 21 | 111 normales aprobadas; fixture y 29 métodos SQL compilados; Failsafe falla en preparación Docker: 1 error de clase, 0 fallos, 0 skipped. **0 métodos SQL ejecutados**. |

Recuento final por categorías mutuamente excluyentes para las 111 normales:

| Categoría | Ejecutadas | Aprobadas | Fallidas | Omitidas/no ejecutadas |
|---|---:|---:|---:|---:|
| Unitarias (dominio, aplicación, memoria y control JDBC) | 96 | 96 | 0 | 0 |
| ArchUnit | 4 | 4 | 0 | 0 |
| Integración Spring / composición | 11 | 11 | 0 | 0 |
| SQL Server real | 0 | 0 | 0 | 29 bloqueadas |
| Migraciones Flyway | 0 | 0 | 0 | 1 escenario de V1 desde vacío y reaplicación, bloqueado |

El error Failsafe de `@BeforeAll` se contabiliza aparte: **1 error de infraestructura**,
no un caso SQL aprobado ni 29 aserciones SQL fallidas. El escenario Flyway vive en esa
preparación y no suma un test JUnit independiente. La sonda Azure es diagnóstico de
lectura y tampoco se cuenta como prueba de persistencia aislada.

Desglose XML Surefire: dominio 46, aplicación 21, memoria 4, SQL con mocks 25,
ArchUnit 4, `SqlUserConfigTest` 5, `LocalUserConfigTest` 2,
`HealthControllerTest` 3 y `UsersApplicationTests` 1.

**Reconciliación de 105 + 24:** el código recibido tenía 105 normales (19 de SQL con
mocks + 5 de configuración nuevas sobre las 81 previas) y 24 métodos SQL en otro árbol
de fuentes. QA añade tres regresiones transaccionales y amplía un test a cuatro códigos
no reintentables: +6 casos normales = 111. Añade cinco SQL = 29.

Sin `-Psql-integration`, Maven no incorpora `src/sql-test/java`, no compila ni descubre
esos métodos en un clean build y no activa Failsafe. Con el perfil, Failsafe descubre
la clase `*IT` pero falla su preparación antes de ejecutar cualquiera de sus métodos.
No se encontraron `@Disabled`, assumptions Docker, etiquetas excluidas ni condiciones
que los marquen automáticamente como skipped. Surefire/Failsafe efectivos: 3.5.6.
**Cero skipped en XML no significa cero pruebas pendientes. Un verify normal verde
puede omitir toda la persistencia real.**

Las pruebas SQL cubren o preparan V1 desde vacío, lectura tras recrear contexto,
unicidad, FK, rollback de todas las tablas y tras fallo SQL intermedio, conflictos de
versión, altas concurrentes, vinculación concurrente, desactivación, reinicio del motor,
traducción técnica y un escenario comparable con memoria. Todas siguen sin ejecutarse;
la compilación no acredita siquiera que la fixture TLS logre iniciar el motor.

Logs y copias locales fuera del repositorio:

```text
/private/tmp/mtec35-qa-baseline.log
/private/tmp/mtec35-qa-java21-baseline.log
/private/tmp/mtec35-qa-agent-baseline.log
/private/tmp/mtec35-qa-transaction-fixes.log
/private/tmp/mtec35-qa-verify-final.log
/private/tmp/mtec35-qa-sql-final.log
/private/tmp/mtec35-qa-evidence/java24-surefire/
/private/tmp/mtec35-qa-evidence/java21-surefire/
/private/tmp/mtec35-qa-evidence/regressions-before-fix/
/private/tmp/mtec35-qa-evidence/final-normal-surefire/
/private/tmp/mtec35-qa-evidence/profile-surefire/
/private/tmp/mtec35-qa-evidence/final-failsafe/
```

## E. Azure y seguridad

Dependencias efectivas verificadas dentro del JAR: Microsoft JDBC 12.10.2.jre11,
Azure Identity 1.15.3, Flyway core/sqlserver 11.7.2, HikariCP 6.3.3 y Spring JDBC/TX 6.2.19.
`AzureSqlConnection.java:16` fija `ActiveDirectoryDefault`, cifrado habilitado,
`trustServerCertificate=false`, hostname `*.database.windows.net`, login y socket
30 s. La sonda fija timeout SELECT de 20 s y jamás arranca Spring ni Flyway.
La combinación de driver/SDK coincide con la
[matriz oficial de Microsoft](https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-using-azure-active-directory-authentication).

Azure CLI 2.91.0, contexto consultado de solo lectura: **Azure for Students, Enabled**.
No se publican tenant, identidad personal, tokens ni cachés. Se amplió la sonda para
consultar collation y distinguir `dbo`/`db_owner` sin imprimir nombres personales.

Comprobaciones actuales, sin usar la sonda histórica como sustituto:

- Primer intento JDBC: SQLState `08S01`, vendorCode `0`, causa
  `SocketTimeoutException`; no se completó la consulta. No se clasificó como fallo
  de autenticación ni se atribuyó automáticamente a Serverless.
- Segundo intento JDBC: **exitoso**, base exacta, Entra mediante Azure CLI, cifrado y
  verificación de certificado configurados y conexión completada. Todas las
  operaciones fueron SELECT; no se repitió ninguna escritura.
- La sonda confirmó `collation=SQL_Latin1_General_CP1_CI_AS`, `is_dbo=1`,
  `is_db_owner=1` y permisos CREATE TABLE/ALTER dbo/SELECT/INSERT/UPDATE presentes.
  Por tanto, esta identidad tiene privilegios administrativos y **no valida** una
  identidad de ejecución restringida del microservicio.
- `applicationTables=0`, `flywayHistoryTables=0`: no están las tres tablas previstas
  ni el historial. Esto no demuestra que todo el catálogo de la base esté vacío.
- Lectura ARM independiente: `status=Online`, misma collation,
  `useFreeLimit=true`, `freeLimitExhaustionBehavior=AutoPause`, `autoPauseDelay=60`.
  DNS resolvió y TCP 1433 conectó. No se alteró ningún valor.

Logs: `/private/tmp/mtec35-qa-azure-probe.log` (timeout) y
`/private/tmp/mtec35-qa-azure-probe-second.log` (éxito). El `40613` pertenecía al informe
histórico de implementación; no fue el error observado en esta QA. Un timeout seguido
de éxito acredita recuperación de conectividad, no determina su causa.

Hikari en `SqlUserConfig.java:37`: máximo configurable (3 por defecto), mínimo inactivo
0, idle 60 s, vida máxima 10 min, sin keepalive, espera de conexión 35 s. No mantener
el proceso conectado innecesariamente a Serverless. El SDK puede emitir diagnóstico
de selección de credenciales y avisos Netty; no se activó logging de tokens.

La migración es una operación separada del arranque del microservicio. La documentación
distingue identidad migradora con DDL de identidad de ejecución con SELECT/INSERT/UPDATE
y lectura del historial/metadatos. Sin embargo, `ActiveDirectoryDefault` puede seleccionar
Azure CLI u otra credencial disponible; el código no demuestra por sí mismo que exista
una identidad de ejecución restringida ni impide arrancar sql-dev como administrador.
No se certifican permisos mínimos del runtime. El administrador no debe adoptarse como
identidad permanente; no se implementó aquí infraestructura de MTEC-37.

Búsqueda acotada de patrones de claves privadas, tokens GitHub/JWT/AWS y secretos
literales sobre fuentes/configuración versionables: cero candidatos. Se revisó también
el código de conexión: Azure no recibe usuario SQL ni contraseña. Las contraseñas
aleatorias `sa` pertenecen exclusivamente al contenedor desechable de pruebas, nunca
al datasource Azure. El análisis de patrones no sustituye una auditoría de todo el
historial Git, que no se reescribió ni alteró.

**Recursos modificados: ninguno. Migraciones remotas: ninguna.** No se ejecutaron DDL,
DML de pruebas, cambios de permisos, firewall, IAM, Entra, facturación ni creación de
bases/recursos remotos. La lectura JDBC, incluso si despierta Serverless, no se usó
para probar persistencia sobre la base compartida.

## F. Dictamen final

**QA PARTIAL — BLOCKED.** Los defectos reproducidos de control transaccional se
corrigieron y las 111 pruebas normales pasan, incluidas las reglas arquitectónicas.
Faltan comprobaciones indispensables: V1 y metadatos en SQL Server real, commit/rollback
real, concurrencia y recuperación. El perfil SQL falla por infraestructura y sus 29
casos no se han ejecutado. No corresponde QA PASS ni QA PASS WITH NOTES.

No quedan fallos reproducidos abiertos en las pruebas normales. Esto no acredita que
la suite SQL o la fixture TLS vayan a pasar al disponer de un motor; cualquier fallo
posterior debe resolverse y volver a verificarse antes de cambiar el dictamen.

## G. Bloqueos y acciones necesarias para publicar MTEC-35

1. Disponer de un host efímero de QA **Linux x86-64 nativo** con Docker existente,
   acceso al registry de Microsoft y recursos para SQL Server 2022. No usar emulación
   Apple Silicon como evidencia soportada ni crear recursos Azure/workflows de otro
   ticket. Microsoft especifica esa limitación en su
   [documentación de contenedores](https://learn.microsoft.com/en-us/sql/linux/containers/deploy?view=sql-server-ver17).
2. Copiar el estado completo de este worktree, incluidos los archivos nuevos, al
   entorno aislado; no basta con checkout del HEAD, que no contiene la implementación.
   Usar JDK 21 y ejecutar los comandos siguientes. La fixture elige exclusivamente
   su contenedor y crea una base nueva con collation CI, TLS verificado y contraseña
   aleatoria. No admite una URL Azure ni usa variables `USERS_SQL_SERVER` para migrar.

   ```bash
   java -version
   ./mvnw --version
   docker info --format '{{.OSType}}/{{.Architecture}}'
   ./mvnw --batch-mode -Psql-integration clean verify
   ```

3. Exigir BUILD SUCCESS, **111 normales y 29 métodos SQL aprobados**, cero errores y
   cero skipped en ambos conjuntos XML; verificar específicamente que `@BeforeAll`
   aplicó V1 desde vacío y repetir `migrate()` produjo cero nuevas migraciones.
   Inspeccionar `target/surefire-reports/` y `target/failsafe-reports/`; no aceptar un
   build sin perfil ni un mero test-compile. Corregir cualquier fallo dentro de MTEC-35,
   repetir las pruebas afectadas y actualizar este dictamen con evidencia real.
4. Antes de habilitar ejecución sobre Azure, acreditar conectividad Entra/TLS con la
   identidad prevista y separar su privilegio del de migración; no dejar `dbo` como
   identidad permanente. Una futura migración remota requiere autorización explícita,
   revisión de catálogo/historial y el procedimiento ya documentado en README. No es
   una acción ejecutada ni autorizada por esta QA, ni una condición para sustituir
   las pruebas aisladas por escrituras en la base compartida.
