# MTEC-35 — Subfase 1: informe de implementación

Fecha local: 2026-10-31, America/Costa_Rica. Esta entrega comprende implementación;
no constituye la QA Review independiente ni inicia MTEC-37.

## Estado Git

- Remoto verificado: `https://github.com/a-calderon-org/ms-users-market-tec.git`.
- `git fetch origin` completado. `origin/main` incluye el merge de MTEC-34:
  `db8e352e876a006d8ca5034baae0e4a714e9097d`, con padres
  `6216159fbd677ae76c61a9423e70794342d861b4` y
  `43374633a5bc7d230aef3e8b8f6f389ec8e0a913`.
- HEAD inicial y final: `db8e352e876a006d8ca5034baae0e4a714e9097d`.
- Rama creada desde `origin/main`: `feat/mtec35-azure-sql-persistence`.
- Worktree conservado: `/private/tmp/markettec-mtec35-worktree`.
- Índice sin cambios; todos los cambios de esta entrega están sin staging.
- No se ejecutaron commit, push, PR, merge, rebase, deploy ni operaciones destructivas.
- El checkout original conserva la rama MTEC-34 y el archivo ajeno sin seguimiento
  `.github/workflows/ci-ms-users.yml`. No fue alterado ni copiado al worktree.

Archivos existentes modificados (5):

```text
.env.example
README.md
pom.xml
src/main/java/com/markettec/users/config/LocalUserConfig.java
src/main/resources/application.yml
```

Archivos nuevos (15):

```text
docs/MTEC35_IMPLEMENTATION.md
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

## Implementación

Spring JDBC preserva los snapshots inmutables y el contrato compare-and-set de los
puertos existentes sin entidades JPA paralelas. No se modificó dominio, aplicación,
contratos públicos ni ninguna prueba existente. Se mantiene in-memory y su perfil
`local`; `in-memory` es un alias explícito. No hay fallback desde SQL a memoria.

V1 crea `users`, `profiles` y `external_identities`, con PK, FK, NOT NULL, CHECK e índice
de propietario. User usa el ID opaco existente, nunca correo o subject como PK. Los
nombres admiten Unicode suplementario, las fechas conservan nanosegundos, el correo
y la foto conservan sus valores originales. No se añaden tablas de autenticación.

La unicidad efectiva de issuer/subject utiliza dos hashes SHA-256 calculados y
persistidos sobre sus cadenas completas. Es necesario porque el dominio no limita
su longitud y SQL Server no permite indexar NVARCHAR(MAX) directamente. No depende del
proveedor ni de la collation para comparar identidad. Se comprueba la clave completa
al recuperar el registro y se falla ante una hipotética colisión; no se reasigna.

`SqlUserStore` implementa ambos repositorios y transacciones: lectura por ID/clave,
creación, reemplazo de perfil/agregado, vinculación idempotente y desactivación mediante
los casos de uso existentes. SERIALIZABLE coordina operaciones de cuenta/identidad;
`version bigint` protege la escritura tras comparar el agregado esperado. Hasta cuatro
intentos de la unidad completa resuelven carreras de unicidad/serialización. Commit
incierto y conectividad no se reproducen. Un fallo técnico capturado dentro del callback
marca rollback e impide confirmar una creación/actualización parcial. Unidades anidadas
usan savepoints. Las excepciones técnicas se separan de los motivos del dominio.

`sql-dev` usa datasource Entra explícito, pool pequeño sin keepalive ni mínimo de
conexiones abiertas. Valida base, historial/checksum y metadatos al arrancar. Flyway
no se autoconfigura ni ejecuta remotamente. `sql-integration` requiere un datasource
suministrado por la fixture de pruebas. La política real de vinculación sigue denegada
hasta disponer de un adaptador de autorización confiable en el trabajo correspondiente.

## Azure: evidencia de solo lectura

Herramientas comprobadas: Azure CLI 2.91.0, Maven Wrapper 3.9.11. Java por defecto era
Oracle 24.0.2; todas las compilaciones y pruebas reportadas usaron el JDK local Temurin
21.0.13+9, sin instalación global.

`az account show` confirmó suscripción habilitada **Azure for Students** y tenant activo.
Se consultaron sus identificadores para comprobar el contexto; no se copian al informe
por no ser necesarios para reproducir la configuración. La identidad del portal no se
supuso a partir de ese contexto: se realizó JDBC real con Entra.

Sonda `AzureSqlProbe`, driver **12.10.2.jre11**, Azure Identity **1.15.3**:

1. Primer intento: SQLState `S0001`, código SQL Server **40613**. No se ejecutaron SELECT
   ni migraciones en ese intento. DNS resolvió correctamente y la consulta ARM de solo
   lectura informó la base `Online`.
2. Segundo intento: **conectividad JDBC verificada**, TLS habilitado, certificado
   verificado y `ActiveDirectoryDefault` con **AzureCliCredential** seleccionado.

Salida funcional del segundo intento, sin información personal ni tokens:

```text
database=sqldb-ms-users-dev; loginPresent=true; databaseUserPresent=true
can_create_table=1
can_alter_dbo=1
can_select_dbo=1
can_insert_dbo=1
can_update_dbo=1
applicationTables=0
flywayHistoryTables=0
Read-only JDBC probe complete; no migrations executed.
```

El error inicial es compatible con indisponibilidad/reanudación, pero no se atribuye
una causa única sin evidencia adicional. La conexión posterior sí acredita acceso
desde Java; no acredita ejecución de V1 ni permisos de la futura identidad productiva.
El SDK emitió un aviso de versiones Netty gestionadas por Spring Boot distintas de
las usadas para compilar Azure SDK; la adquisición Entra y consulta JDBC completaron.

Configuración consultada sin cambios: `canadacentral`, `useFreeLimit=true`,
`freeLimitExhaustionBehavior=AutoPause`, `autoPauseDelay=60`. No se habilitaron excedentes
ni se alteraron firewall, IAM, Entra, recursos o facturación. No se solicitaron ni
almacenaron tokens o credenciales Azure.

**Migraciones remotas: no ejecutadas.** La futura primera migración exige autorización
explícita, validación aislada y nueva revisión del catálogo completo e historial. La
sonda actual revisa únicamente presencia de las tres tablas previstas y del historial;
no sustituye esa revisión exhaustiva antes de migrar.

## Verificación y resultados reales

| Ejecución | Resultado |
|---|---|
| Baseline desde origin/main, Java 21, `./mvnw --batch-mode clean verify` | 81 pruebas, 0 fallos, 0 errores, 0 omitidas |
| Final, Java 21, `./mvnw --batch-mode clean verify` | **105 pruebas aprobadas**, 0 fallos, 0 errores, 0 omitidas; BUILD SUCCESS; JAR generado |
| ArchUnit final | 4 reglas aprobadas; independencia y dirección hexagonal conservadas |
| Nuevas pruebas normales | 24 aprobadas: 19 de control/mapeo JDBC y 5 de configuración |
| `./mvnw --batch-mode -Psql-integration clean verify` | Fuentes SQL compiladas; 105 pruebas normales aprobadas; preparación SQL bloqueada por Docker no disponible |
| Failsafe SQL final | Reporta 1 error de inicialización, 0 fallos, 0 omitidas; **0 de los 24 casos SQL ejecutados** |
| Flyway V1 sobre SQL Server aislado | **Pendiente**, ningún motor SQL aislado disponible |
| Sonda JDBC Azure de solo lectura | Exitosa en segundo intento; no contiene llamadas Flyway |
| Revisión Git | Sin staging, sin cambios a pruebas existentes; diff sin errores de espacios |

Bloqueo SQL: Docker instalado pero el daemon no está iniciado (`Connection refused` /
`Could not find a valid Docker environment`). El equipo es ARM64. La primera versión
de la fixture rechazó la arquitectura del cliente; se mejoró para comprobar la del
motor Docker y permitir un host remoto x86-64 nativo. La ejecución final quedó bloqueada
al consultar Docker, antes de crear certificados, contenedor o base. No se intentó
emulación SQL Server x86-64 ni se afirmó soporte nativo en Apple Silicon.

Los mocks validan control de transacciones, clasificación/reintentos, mensajes neutros,
propiedad, mapeo exacto, hashes y selección de perfiles. **No son evidencia de semántica
SQL, commit/rollback real, DDL o concurrencia entre procesos.**

La suite SQL preparada cubre los 15 grupos solicitados: usuario, perfil, vinculación,
unicidad, conflictos, idempotencia, commit, rollback de todas las tablas, locking,
actualizaciones/altas concurrentes, ambas consultas, desactivación, reinicio del contexto
y FK. Añade comprobaciones de evidencia original, Unicode/claves largas, savepoints,
rollback aun capturando el fallo del perfil, carrera vínculo/desactivación, validación
del esquema y reaplicación sin nuevas migraciones. Todos esos resultados siguen
pendientes del motor real.

Evidencia local conservada fuera del repositorio:

```text
/private/tmp/mtec35-baseline.log
/private/tmp/mtec35-jdbc-probe.log
/private/tmp/mtec35-jdbc-probe-second.log
/private/tmp/mtec35-sql-integration-final.log
/private/tmp/mtec35-clean-verify-final.log
```

## Riesgos y pendientes para QA

- Ejecutar los 24 casos con Docker Linux x86-64 nativo antes de dar por validados V1,
  certificados de la fixture, metadatos y garantías de concurrencia/rollback SQL.
- La unicidad por hashes tiene riesgo teórico de colisión; el comportamiento es fallar
  cerrado. Los valores completos se conservan y nunca se truncan ni normalizan.
- La comparación del agregado tiene la misma semántica de valor que memoria; la versión
  interna no añade un token al puerto ni detecta históricamente todos los ciclos ABA.
- SQL puede agotar reintentos o perder disponibilidad. Memoria solo coordina una instancia
  en una JVM y pierde sus datos al reiniciar. No se promete equivalencia de disponibilidad.
- Un callback repetido no debe publicar efectos externos. Una futura concesión consumible
  necesita la misma transacción; no se implementó autenticación ni consumo en esta subfase.
- La oferta gratuita puede pausar por cuota mensual y por inactividad; cerrar las
  conexiones innecesarias y no mantener sondeos permanentes.
- MTEC-37 debe proporcionar identidad de ejecución restringida, revisar permisos mínimos
  y separar migración de aplicación. El administrador actual no debe quedar como identidad
  permanente. La validación también requiere lectura de historial y metadatos.
- La primera migración Azure permanece pendiente de autorización explícita y revisión
  del estado real. No se inicia esa intervención ni QA independiente automáticamente.

**MTEC-35 — Subfase 1 completada; pendiente QA Review.** Los entregables de implementación
están preparados. La validación de SQL Server aislado permanece como limitación declarada
para la revisión; esta entrega no afirma preparación productiva ni migración remota.

## Revisión posterior

La QA independiente, sus correcciones y los recuentos actuales se documentan en
[MTEC35_QA_REVIEW.md](MTEC35_QA_REVIEW.md). Los resultados de este informe corresponden
a la subfase de implementación; no acreditan ejecución SQL real en la QA.
