# ms-users — MarketTec

Implementación inicial del dominio de usuarios (MTEC-34), en el repositorio independiente
`a-calderon-org/ms-users-market-tec`. Java 21, Spring Boot 3.5.16 y Maven Wrapper 3.9.11.
El núcleo y los casos de uso son Java puro. No hay autenticación, sesiones, persistencia
productiva ni endpoints HTTP de negocio.

## Desarrollo local

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
  adapters/out/memory/      InMemoryUserStore
  adapters/out/             UuidUserIdGenerator
  config/                  Composición Spring; almacén habilitado solo con perfil local
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

Un futuro adaptador persistente debe implementar el contrato de `UserTransactions`:

1. Ambos repositorios comparten transacción con commit atómico y rollback completo.
2. Restricción única de almacenamiento sobre `(issuer, subject)`, independiente del
   proveedor; ID de usuario único y referencia íntegra a su propietario.
3. Aislamiento serializable o bloqueos equivalentes para coordinar alta, vínculo,
   actualización y desactivación; comparación de versión/agregado para escrituras.
4. Reintentos acotados de la unidad completa ante carreras de unicidad/serialización,
   resolviendo un alta concurrente al usuario ganador sin cuentas huérfanas.
5. Autorización consumible coordinada con la misma unidad, sin pérdida de concesiones
   por un rollback de cuenta/identidad.

**No se afirma ninguna garantía de persistencia en producción** y no se conecta Azure
SQL, Cosmos DB ni otro almacenamiento. La selección de persistencia sigue pendiente.

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
