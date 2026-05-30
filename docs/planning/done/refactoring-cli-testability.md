# Refactoring: CLI-Command-Testbarkeit

> **Status**: Strukturell abgeschlossen 2026-05-30
> **Prioritaet**: Mittel
> **Erstellt**: 2026-04-15
> **Aktualisiert**: 2026-05-30 (alle Commands der „Betroffene Commands"-Liste umgesetzt)

## Stand 2026-05-30

Alle in „Betroffene Commands" gelisteten Commands tragen jetzt das
Drei-Schicht-Modell `Command (Clikt) → Wiring (Clikt-frei, internal) →
Runner (Logik)`. `McpServeCommand` bleibt der Praezedenzfall fuer die
komplexere Reihenfolge `Command → Runner → Wiring → Factory-Port`:

| Command | Wiring-Datei | Commit |
| --- | --- | --- |
| `McpServeCommand` | `McpServeWiring.kt` | (vor dieser Tranche) |
| `DataExportCommand` | `DataExportWiring.kt` | `4476acca` |
| `DataImportCommand` | `DataImportWiring.kt` | `bc092096` |
| `DataProfileCommand` | `DataProfileWiring.kt` | `bc092096` |
| `DataTransferCommand` | `DataTransferWiring.kt` | `741f6ad7` |
| `SchemaReverseCommand` | `SchemaReverseWiring.kt` | `acae00f0` |
| `SchemaCompareCommand` | `SchemaCompareWiring.kt` | `acae00f0` |
| `SchemaGenerateCommand` | `SchemaGenerateWiring.kt` | `acae00f0` |
| `ExportFlyway/Liquibase/Django/Knex` | `ToolExportWiring.kt` (shared) | `2541b48a` |

Nachlauf im Rahmen der Quality-Coverage-Auditierung:

| Command | Wiring-Datei | Grund |
| --- | --- | --- |
| `SchemaValidateCommand` | `SchemaValidateWiring.kt` | CLI-Shell von Validierungs-/Formatierungslogik getrennt |
| `SchemaRollbackCommand` | `SchemaRollbackWiring.kt` | DB-Loader/Hikari-Wiring aus `run()` entfernt |
| `SchemaMigrateCommand` | `SchemaMigrateWiring.kt` | Overlay-/Routine-/Runner-Wiring aus `run()` entfernt |

Vorarbeit:
- `SchemaCodec`-Port (war vor der Tranche vorhanden).
- `DataImportSchemaPreflight` von `adapters/driving/cli` nach
  `hexagon:application` migriert (`88c813f6`); Hex-Boundary zu
  `YamlSchemaCodec` damit geschlossen.

Lessons-Learned-Pruefung (gegen §2–§7 unten):
- §2 Internal-Sichtbarkeit: alle `*Options` + `*Wiring` Typen der
  Tabelle und der Nachlauf-Tranche sind `internal`.
- §3 Exit-Code statt `ProgramResult`: jedes Wiring liefert `Int`,
  die Commands wandeln `Int != 0` in `throw ProgramResult(exit)`.
- §6 Hidden-Adapter-Trap: ueber `DataImportSchemaPreflight`-Klassen-
  Variante mit injiziertem `SchemaCodec` geschlossen.
- §7 UseRequire-Trigger: drei `throw IllegalArgumentException(...)`-
  Stellen in DataExport/Import/Profile-Wirings sind Re-throws im
  `catch` (vom Detekt-Regelumfang nicht erfasst).

## Folge-Tranche: §11-style Coverage fuer die sechs eager-konstruierten Wirings

Die sechs Wirings `DataImport`, `DataProfile`, `ToolExport`,
`SchemaCompare`, `SchemaGenerate`, `SchemaReverse` konstruieren
Hikari-Pools + Adapter eager im `execute()`. Sie sind via `internal`
sichtbar, aber nicht ohne lebende JDBC-Endpunkte unit-testbar.

Bereits geschrieben:
- `DataExportWiringTest` + `DataTransferWiringTest` decken die
  Pre-Runner-Pfade (blank filter, unparseable filter → Exit 2)
  vollstaendig ab, weil beide Wirings vor jeder JDBC-Konstruktion
  zurueckkehren.
- `DataProfileWiring` nutzt seit der Folge-Tranche einen injizierbaren
  Factory-Port (`wiring: add data profile factory port`).
- `ToolExportWiring` nutzt seit der Folge-Tranche einen injizierbaren
  Factory-Port (`wiring: add tool export factory port`).
- `SchemaReverseWiring` nutzt seit der Folge-Tranche einen injizierbaren
  Factory-Port (`wiring: add schema reverse factory port`).
- `SchemaCompareWiring` nutzt seit der Folge-Tranche einen injizierbaren
  Factory-Port (`wiring: add schema compare factory port`).
- `SchemaGenerateWiring` nutzt seit der Folge-Tranche einen injizierbaren
  Factory-Port (`wiring: add schema generate factory port`).
- `DataImportWiring` nutzt seit der Folge-Tranche einen injizierbaren
  Factory-Port (`wiring: add data import factory port`).

Abgeschlossen (eigene Tranche):
- Factory-Port-Schnitt fuer die sechs eager-konstruierten Wirings analog
  zu McpServeWiring / `ServerStateFactory`: Hikari- + Adapter-Lookup
  liegen hinter injizierbaren Factories; Default-Impl bleibt im Wiring,
  Tests substituieren in-memory / Fake-Pool-Varianten.
- Erwartungs-Coverage: 75–88% pro Wiring, analog §11-Tabelle.
- Abschluss-Nachweis: `make docker-coverage-gate`.

Offen ausserhalb dieser Tranche:
- `DataExportWiring` / `DataTransferWiring` Hikari-Anteil; die
  Pre-Runner-Pfade sind gepinnt, Pool-/Adapter-Konstruktion ist ein
  separater Folge-Slice.

---

## Problem

Die Clikt-Command-Klassen (`DataProfileCommand`, `ExportFlywayCommand`,
`SchemaReverseCommand` etc.) enthalten in `run()` Wiring-Code, der:

- `NamedConnectionResolver` instanziiert
- `ConnectionUrlParser.parse()` aufruft
- `HikariConnectionPoolFactory.create()` aufruft
- Tool-spezifische Adapter-Lookup-Lambdas baut
- `OutputFormatter` und `ProfileReportWriter` verdrahtet

Dieser Code ist ohne echtes Clikt-Framework nicht unit-testbar und drueckt
die Modul-Coverage. Kover-Excludes funktionieren nicht zuverlaessig in CI
(Gradle Actions Cache-Interaktion).

## Loesung

Den Wiring-Code aus `run()` in eine testbare Factory extrahieren:

```kotlin
// Heute:
class DataProfileCommand : CliktCommand(...) {
    override fun run() {
        val runner = DataProfileRunner(
            connectionResolver = { NamedConnectionResolver(...).resolve(it) },
            // ... 20 Zeilen Verdrahtung
        )
        val exit = runner.execute(request)
        if (exit != 0) throw ProgramResult(exit)
    }
}

// Ziel:
class DataProfileCommand : CliktCommand(...) {
    override fun run() {
        val exit = DataProfileWiring.execute(source, tables, schema, topN, format, output, root)
        if (exit != 0) throw ProgramResult(exit)
    }
}

// Testbar ohne Clikt:
object DataProfileWiring {
    fun execute(...): Int { ... }
}
```

## Betroffene Commands

- ✅ `McpServeCommand` (umgesetzt 2026-05-09 — siehe **Lessons Learned** unten)
- `DataProfileCommand`
- `DataExportCommand`
- `DataImportCommand`
- `DataTransferCommand`
- `SchemaReverseCommand`
- `SchemaCompareCommand`
- `SchemaGenerateCommand`
- `ExportFlywayCommand` / `ExportLiquibaseCommand` / `ExportDjangoCommand` / `ExportKnexCommand`

---

## Lessons Learned (McpServeCommand → McpServeRunner + McpServeWiring)

Der erste Anwendungsfall war `McpServeCommand` — 530 Zeilen Wiring-Code
inklusive HikariCP, Flyway-Migrations, Async-Executor-Loops und voller
LF-012 / LN-038-Komposition. Folgendes hat sich bewaehrt bzw. als Falle
erwiesen:

### 1. Drei-Schicht-Aufteilung statt zwei

Das urspruengliche Schema "`Command` (Clikt) → `Wiring` (testbar)" hat sich
fuer komplexe Commands als zu grob erwiesen. Saubere Schichtung:

```
Command (Clikt-Bindings, ~150 Zeilen Options + duenner run())
    ↓ delegiert an
Runner (Lifecycle-Orchestrierung: validate, parse, sweep, lock)
    ↓ delegiert an
Wiring (Konstruktion: Phase-C/E/G Komposition, Sweeper-Loops)
    ↓ delegiert an
Factory-Port(s) (Backend-spezifische Adapter — z.B. ServerStateFactory)
```

`McpServeCommand` (jetzt ~210 Zeilen reine Option-Deklarationen) →
`McpServeRunner` → `McpServeWiring` → `ServerStateFactory`. Jede
Schicht hat einen klaren Test-Scope:

- **Runner**: Lifecycle-Logik mit Fakes (Stderr-Sink, Retention-Parser)
- **Wiring**: Komposition gegen Temp-State-Dir (in-memory Branch); JDBC-
  Branch ueber injizierte Fake-Factory
- **Factory**: Default-Impl ist Integrationstest-bound (Postgres), Tests
  ersetzen sie durch in-memory Variante

### 2. Internal-Sichtbarkeit ist Pflicht, nicht Stil

Wenn der extrahierte Runner/Wiring interne Hilfstypen wie
`RetentionPolicy`, `StateDirOwner`, `McpStateDirLock`,
`McpServerStateConfig` durchreicht (was er meistens tut), schlagen
public-Methoden mit
*"public function exposes its internal return type"* fehl. Saubere
Loesung: die ganze Klasse `internal` markieren — nicht jede Methode
einzeln. Tests im selben Modul sehen den `internal`-Scope.

```kotlin
internal data class McpServeOptions(...)
internal class McpServeExit(val code: Int) : RuntimeException()
internal class McpServeRunner(...) { ... }
```

### 3. Exit-Code-Konvention statt ProgramResult durchreichen

`ProgramResult` ist eine Clikt-Exception. Der Runner soll Clikt-frei
bleiben, also: Inneres Frueh-Exit ueber eine eigene
`internal class McpServeExit(val code: Int) : RuntimeException()`. Die
oeffentliche `execute(): Int`-Methode faengt sie und mappt zum
Exit-Code. Der Command-Wrapper konvertiert `Int != 0` zu
`throw ProgramResult(exit)`. Saubere Trennung.

### 4. NamedConnectionResolver in Tests durch Lambda ersetzen

Die `*Runner`-Klassen in `hexagon:application` nehmen einen
`(String, Path?) -> String`-Resolver. Tests im CLI-Modul nutzten
`NamedConnectionResolver(...).resolve(...)` als Convenience —
beim Verschieben der Tests nach `hexagon:application` wuerde das
einen Application→CLI-Reverse-Dep im Test-Klassenpfad erzeugen.
Lambda-Stub reicht:

```kotlin
fun isolatedSourceResolver(source: String, configPath: Path?): String {
    require(source.isNotBlank()) { "--source must not be blank" }
    if ("://" in source) return source
    throw IllegalArgumentException("Connection name '$source' not resolvable in test context")
}
```

`DataExportRunner` faengt jede Exception aus dem Resolver und mappt
auf Exit-7 mit der Exception-Message als stderr. Die `require()`-
Variante triggert Detekt's `UseRequire` nicht (siehe Punkt 7).

### 5. Same-Package-Helper-Migration

`SchemaGenerateHelpers` (CLI) wurde mit verschoben, weil
`SchemaGenerateRunner` (in `hexagon:application`) sie als Function-
References konsumiert. Trivial weil keine Adapter-Abhaengigkeit:
nur `core.model` + `driver`-Ports, beide bereits in
`hexagon:application` sichtbar. `internal object` → `object`
(public) damit `SchemaGenerateCommand` (CLI) den Helper weiter
importieren kann.

### 6. Hidden-Adapter-Trap (DataImportSchemaPreflight)

**Nicht jeder Helper laesst sich umziehen.** `DataImportSchemaPreflight`
ist Application-Layer-Logik, importiert aber `YamlSchemaCodec` aus
`adapters/driven/formats`. Das verschieben wuerde
`hexagon:application` an die `formats`-Implementation binden — Hex-
Boundary-Verletzung. **Voraussetzung fuer Migration**: erst einen
`SchemaCodec`-Port in `hexagon:ports`, dann `YamlSchemaCodec` als
Adapter-Impl, dann den Helper migrieren. Aktuell stehen 4 von 7
DataImportRunner-Tests im CLI-Modul, weil sie diesen Helper
indirekt brauchen.

> Folgearbeit: `SchemaCodec`-Port + Migration `DataImportSchemaPreflight`
> nach `hexagon:application`. Schliesst die letzten 4 DataImport-Tests
> ein.

### 7. UseRequire-Detekt-Trigger

```kotlin
// Schlaegt mit Detekt UseRequire fehl:
if (x == null) throw IllegalArgumentException("...")

// Korrekt:
require(x != null) { "..." }
```

Beim Schreiben von Test-Helpern auffallen lassen, weil das
Coverage-Target in der CI sonst rot wird.

### 8. Test-Implementation-Deps mit-migrieren

Verschieben von Runner-Tests nach `hexagon:application` braucht in
`hexagon/application/build.gradle.kts`:

```kotlin
testImplementation(project(":adapters:driven:formats"))
testImplementation(project(":adapters:driven:driver-common"))
testImplementation(project(":adapters:driven:streaming"))
```

`integrations` (testImplementation) liefert nur
`hexagon:ports` per `api`, nicht die anderen Adapter — die mussten
einzeln dazu.

### 9. HikariDataSource ist eager — nicht unit-testbar

```kotlin
fun createServerStateDataSource(state: McpServerStateConfig): HikariDataSource {
    val cfg = HikariConfig().apply { jdbcUrl = state.jdbcUrl; ... }
    return HikariDataSource(cfg)  // <- versucht sofort zu connecten
}
```

Default `initializationFailTimeout=1ms` heisst: bei Konstruktion
wird ein Connect probiert. Ohne erreichbaren JDBC-Endpunkt fliegt
`SQLException`. Saubere Loesung: Konstruktion in `DefaultServerStateFactory`
verbergen, Tests substituieren die ganze Factory mit Fake-Bundle
(siehe Factory-Port).

### 10. Backend-Wechsel als Factory-Port designen

Der JDBC-Block (Hikari → Flyway → JdbcTransactionRunner → 5 Stores)
laesst sich *nicht* sinnvoll mit Mockk pro Klasse mocken — die
Inter-Dependencies ergeben Boilerplate-Lawinen. Stattdessen:

```kotlin
internal data class ServerStateBundle(
    val phaseCWithPersistence: McpRuntimeWiring,
    val idempotencyStore: IdempotencyStore,
    val jobStartTransaction: JobStartTransaction,
    val quotaReservationOwnerStore: QuotaReservationOwnerStore,
    val ownerAwareQuotaService: OwnerAwareQuotaService,
    val cleanup: AutoCloseable,
)

internal fun interface ServerStateFactory {
    fun build(state: McpServerStateConfig, phaseC: McpRuntimeWiring): ServerStateBundle
}
```

Default-Impl ist Hikari/Postgres, Tests injizieren Fakes. Doppelter
Gewinn: ermoeglicht spaeter eine SQLite-in-Memory-Variante als
echten Backend-Adapter, ohne `McpServeWiring` selbst zu aendern.

### 11. Coverage-Hebel pro Schicht

Modul-isolierte Coverage von `adapters:driving:cli` nach dem Refactor:

| Klasse | Vor Refactor | Nach Refactor |
| --- | --- | --- |
| `McpServeCommand` | 62% | gedeckt durch CliHelpAndBootstrapTest (Shell) |
| `McpServeRunner` | n/a | 75% (private startStdio/startHttp callen `McpServerBootstrap`) |
| `McpServeWiring` | n/a | 88% (in-memory + persistent Branch via Fake-Factory) |
| `DefaultServerStateFactory` | n/a | ~5% (nur Integrationstests) |

Der unvermeidliche Rest (`startStdio`, `startHttp`,
`DefaultServerStateFactory`) ist sauber isoliert und gehoert in
Integrationstests, nicht in Unit-Tests.

---

## Empfohlene Reihenfolge

1. **`SchemaCodec`-Port** als Voraussetzung fuer `DataImportSchemaPreflight`-
   Migration (Sub-Refactor).
2. **`DataExportCommand` / `DataImportCommand` / `DataProfileCommand`** —
   gleiche Struktur wie `McpServeCommand`, aber kleiner. Direkt
   `Runner`-Schicht extrahieren; ein `Wiring` lohnt sich nur, wenn das
   `run()` ueber 100 Zeilen wachst.
3. **Tool-Export-Commands** (`ExportFlyway/Liquibase/Django/Knex`) —
   die teilen sich `ToolExportRunner`. `ToolExportCommand` als
   Common-Shell extrahieren, dann pro-Tool nur duenne Wrapper.
4. **`SchemaCompareCommand`, `SchemaGenerateCommand`,
   `SchemaReverseCommand`** — Compare-Renderer und SchemaCompareHelpers
   sind separat zu testen (sind heute schon gut isoliert).

## Abgrenzung

Dieses Refactoring ist kein Teil von Milestone 0.7.5. Der hier
abgeschlossene Scope betrifft die oben gelisteten Commands plus die
Quality-Coverage-Nacharbeit fuer `SchemaMigrateCommand`,
`SchemaRollbackCommand` und `SchemaValidateCommand`. Die anschliessende
Coverage-Folgearbeit fuer eager konstruierte Wirings ist als
Factory-Port-Tranche unter
`docs/planning/done/wiring-factory-port-coverage.md` abgeschlossen.
