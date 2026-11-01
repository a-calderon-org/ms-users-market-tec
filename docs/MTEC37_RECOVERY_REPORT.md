# MTEC-37 — Informe de recuperación

Fecha: 2026-11-01. **REBUILT — READY FOR RE-QA**.
La implementación original no se recuperó íntegra. Se reconstruyó desde la base
verificada y fragmentos del historial. Este dictamen no equivale a QA PASS.

| Elemento | Resultado |
|---|---|
| Worktree original encontrado | No: `/private/tmp/markettec-mtec37-worktree` está ausente; Git conserva registro e índice de la base. |
| Historial de Codex disponible | Subfase 3 legible; preparación y QA conservadas parcialmente en registros guardian con diffs truncados. Sesiones principales no disponibles. |
| Copia de seguridad disponible | No se encontró copia íntegra de los archivos MTEC-37 en ubicaciones revisadas. Se preservó la evidencia parcial en una carpeta persistente privada. |
| Archivos recuperados | Ningún archivo original MTEC-37 completo y verificable. Los archivos heredados de `origin/main` sí proceden exactamente de Git. |
| Archivos reconstruidos | CI, probes, logs, perfil SQL/MI, regresiones, inventario/verificador y documentación; detalle de procedencia abajo. |
| Archivos no recuperables | Versiones exactas de los archivos originales sin commit, informes originales completos, artefactos/JAR y reportes XML históricos. |
| Nueva ubicación persistente | `~/Developer/MarketTec/worktrees/ms-users-mtec37` |
| Nueva rama | `recovery/mtec37-appservice-readiness`; worktree bloqueado contra prune accidental. |
| SHA base | `650411bdf0a5b9375fbe6911595f3150d79c633d`, coincide con `origin/main` y consulta remota de `refs/heads/main`. |
| Pruebas ejecutadas | Java 21.0.13+9, Maven 3.9.11; `clean verify`: 130 casos, 0 fallos, 0 errores, 0 omitidos. Resultados nuevos del candidato. |
| ArchUnit | Cuatro reglas ejecutadas y aprobadas, incluidas en los 130 casos. |
| SQL remoto | 34 métodos pendientes; suite compilada con perfil `sql-integration`. Preflight estricto rechaza macOS aarch64: exige Linux x86-64. |
| Diferencias respecto al QA anterior | Código reconstruido por aproximación; no se conserva equivalencia byte a byte ni se hereda su aprobación. Readiness usa disponibilidad Spring, sin consultas SQL periódicas. |
| Estado Git | 13 archivos modificados y 7 nuevos, todos sin staging; HEAD sigue en la base. Sin commit, push, PR, merge ni rebase. |

## Diagnóstico y límites de la búsqueda

Se inspeccionaron worktrees, ramas, estado, remotos, reflog e índice administrativo.
El registro original no se eliminó, reparó ni reutilizó. Un registro administrativo
no contiene los cambios que nunca se incorporaron al índice ni a objetos Git.
No existe evidencia suficiente para atribuir la desaparición a una persona, proceso
o causa concreta. El uso de almacenamiento temporal es una vulnerabilidad de
preservación; no demuestra por sí solo qué eliminó los archivos.

Se realizaron búsquedas limitadas con Spotlight, find y rg en el árbol del proyecto,
Documents, Downloads, temporales relevantes, visualizaciones, historiales de VS Code
y Cursor, y sesiones activas/archivadas de Codex. No se encontraron los documentos
originales completos ni un directorio trasladado. Time Machine no enumeró snapshots
locales de `/`. Esto no descarta respaldos externos o ubicaciones no accesibles.
No se ejecutó `worktree repair`, porque no se encontró una carpeta movida.

Se consultaron chats de Codex con herramientas de lectura y se verificó la ausencia
de las dos sesiones principales también en los registros locales disponibles.
Las búsquedas de SQLite fueron de solo lectura. Los registros guardian contienen
fragmentos de conversaciones/comandos/resultados, no una copia exacta de los archivos.
No se ejecutaron comandos históricos recuperados sin revisión.

## Evidencia preservada

Carpeta privada persistente: `~/Developer/MarketTec/recovery/mtec37-20261101/`.
Las tres transcripciones se copiaron con permisos privados y SHA-256 verificado;
`manifest.json` registra origen, destino, tamaño y hash. No se incorporan al repositorio
ni se publican, ya que pueden contener contexto sensible.

| Sesión | Identificador y evidencia disponible |
|---|---|
| Preparación original | Sesión principal ausente; fragmento auxiliar conservado en el respaldo privado. |
| QA original | Sesión principal ausente; fragmento auxiliar conservado en el respaldo privado. |
| Intento anterior de subfase 3 | Registro legible y copiado; documenta carpeta ausente y falta de publicación. |

Las referencias P y Q de la tabla siguiente son números de llamadas guardados en los
fragmentos de preparación y QA respectivamente. Un fragmento completo de una función
no permite certificar el archivo entero. Todos los archivos MTEC-37 se clasifican como
**reconstruidos**, aunque reutilicen porciones textuales del historial.

## Procedencia por archivo

Los paths se expresan respecto de la raíz del nuevo worktree.

| Archivo | Procedencia y clasificación |
|---|---|
| `.github/workflows/qa-mtec35-sql.yml` | Base Git + P29 y Q31/Q44; triggers y CI reconstruidos. Conserva suite SQL nativa, acciones fijadas y permisos mínimos de la base. |
| `.env.example` | Base Git + P29; documenta perfil MI y puerto interno. |
| `pom.xml` | Base Git; dependencia Actuator recreada según requisitos y evidencia de probes. |
| `README.md` | Base Git + Q44 y notas actuales; adición explícita, sin sustituir evidencia histórica MTEC-35. |
| `scripts/mtec35-test-inventory.json` | Base Git + P40/P50/Q44; 130 casos y nombres críticos reconstruidos. Lista de 34 SQL heredada sin cambios. |
| `scripts/verify-mtec35-reports.py` | Base Git + P40; control por nombres críticos reconstruido. |
| `src/main/java/com/markettec/users/adapters/in/web/ApiExceptionHandler.java` | Base Git + Q33/Q50; errores seguros y logging; manejo 404/405 recreado. |
| `src/main/java/com/markettec/users/adapters/in/web/CorrelationIdFilter.java` | Base Git + P29/Q31 parciales; normalización, patrón de ruta, estado HTTP y aislamiento/restauración del mapa MDC recreados. |
| `src/main/java/com/markettec/users/adapters/out/sql/AzureSqlConnection.java` | Base Git + P29; modo MI explícito y propiedades TLS conservadas. |
| `src/main/java/com/markettec/users/config/LocalUserConfig.java` | Base Git; exclusión de perfil App Service recreada para impedir fallback. |
| `src/main/java/com/markettec/users/config/SqlUserConfig.java` | Base Git + P29/Q31; selección de perfil y validación no vacía recreadas. |
| `src/main/java/com/markettec/users/config/SafeJsonLogFormatter.java` | Q31/Q50 parciales; formatter de lista permitida reconstruido con API Boot 3.5. |
| `src/main/resources/application.yml` | Base Git + P29/Q31; listener loopback, probes y formatter recreados. Desactivación explícita del health SQL añadida para evitar sondeo de Serverless. |
| `src/main/resources/application-sql-appservice.yml` | Requisitos e historial parcial; perfil recreado con destinos sin defaults. |
| `src/test/java/com/markettec/users/config/SqlUserConfigTest.java` | Base Git + P31/P42/Q33/Q39 y nombres Q44; regresiones recreadas. |
| `src/test/java/com/markettec/users/adapters/in/web/InternalHealthTest.java` | P31/P42/Q33 parciales y nombres Q44; pruebas HTTP recreadas. Semántica sin consultas SQL explícita. |
| `src/test/java/com/markettec/users/adapters/in/web/RequestLoggingTest.java` | P31/Q29/Q39/Q50 parciales y nombres Q44; siete regresiones recreadas. |
| `docs/MTEC37_DEPLOYMENT_READINESS.md` | Requisitos, fragmentos Q55 y documentación oficial consultada; reconstruido, no original recuperado. |
| `docs/MTEC37_QA_REVIEW.md` | Documento nuevo de validación de la reconstrucción; indica que la re-QA independiente está pendiente. |
| `docs/MTEC37_RECOVERY_REPORT.md` | Documento nuevo de esta recuperación. |

V1, dominio, casos de uso y persistencia transaccional MTEC-35 se conservan desde la
base. No se reemplazó ninguna implementación original encontrada íntegra, porque no
se halló una. No se garantiza reproducción exacta de los detalles truncados.

## Validación y diferencias materiales

`java -version`, `./mvnw --version` y `./mvnw --batch-mode clean verify` se ejecutaron
con Temurin 21.0.13+9, descargado de Adoptium y verificado por SHA-256 en una ruta
persistente bajo `~/Developer/MarketTec/tools/`. No se instaló globalmente ni se depende
del JDK temporal anterior, también ausente.

`verify-mdc-final.log` acredita la compilación, 130 casos y empaquetado. El verificador
reconcilió los XML reales con `--normal-only`. El JAR se ejecutó en loopback con perfil
local y heap máximo 256 MiB; se verificaron health, correlación, probes internos, rutas
no publicadas, JSON y bytecode Java 21. Dos arranques independientes con SQL ausente y
vacío fallaron de forma explícita antes de cualquier conexión remota.

La compilación `-Psql-integration test-compile` pasó. El preflight SQL falló
explícitamente por macOS ARM64, sin conectarse a Azure. Las 12 comprobaciones del
verificador usan fixtures sintéticos separados, incluidos casos omitidos, sustituidos
y ausentes; **no constituyen evidencia de SQL ejecutado**.

Respecto al QA histórico: el recuento 130 vuelve a coincidir, pero la implementación
y las aserciones se recrearon. Readiness se basa en `readinessState`, no en una consulta
de base de datos; 503 proviene de REFUSING_TRAFFIC y la caída SQL posterior al arranque
no cambia automáticamente ese estado. El filtro restaura todo MDC y el formatter
omite texto libre incluso en diagnósticos de arranque. Estas decisiones necesitan
auditoría independiente. Los documentos originales y resultados históricos no se
presentan como recuperados ni como aprobación vigente.

## Preservación y restricciones

El checkout MTEC-34 conserva HEAD `43374633a5bc7d230aef3e8b8f6f389ec8e0a913` y su
workflow untracked `.github/workflows/ci-ms-users.yml`, SHA-256
`4daca123c41dd33f2fde92a6618beffec643c9c7f507d68de7cafdc4a1a88cec`.
Ese archivo solo se leyó y no se movió, modificó ni borró.
Los tres registros antiguos de worktrees se conservaron; ninguna rama ya ocupada se reutilizó.

No se hicieron staging, commits, push, PR, merge, rebase, prune, gc, clean, reset hard
ni reparación genérica de worktree. No se modificó Azure, firewall, identidades,
permisos, cuotas, facturación ni Jira; no se ejecutó Flyway ni SQL contra Azure.
Los clean de Maven se limitaron a artefactos generados en el worktree nuevo.

La evidencia se preserva fuera de `/tmp`. El candidato fuente se conserva además en
`rebuilt-candidate-source-verified.tar.gz` con un manifiesto de hashes en la misma carpeta privada,
sin transcripciones, `.git`, secretos ni `target`; los resultados se guardan separadamente. Estos respaldos
son locales, no garantizan recuperación ante pérdida del disco y no sustituyen una
publicación futura autorizada.

## Reanudar subfase 2

1. Abrir el worktree persistente y verificar rama, base, diff completo, archivos nuevos
   y procedencia. La rama original y su registro ausente permanecen intactos.
2. Auditar independientemente logs/MDC, respuestas HTTP, semántica de probes,
   selección MI/TLS, ausencia de fallback, CI y documentación de costos/red/migraciones.
3. Repetir Java/Maven/clean verify y reconciliar XML reales, cuatro ArchUnit y smoke
   del JAR. Usar el JDK persistente documentado en README.
4. Clasificar los 34 SQL como pendientes hasta ejecutarlos en Linux x86-64 con Docker.
   CI remoto requiere una autorización posterior de publicación; esta tarea no la concede.
5. Emitir un nuevo dictamen QA ligado al candidato auditado, sin heredar QA PASS histórico.

**REBUILT — READY FOR RE-QA. Subfase 2 pendiente de auditoría independiente;
SQL remoto, publicación, infraestructura y despliegue pendientes.**

Nota editorial de subfase 3: se omitieron rutas personales e identificadores privados
de sesión para publicación. Los originales íntegros permanecen en el respaldo local;
los resultados históricos anteriores no se han actualizado ni reutilizado como QA nuevo.
