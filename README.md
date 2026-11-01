# ms-users — MarketTec

Dominio de usuarios (MTEC-34) y adaptador relacional (MTEC-35), en el repositorio independiente
`a-calderon-org/ms-users-market-tec`. Java 21, Spring Boot 3.5.16 y Maven Wrapper 3.9.11.
El núcleo y los casos de uso son Java puro. La persistencia SQL es optativa y requiere
un esquema migrado explícitamente. No hay autenticación, sesiones ni endpoints HTTP de negocio.

## Desarrollo local

La configuración y evidencia de MTEC-35 están en [Persistencia SQL](#persistencia-sql--mtec-35)
y en [el informe de implementación](docs/MTEC35_IMPLEMENTATION.md).

Requiere JDK 21; no requiere Maven global ni bases de datos.

```bash
java -version
./mvnw --batch-mode verify
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
curl -i http://localhost:8080/health
```

También se puede ejecutar el JAR con el perfil local:

```bash
java -jar target/ms-users-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

`PORT` (8080) y `SERVICE_NAME` (ms-users) se leen del entorno. `.env.example` es una
referencia; Spring no carga automáticamente `.env`. Sin el perfil `local`, solo se
compone la fundación de health. Con `local`, se añaden los casos de uso y un único
almacén en memoria: todos los datos se pierden al detener el proceso. El puerto de
autorización de vínculos rechaza todas las solicitudes en esta configuración.

El único endpoint implementado es `GET /health`; el resto devuelve 404. El header
`X-Correlation-ID` sirve para correlación, nunca para establecer identidad. Los casos
de uso se invocan dentro del proceso mediante `UserUseCases`; no se añaden rutas
provisionales que acepten IDs, correos o indicadores de verificación como autenticación.

La validación local ejecuta `./mvnw --batch-mode verify` con Temurin 21. Ese comando
compila con `release 21`, ejecuta pruebas unitarias, concurrencia, integración de Spring
y reglas ArchUnit, y produce el JAR ejecutable. La ejecución local puede usar
`--offline` cuando todas las dependencias ya están en la caché Maven.

## Estructura y responsabilidades

```text
src/main/java/com/markettec/users/
  domain/model/             User, UserId, UserProfile, Email, ExternalIdentity
  application/ports/in/     UserUseCases, AuthenticatedPrincipal, VerifiedExternalIdentity
  application/ports/out/    Repositorios, generador, transacciones, autorización de vínculo
  application/usecases/     UserService y GetHealthService
  adapters/in/web/          Health, correlación y errores técnicos del scaffold
  adapters/out/sql/         JDBC, transacciones, validación y sonda Azure de solo lectura
  adapters/out/memory/      InMemoryUserStore
  adapters/out/             UuidUserIdGenerator
  config/                  Composición Spring; selección explícita local/in-memory o SQL
src/test/java/com/markettec/users/
  domain/                   Invariantes, validación y conservación de datos
  application/              Casos de uso, confianza, idempotencia y concurrencia
  adapters/                 Contratos del almacén y HTTP técnico
  architecture/             Dirección de dependencias, independencia y ciclos
  config/                   Composición local y rechazo de vinculación por defecto
```

Dominio y aplicación no dependen de Spring, Jakarta/JPA, Hibernate, Google ni Azure.
Configuración es la raíz de composición y puede acceder a aplicación, dominio y
adaptadores. ArchUnit comprueba esas fronteras y evita ciclos entre capas.

## Modelo e invariantes

- `UserId`: `usr_` seguido de UUID v4 aleatorio, 40 caracteres. Es una decisión interna
  de almacenamiento; no redefine el contrato público de string opaco (máximo 64).
  Nunca se genera a partir de correo, issuer o subject, ni se usa Google `sub` como ID.
- `User`: agregado inmutable con perfil del mismo propietario y estado `ACTIVE` o
  `DEACTIVATED`. El alta interna crea una cuenta activa con correo sin verificar.
  Desactivar es idempotente; una cuenta desactivada no puede leer/actualizar su perfil,
  registrar otra alta con su identidad ya vinculada ni vincular métodos nuevos.
  Sus identidades se conservan y no quedan disponibles para otra cuenta.
- `Email`: subconjunto ASCII de addr-spec, local de máximo 64 caracteres y etiquetas
  DNS válidas. Rechaza espacios periféricos, controles, puntos consecutivos y entradas
  ambiguas; no admite direcciones entre comillas, comentarios ni EAI. Conserva la
  dirección original y deriva una representación normalizada bajando solo el dominio
  con `Locale.ROOT`. No cambia mayúsculas del local, puntos ni sufijos `+`.
  La igualdad de email nunca se usa como mecanismo de búsqueda o fusión de cuentas.
- `ExternalIdentity`: proveedor (`GOOGLE` o `INSTITUTIONAL_EMAIL`), clave exacta
  `(issuer, subject)`, propietario y dirección verificada original. La unicidad abarca
  ambos proveedores; cambiar solo el proveedor no evita el conflicto de clave.
  No existe una operación de reasignación ni fusión automática por correo.
  El adaptador confiable futuro decide la canonicalización del issuer después de
  verificarlo. Los valores no se transforman silenciosamente en el dominio.
- `UserProfile`: propietario, `ProfileDetails`, correo, foto como URI absoluta,
  fecha de registro, fecha de actualización y estado de verificación del correo.
  Los snapshots son inmutables y las fechas no pueden retroceder. Vincular un método
  con otro correo no cambia el correo del perfil ni lo marca como verificado.

El perfil sigue los campos y límites del borrador compartido `UserProfileV1` y
`UpdateUserRequest` de `contracts/openapi/users.json`, cotejados con el modelo Angular:

| Contrato de referencia | Representación interna |
|---|---|
| `id` | `UserProfile.userId`, propiedad del agregado `User` |
| `nombreUsuario` (1–80), `nombre` (1–100), `apellidos` (0–160) | `ProfileDetails`; únicos campos editables |
| `correo`, `fotoPerfil` | `Email`, `URI`; inmutables por la actualización de perfil |
| `fechaRegistro`, `fechaActualizacion` | `Instant`; inicializadas juntas, registro inmutable |
| `verificado` | Estado del correo de perfil, solo actualizado desde evidencia confiable |
| `estado` | `User.status`; no editable por el comando de actualización de perfil |
| `createdAt` aditivo opcional | Derivable del instante; sin DTO ni publicación en esta subfase |

Esta representación interna no es un DTO HTTP. Todavía no se serializan perfiles ni
se eligen valores públicos para `estado`; tampoco se sustituye el string ISO de fechas
por Epoch en los contratos. No se impone unicidad de nombre de usuario, ausente del
contrato inspeccionado. Si se requiere, deberá acordarse su normalización y unicidad.

## Casos de uso y fronteras de confianza

| Operación | Entrada confiable y comportamiento |
|---|---|
| `createUser` | Aprovisionamiento interno autorizado; genera ID, cuenta y perfil. No autentica ni verifica el correo. Cada llamada crea una cuenta distinta. |
| `registerVerifiedIdentity` | Adaptador interno que ya verificó identidad y autorizó el aprovisionamiento. Crea cuenta y vínculo en una transacción, o devuelve el usuario activo asociado a la clave existente. |
| `linkExternalIdentity` | Principal autenticado propietario, evidencia externa verificada y autorización explícita del puerto `IdentityLinkAuthorization` para esa cuenta y esa identidad. |
| `getOwnProfile` | El `UserId` del principal confiable debe coincidir con el propietario seleccionado. |
| `updateOwnProfile` | Mismo control de propietario; solo cambia los tres campos de `ProfileDetails`. |
| `deactivateUser` | Solo propietario autenticado; conserva perfil y vínculos, sin reactivación ni borrado. |

`AuthenticatedPrincipal` y `VerifiedExternalIdentity` son entradas de una frontera
programática confiable, **no pruebas criptográficas por existir como objetos Java**.
No hay adaptador que las construya desde tráfico HTTP en esta subfase. Sus futuras
implementaciones deben verificar las credenciales, titularidad del correo, elegibilidad
institucional, vigencia y protección contra replay antes de llamar a estos casos de uso.
No se recibe un booleano `verified` o `authorized` como prueba del cliente.

La propiedad de la cuenta por sí sola no autoriza asociar un método. El puerto
`IdentityLinkAuthorization` debe validar una concesión explícita ligada al principal,
cuenta, proveedor y clave externa, con prueba reciente de control de ambos métodos.
Si consume una concesión, ese consumo debe participar en la misma transacción y
revertirse ante un fallo. La autorización debe volver a comprobarse en cada repetición;
la idempotencia no permite omitirla. Solo las pruebas usan una política fake con
concesiones explícitas. La configuración local no simula una autorización exitosa.

Las altas repetidas por la misma clave no reemplazan perfil, email ni evidencia previa.
Repetir un vínculo para el mismo propietario/proveedor devuelve el vínculo original;
un propietario o proveedor diferente produce conflicto. Dos métodos solo comparten
cuenta mediante vinculación explícita. Coincidir en correo nunca acredita esa relación.
La desactivación aquí solo cambia el dominio: no promete revocar sesiones futuras.

## Atomicidad, concurrencia y persistencia futura

`InMemoryUserStore` implementa ambos repositorios y `UserTransactions` como una única
unidad. Serializa operaciones mediante el monitor de esa instancia y restaura ambos
mapas si hay una excepción; todos los valores almacenados son inmutables. La unicidad
se comprueba también en la escritura atómica, sin depender de un `find` previo.
Comprueba la existencia y actividad del propietario, y `replace` usa comparación del
agregado esperado para rechazar escrituras obsoletas.

Las garantías comprobadas abarcan hilos y múltiples instancias de `UserService` que
comparten **el mismo** almacén en **un** proceso. No abarcan varias JVM, reinicios,
réplicas ni fallos de infraestructura. Las copias de mapas por transacción son una
solución de desarrollo/pruebas, no una elección de almacenamiento productivo.

El adaptador SQL de MTEC-35 implementa este contrato de `UserTransactions`:

1. Ambos repositorios comparten transacción con commit atómico y rollback completo.
2. Restricción única de almacenamiento sobre `(issuer, subject)`, independiente del
   proveedor; ID de usuario único y referencia íntegra a su propietario.
3. Aislamiento serializable o bloqueos equivalentes para coordinar alta, vínculo,
   actualización y desactivación; comparación de versión/agregado para escrituras.
4. Reintentos acotados de la unidad completa ante carreras de unicidad/serialización,
   resolviendo un alta concurrente al usuario ganador sin cuentas huérfanas.
5. Autorización consumible coordinada con la misma unidad, sin pérdida de concesiones
   por un rollback de cuenta/identidad.

MTEC-35 incorpora JDBC para Azure SQL y conserva este almacén de pruebas. La conexión
Azure se validó mediante consultas de solo lectura; la migración remota no se ejecutó.
La ejecución de las pruebas de SQL Server real está pendiente por falta de motor aislado compatible.

## Pruebas y límites pendientes

Las pruebas cubren creación interna/externa, IDs y emails inválidos, integridad del
perfil, separación Google/interna, propiedad, actualización, desactivación, rechazo de
vínculos sin concesión, asociación de varios métodos, conflictos de propietario y
proveedor, alta y vínculo idempotentes, unicidad sin lectura previa, rollback ante fallos
después de escribir, rechazo de agregados obsoletos y carreras concurrentes entre
servicios. También verifican health, composición Spring y reglas hexagonales.

### QA Review de MTEC-34 — 2026-10-31

**QA PASS WITH NOTES.** Revisión de todos los archivos de producción, pruebas,
configuración y Maven Wrapper; sin defectos bloqueantes del dominio o los casos de uso.
`./mvnw --batch-mode clean verify` pasó con Temurin **21.0.13+9** y Maven **3.9.11**:
**81 pruebas, 0 fallos, 0 errores, 0 omitidas**, incluidas las **4 reglas ArchUnit**.
Se generó `target/ms-users-0.1.0-SNAPSHOT.jar`, con `JarLauncher` y bytecode Java 21.

La primera ejecución dentro del sandbox terminó con 6 errores de inicialización de
Mockito porque el entorno impedía adjuntar su agente; las otras 75 comprobaciones
pasaron. La ejecución completa del comando anterior fuera del sandbox pasó sin
modificar dependencias, configuración de pruebas ni omitir pruebas. También se
comprobó una ejecución offline usando la caché local y carga previa del agente.

La revisión cubrió creación, validación de IDs, alta externa, unicidad de
`(issuer, subject)`, altas concurrentes e idempotentes, vinculación autorizada y
rechazada, cuentas desactivadas, consulta/actualización propia, acceso cruzado,
escrituras obsoletas, rollback, aislamiento del almacén, Spring y ArchUnit.
Las garantías de concurrencia se limitan al almacén compartido en una JVM.

En una copia temporal se ejecutaron además dos sondas de QA: independencia entre
instancias del almacén y bloqueo de lectura concurrente hasta completar un rollback.
Ambas pasaron. Las pruebas existentes detectaron siete mutaciones deliberadas,
aplicadas individualmente en esa copia: omitir propiedad, autorización de vínculo,
estado activo, unicidad, rollback, comparación de escritura y añadir Spring al dominio.
Cada mutación compiló y produjo fallos de aserción/reglas; ninguna se aplicó al repositorio.
No se añadieron pruebas permanentes sin un defecto funcional comprobado.

| Hallazgo | Severidad y evidencia | Resolución / verificación |
|---|---|---|
| Exclusiones de archivos locales incompletas | Baja; `.gitignore` solo excluía `.env`, sin variantes, logs ni patrones comunes de claves/credenciales | Añadidas exclusiones y excepción para `.env.example`; comprobadas con `git check-ignore` y revisión del staging |
| Finales de línea del wrapper Windows | Baja; `git diff --cached --check` interpretaba los CRLF de `mvnw.cmd` como espacios sobrantes | `.gitattributes` normaliza el índice y conserva CRLF al hacer checkout en Windows; comprobación del staging sin errores |
| Workflow heredado fuera del alcance | Media de alcance; `.github/workflows/ci-ms-users.yml` estaba sin seguimiento | Conservado localmente, excluido del commit y la publicación de MTEC-34; CI/CD pendiente de MTEC-37 |
| Frontera de autenticación pendiente | Nota; las entradas confiables son objetos Java construibles, no evidencia criptográfica | No existen rutas HTTP de negocio; pruebas rechazan headers/flags como acceso. La verificación y concesiones reales requieren MTEC-36 |

No quedan defectos funcionales conocidos pendientes dentro de MTEC-34. La revisión
de futuros adaptadores reales queda pendiente de sus tickets; esta QA no los acredita.
MTEC-34 no se considera Done hasta revisión y fusión del PR y verificación de sus
comprobaciones. Este trabajo no crea un PR ni configura pipelines de CI/CD.

Para MTEC-36 y trabajos posteriores quedan pendientes:

- Aprobar política de alta, dominios institucionales admitidos y qué evidencia acredita
  una dirección; Google/email no se integran ni se verifican en este ticket.
- Verificador Google criptográfico, política de issuer/audience y canonicalización,
  email institucional verificado y mapeo estable de subject para ese método.
- Construcción confiable de principales, prueba reciente y concesiones de vinculación,
  expiración/replay/consumo y decisión explícita sobre reactivación/recuperación.
- JWT/JWKS, algoritmos y claves, refresh, sesiones, logout/revocación, correos y magic
  links; nada de ello está implementado ni aprobado por esta subfase.
- Adaptadores HTTP autenticados, traducción al DTO de perfil, errores de negocio,
  autorizaciones, representación pública de estados y políticas de APIM.

No se han modificado OpenAPI, contratos compartidos ni envelopes de eventos. Este
núcleo no necesita cambios contractuales públicos para ejecutarse. Antes de exponer
vinculación, desactivación o correo institucional deberán acordarse operaciones y
esquemas (actualmente ausentes del borrador inspeccionado) mediante MTEC-15, además de
revisar conjuntamente autorización de consulta y respuestas 401/403. El contrato
conceptual del principal y ADR-004 siguen siendo propuestas; aquí no se aprueban claims,
parámetros JWT ni políticas de seguridad.

## Procedencia del scaffold y referencias

La fundación se reutilizó de `app/ms-users` del repositorio
[`a-calderon-org/market_tec`](https://github.com/a-calderon-org/market_tec), inspeccionado
en `5dc2d1ebf981b8009fb5b268d7c857e6a1167496`. El último commit que modificó ese scaffold
es `2b1f5286088a117a144d6321e8bc8c85aa0ab764`
(`feat(backend): establish hexagonal microservices foundation`).

Se conservaron su paquete `com.markettec.users`, estructura hexagonal, POM, Maven
Wrapper, configuración, health/correlación/errores y pruebas técnicas. Se extendieron
las reglas de arquitectura para permitir composición de adaptadores y prohibir SDKs
en el núcleo. El workflow de CI heredado queda fuera de la publicación de MTEC-34.

Referencias inspeccionadas, sin modificarlas:

- [Scaffold de origen](https://github.com/a-calderon-org/market_tec/tree/5dc2d1ebf981b8009fb5b268d7c857e6a1167496/app/ms-users).
- [ADR-001 Accepted](https://github.com/a-calderon-org/market_tec/blob/5dc2d1ebf981b8009fb5b268d7c857e6a1167496/context/decisions/ADR-001-distribucion-microservicios.md).
- [Users OpenAPI, borrador](https://github.com/a-calderon-org/market_tec/blob/5dc2d1ebf981b8009fb5b268d7c857e6a1167496/contracts/openapi/users.json).
- [Contrato conceptual de identidad, propuesto](https://github.com/a-calderon-org/market_tec/blob/5dc2d1ebf981b8009fb5b268d7c857e6a1167496/contracts/identity/IDENTITY_CONTRACT.md).

La implementación de MTEC-34 se desarrolla exclusivamente aquí. El scaffold anterior
se preserva como origen histórico de migración; no se modifica ni se mantiene una
segunda implementación del dominio allí. Su retirada y actualización de referencias
son un trabajo de migración separado.

## Persistencia SQL — MTEC-35

### Arquitectura y diseño

Se eligió **Spring JDBC** frente a JPA porque los puertos ya expresan snapshots
inmutables, comparación del agregado completo y repetición de la unidad transaccional.
JDBC permite expresar ese contrato con consultas pequeñas, sin duplicar el dominio en
entidades mutables ni introducir dependencias Spring/JPA en aplicación o dominio.
No se cambian los puertos, los casos de uso ni las pruebas originales.

`SqlUserStore` implementa los dos repositorios y `UserTransactions`. Cada operación
pública aislada abre una transacción; dentro de un caso de uso comparte la misma
conexión y transacción. El almacén debe ser la misma instancia para los tres puertos.
No se admite envolverlo en una transacción externa de aislamiento desconocido.
La futura autorización consumible debe participar en esta conexión/transacción y
volver a ejecutarse en cada intento. Por ahora SQL y memoria **rechazan todos los
vínculos** en la composición real; solo las pruebas conceden autorizaciones explícitas.

| Tabla | Datos y restricciones |
|---|---|
| `dbo.users` | PK `user_id` opaco `usr_` UUID v4; estado con CHECK; versión bigint no negativa |
| `dbo.profiles` | PK/FK `user_id`, relación 1:1; todos los campos actuales del perfil, NOT NULL; sin unicidad de correo o nombre |
| `dbo.external_identities` | FK al propietario, proveedor con CHECK, issuer y subject exactos, correo verificado original; PK calculada sobre dos SHA-256 independientes |

Issuer y subject no tienen un máximo en el dominio. Se conservan como `nvarchar(max)`;
la PK combina `issuer_hash` y `subject_hash`, calculados y persistidos por SQL Server
sobre cada cadena UTF-16LE completa. Impone unicidad efectiva de `(issuer, subject)`
independiente del proveedor, sin normalización, concatenación ambigua ni truncamiento.
El adaptador compara también las cadenas originales: una hipotética colisión falla
como error técnico y nunca asocia otra identidad. Las búsquedas usan esa PK.
`ix_external_identities_user` soporta la relación referencial al propietario; no se
crean índices de correo porque no existe búsqueda ni fusión por correo.

Los tamaños NVARCHAR de los nombres admiten el doble de unidades UTF-16 que los
máximos de puntos de código del dominio, conservando caracteres suplementarios.
Las fechas guardan segundos y nanosegundos por separado: `datetime2(7)` perdería
precisión de `Instant`. La URI y el correo mantienen su representación original.
Las reglas de negocio siguen ejecutándose en los constructores y métodos de dominio.

### Transacciones, concurrencia y errores

- Aislamiento `SERIALIZABLE` para lectura de identidad, alta, vínculo, actualización y
  desactivación; la PK única y las FK constituyen la última defensa en almacenamiento.
- `replace` vuelve a leer y compara el agregado esperado. Actualiza `users` con
  `WHERE version=?` e incrementa la versión, después escribe el perfil en la misma
  transacción. Una comparación fallida produce `CONCURRENT_MODIFICATION`.
- La versión es un detalle interno del adaptador. Los puertos de MTEC-34 comparan el
  valor completo, sin exponer un token de versión. Como memoria, aceptan un snapshot
  cuyo valor completo vuelva a ser igual; no prometen detectar ciclos ABA históricos.
- Deadlock, timeout de bloqueo, conflictos de serialización y carreras de unicidad
  identificados por códigos SQL Server se reintentan hasta **4 intentos**, con espera
  breve y jitter, después del rollback. Se repite todo el trabajo, incluida autorización;
  no se reintenta únicamente la sentencia de inserción ni se deja una cuenta perdedora.
- Una unidad anidada usa un savepoint. Un fallo de negocio puede revertir solo ese
  ámbito; un fallo técnico SQL o del savepoint invalida también la unidad exterior,
  incluso si el llamador lo captura: algunos errores SQL revierten toda la transacción.
  Los errores de conectividad y resultados inciertos del commit no se reproducen
  automáticamente. Solo se repite la misma excepción transitoria del callback tras
  completar su rollback; nunca un error de apertura, commit o rollback. Ninguna
  operación depende de efectos externos no transaccionales dentro del callback.
- Duplicados de usuario, conflicto de identidad, ausencia, desactivación y snapshot
  obsoleto se traducen a las razones existentes. Fallos técnicos quedan como
  `UserPersistenceException`, con mensaje neutro y sin publicar excepciones SQL,
  sentencias, correos o credenciales. No se define un nuevo contrato HTTP de errores.

A diferencia del monitor de memoria en una JVM, SQL coordina varias conexiones y
procesos y conserva información tras reiniciar. Puede agotar reintentos bajo contención
y producir errores de disponibilidad. La suite real pendiente debe acreditar estas
propiedades antes de declarar el adaptador listo para producción.

### Perfiles y variables

| Perfil Spring | Comportamiento |
|---|---|
| ninguno | Fundación HTTP/health existente; sin repositorio ni datasource |
| `local` o `in-memory` | Un único `InMemoryUserStore`; no abre conexiones |
| `sql-dev` | Azure SQL con Microsoft Entra; valida esquema y checksum Flyway al arrancar; **jamás migra automáticamente** |
| `sql-integration` | Composición JDBC sobre un datasource aislado provisto por la prueba; no hereda servidor Azure ni credenciales |

Seleccionar SQL y memoria simultáneamente produce error. Un fallo SQL no activa memoria.
`DataSourceAutoConfiguration` y `FlywayAutoConfiguration` están excluidas: el datasource
SQL es explícito y la validación es de solo lectura. No hay Hibernate ni generación
automática de DDL. `SqlSchemaValidator` comprueba la base, checksum/historial Flyway,
columnas/tipos/tamaños/NOT NULL, PK, FK y CHECK habilitados, hashes persistidos e índice
de propietario. Una base vacía rechaza el arranque SQL hasta migrarse por separado.

| Variable | Valor de desarrollo / finalidad |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `local`, `in-memory` o `sql-dev` |
| `USERS_SQL_SERVER` | `sql-markettec-dev.database.windows.net` |
| `USERS_SQL_DATABASE` | `sqldb-ms-users-dev` |
| `USERS_SQL_PORT` | `1433` |
| `USERS_SQL_POOL_SIZE` | `3`, pequeño para desarrollo |
| `PORT`, `SERVICE_NAME` | `8080`, `ms-users` |

`.env.example` es documentación, no un archivo cargado automáticamente por Spring.
No definir usuario SQL, contraseña ni tokens para Azure. El modo de desarrollo es
fijo `ActiveDirectoryDefault`; el driver usa `DefaultAzureCredential` y puede tomar
el contexto de Azure CLI. Revisar que otras credenciales del entorno no seleccionen
una identidad diferente. La sonda realizada confirmó uso de `AzureCliCredential`.

Pool Hikari: mínimo de conexiones inactivas 0, idle timeout 60 segundos, sin keepalive,
máximo de vida 10 minutos. Cerrar el proceso cuando no sea necesario; incluso una
conexión inactiva puede impedir la pausa automática. No mantener una aplicación o
sonda conectada permanentemente a la base gratuita.

### Microsoft Entra y conexión JDBC de solo lectura

Versiones efectivas: driver Microsoft **12.10.2.jre11** (compatible con Java 21),
Azure Identity **1.15.3** y Flyway **11.7.2**, incluido **flyway-sqlserver**. Spring Boot
3.5.16 gestiona JDBC y Flyway. El modo `ActiveDirectoryDefault` y esa dependencia están
contrastados con la [documentación de Microsoft](https://learn.microsoft.com/en-us/sql/connect/jdbc/connecting-using-azure-active-directory-authentication)
y el [módulo oficial de Flyway SQL Server](https://documentation.red-gate.com/flyway/reference/database-driver-reference/sql-server-database).

```bash
java -version
./mvnw --version
az version
az account show --query '{tenantId:tenantId,subscription:id,name:name,state:state}'
```

Si el contexto no existe o expiró, el usuario debe ejecutar `az login` en su terminal
y seleccionar el tenant/suscripción institucional correctos. No copiar tokens de
`az account get-access-token`, contraseñas ni cachés al repositorio. La selección debe
compararse con el contexto autorizado, sin asumir que es la sesión del portal.

La conexión equivalente es:

```text
jdbc:sqlserver://sql-markettec-dev.database.windows.net:1433;databaseName=sqldb-ms-users-dev;authentication=ActiveDirectoryDefault;encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net
```

El código configura esas propiedades mediante `SQLServerDataSource`, con timeout y
verificación del certificado. Nunca habilita confianza indiscriminada del certificado.
La sonda no inicia Spring ni Flyway y solo ejecuta SELECT:

```bash
./mvnw --batch-mode compile dependency:build-classpath -Dmdep.outputFile=target/jdbc-classpath.txt
java -cp "target/classes:$(cat target/jdbc-classpath.txt)" \
  com.markettec.users.adapters.out.sql.AzureSqlProbe
```

Comprueba `DB_NAME(), SUSER_SNAME(), USER_NAME()`, base exacta y permisos; publica
solo presencia de identidades, permisos y número de tablas, sin nombres personales.
Un fallo imprime SQLState/código/tipos de causa, nunca el mensaje completo de autenticación.
No obtiene ni imprime tokens. Las librerías pueden emitir diagnóstico de credenciales
seleccionadas, sin el valor de ningún token.

En esta ejecución el primer intento devolvió `40613`; el segundo conectó y confirmó
la base exacta y permisos. Un error parecido requiere distinguir pausa/reanudación,
cuota mensual y disponibilidad antes de reintentar. DNS/firewall/TLS/tenant y permisos
se diagnostican sin cambiar red, autenticación o recursos automáticamente.

### Flyway y procedimiento seguro para Azure

La migración aditiva `src/main/resources/db/migration/V1__initial_users_schema.sql`
crea únicamente las tres tablas e índice. No contiene borrados ni reescritura de datos.
`baselineOnMigrate` no está habilitado y `clean` está deshabilitado en los puntos de uso.
La única llamada a `migrate()` incluida en el código es la fixture de SQL Server aislado.
**No se ha ejecutado la primera migración contra Azure ni existe comando de arranque
que la ejecute. Se requiere autorización explícita posterior para esa ejecución.**

Procedimiento de esa futura intervención autorizada:

1. Ejecutar la sonda JDBC con la misma identidad que hará la migración y confirmar
   `sqldb-ms-users-dev`, tenant/suscripción y privilegios de migración.
2. Revisar V1 completa, su checksum, los tipos/índices y su resultado en SQL Server
   aislado. Esta validación del motor está pendiente; no reemplazarla por pruebas mock.
3. Inspeccionar **todas** las tablas/esquemas de aplicación y el historial, si existe:

   ```sql
   SELECT DB_NAME(), SUSER_SNAME(), USER_NAME();
   SELECT SCHEMA_NAME(schema_id) AS schema_name, name
   FROM sys.tables WHERE is_ms_shipped = 0;
   SELECT OBJECT_ID('dbo.flyway_schema_history') AS history_object_id;
   -- Solo si existe el historial:
   SELECT installed_rank, version, description, checksum, success
   FROM dbo.flyway_schema_history ORDER BY installed_rank;
   ```

4. Confirmar que no hay tablas/datos preexistentes que interfieran; detenerse ante
   cualquier diferencia. No adoptar automáticamente una base existente con baseline.
5. Acordar ventana y recuperación. En una base vacía, Flyway debe ejecutar V1 en su
   transacción SQL Server. Si falla, inspeccionar catálogo/historial sin borrar ni
   reparar automáticamente. El historial podría existir aunque la migración revierta.
6. Con autorización explícita y revisión completa, un operador ejecutará Flyway API
   con el datasource Entra de migración, esquema `dbo`, `cleanDisabled(true)` y
   `baselineOnMigrate(false)`. No usar el arranque de la aplicación para esa primera vez.
7. Confirmar historial exitoso, `validate()` y metadatos; después arrancar `sql-dev`
   con una identidad de aplicación restringida.

No modificar V1 una vez aplicada. Para un defecto posterior, preparar otra migración
aditiva revisada. Ante datos existentes, coordinar recuperación por el responsable y
revisar las capacidades de backup/restauración de la oferta antes de autorizar acciones.
Esta subfase no realiza restauraciones ni crea bases alternativas.

### Privilegios y MTEC-37

La identidad de migración necesita `CREATE TABLE` en la base y `ALTER` en `dbo`, además
de los permisos necesarios para historial e índices. La identidad de aplicación debe
restringirse a `SELECT`, `INSERT` y `UPDATE` sobre las tablas necesarias; no requiere
borrado ni DDL. La validación de arranque también requiere lectura del historial Flyway
y visibilidad de metadatos/definiciones de las tablas comprobadas. Revisar esas concesiones
mínimas junto con la estrategia de migración separada. No ejecutar concesiones aquí.

El administrador institucional usado para las comprobaciones no es una credencial
permanente del servicio. `ActiveDirectoryDefault` ya admite Managed Identity en la
cadena, pero MTEC-37 debe elegir una identidad de ejecución concreta, restringir la
selección en producción, conceder permisos mínimos y separar la identidad de migración.
No se crean identidades, usuarios SQL ni recursos ni se modifican permisos, firewall,
Entra, políticas, facturación o tamaño de la base en MTEC-35.

La [oferta gratuita de Azure SQL](https://learn.microsoft.com/en-us/azure/azure-sql/database/free-offer?view=azuresql)
puede pausar la base al agotarse la asignación mensual si tiene `AutoPause`, y puede
necesitar reanudación al recibir conexiones. No se presupone disponibilidad continua.
Se verificó `useFreeLimit=true`, `freeLimitExhaustionBehavior=AutoPause` y
`autoPauseDelay=60`, sin cambiar esa configuración ni habilitar cobros por excedentes.

### Pruebas locales y SQL Server

```bash
java -version
./mvnw --batch-mode clean verify
# Solo con Docker Linux x86-64 nativo y recursos suficientes:
# Establecer Y únicamente después de aceptar el EULA de SQL Server Developer:
MTEC35_SQLSERVER_ACCEPT_EULA=Y bash scripts/qa-mtec35-sql.sh
```

La implementación inicial reportó 105 pruebas normales. La revisión QA conserva las
**81 originales** y amplía a **30** las de mapeos, transacciones, errores y perfiles:
**111 pruebas normales**, incluidas las cuatro reglas ArchUnit. Surefire usa el agente
explícito de Mockito para evitar depender de autoanexión de la JVM. Los mocks no validan
SQL. El comando normal puede terminar verde sin descubrir ni compilar los casos SQL;
la validación de persistencia requiere el perfil Maven y su informe Failsafe.
Los resultados independientes y bloqueos están en [MTEC35_QA_REVIEW.md](docs/MTEC35_QA_REVIEW.md).

El perfil **Maven** `sql-integration` añade `src/sql-test/java` y Failsafe; no confundirlo
con el perfil Spring del mismo nombre. La fixture crea un contenedor SQL Server 2022
CU18 nuevo y una base vacía exclusiva, confirma `DB_NAME()`, ejecuta Flyway y verifica
que repetir migración no aplica nada. No usa Azure. Las credenciales son aleatorias y
solo de ese contenedor. TLS permanece habilitado: la fixture genera certificado y
truststore efímeros y verifica el certificado sin confianza indiscriminada. No usa H2,
credenciales fijas, volúmenes persistentes ni limpieza SQL destructiva.

La suite inicial contenía 24 casos; QA conserva los 29 recibidos y amplía a 34. Comprueba datos completos,
consultas por ID/identidad, perfiles, vínculos,
unicidad real, conflictos, idempotencia, FK, commits/rollback, savepoints, versiones,
escrituras concurrentes, alta concurrente sin cuentas perdedoras, desactivación,
checksum/esquema, comparación con memoria y recuperación tras reiniciar tanto el contexto
Spring como el motor SQL Server. La base efímera usa `SQL_Latin1_General_CP1_CI_AS`, igual
que Azure. Failsafe falla si no descubre casos en el perfil. No hay pruebas
SQL marcadas como omitidas automáticamente: un entorno incompatible hace fallar
explícitamente la preparación.

El equipo de esta ejecución es Apple Silicon ARM64 y Docker no está iniciado. Microsoft
solo soporta los [contenedores SQL Server sobre Linux Intel/AMD x86-64](https://learn.microsoft.com/en-us/sql/linux/containers/deploy?view=sql-server-ver17).
La fixture verifica la arquitectura **del motor Docker**, permitiendo un cliente ARM
con un motor x86-64 nativo; no presupone emulación local. El modo estricto requiere
también un cliente Linux x86-64 y publica el puerto SQL solo en `127.0.0.1` del runner.
El workflow `.github/workflows/qa-mtec35-sql.yml` ejecuta ambas suites sobre
`ubuntu-24.04` y Java 21 mediante push de la rama MTEC-35. El digest de CU18 está
fijado, Testcontainers 1.21.4 usa su módulo Microsoft y no se necesitan secrets Azure.
Los cinco casos añadidos verifican historial Flyway, aislamiento real, deadlock y
reintento completo, ausencia de replay después de commit real y permisos de un
runtime SQL restringido exclusivamente local. No certifican una identidad Entra remota.
El verificador `scripts/verify-mtec35-reports.py` exige 111 casos normales y los 34
métodos SQL del inventario, todos ejecutados y aprobados sin omisiones. El comando
normal de Maven continúa siendo válido para desarrollo Mac y no acredita QA SQL.
El resultado remoto y los enlaces de evidencia constan en `docs/MTEC35_QA_REVIEW.md`.
