# ImpPlan 1.3.0 — `data seed` Phase P1 (deterministischer Generator-Kern)

> **Status:** Draft, bereit zur Umsetzung (2026-09-04). Kein
> Implementierungs-Commit existiert bisher — bleibt deshalb in `next/`
> (Konvention laut `../in-progress/README.md`: „Scope steht, aber kein
> Implementierungs-Commit existiert" gehört nicht nach `in-progress/`).
> Wandert zusammen mit [`cli-data-seed.md`](cli-data-seed.md) nach
> `../in-progress/`, sobald der erste Code-Commit landet.
> **Vorbedingung:** Umsetzt Phase P1 aus
> [`cli-data-seed.md`](cli-data-seed.md) (dort das übergeordnete
> Vier-Phasen-Scope-Dokument P1–P4). Dieses ImpPlan konkretisiert **nur
> P1** bis auf Datei- und Kriterien-Ebene, damit es reviewbar ist und
> gegen die Akzeptanzkriterien unten verifiziert werden kann.

## Kontext / Ist-Stand (verifiziert)

- **CLI-Pattern (Clikt)**: `DataCommand` registriert Subcommands in
  `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt:19`.
  Vorbild-Kette: `DataImportCommand.kt` (Clikt-Schale, Flags via
  `option(...)`) → `DataImportWiring.kt` (Composition Root im CLI-Modul) →
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataImportRunner.kt`
  (reine Business-Logik, DI über Konstruktor, `execute(...): Int` liefert
  den Exit-Code direkt zurück; `DataImportCommand.run()` wirft
  `ProgramResult(exitCode)` wenn `!= 0`).
- **Schema einlesen**: `SchemaCodec.read(path): SchemaDefinition`
  (`hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/SchemaCodec.kt`, Default-Impl
  `YamlSchemaCodec`). `SchemaDefinition.tables: Map<String,
  TableDefinition>`, `TableDefinition.columns: Map<String,
  ColumnDefinition>`.
- **`NeutralType`** (`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/NeutralType.kt`): sealed
  class, 21 Varianten (Identifier, Text(maxLength), Char(length),
  Integer, SmallInt, BigInteger, Float(precision), Decimal(precision,
  scale), BooleanType, DateTime(tz), Date, Time, Uuid, Json, Xml, Binary,
  Email, Enum(values), Array(elementType), Geometry, FullText).
  `ColumnDefinition.type: NeutralType`, außerdem `required: Boolean`,
  `unique: Boolean`, `references: ReferenceDefinition?`.
- **`ReferenceDefinition`** (`hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ReferenceDefinition.kt`):
  `data class ReferenceDefinition(val table: String, val column: String,
  ...)` — FK-Ziel ist direkt (Tabelle, Spalte) benannt, keine
  PK-Auflösung nötig.
- **FK-Topo-Sortierung**
  ([`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008)-Infrastruktur,
  wiederverwendbar):
  `hexagon/core/src/main/kotlin/dev/dmigrate/core/dependency/TableDependencySort.kt` —
  `sortTablesByDependency(tables: Set<String>, edges: List<FkEdge>):
  TableSortResult(sorted, circularEdges)` (Kahn, lineare Ordnung; Zyklen
  ans Ende angehängt, `circularEdges` gefüllt). Kantenbau bereits
  vorhanden als `SchemaFkEdges.of(schema, tables)` (internal object in
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaPartitionSupport.kt:34`,
  genutzt von `TransferPreflightPlanner`/`ImportLayerPlanner`).
- **Schreibpfad** (kein neuer Import-Mechanismus nötig): `DataWriter`
  (`hexagon/ports-write/src/main/kotlin/dev/dmigrate/driver/data/DataWriter.kt`) —
  `openTable(pool, table, options: ImportOptions): TableImportSession`.
  `TableImportSession.targetColumns: List<TargetColumn>` ist die
  autoritative, vom Ziel via JDBC-Metadaten gelesene Spaltenreihenfolge
  (analog `data import`). `write(chunk: DataChunk): WriteResult`,
  `commitChunk()`, `finishTable()`, `close()` (State-Maschine
  OPEN→WRITTEN→OPEN→FINISHED). `DataChunk(table, columns:
  List<ColumnDescriptor>, rows: List<Array<Any?>>, chunkIndex)`.
  Writer-Lookup: `DatabaseDriverRegistry.get(dialect).dataWriter()`.
  Pool: `HikariConnectionPoolFactory.create(ConnectionConfig)`,
  URL-Parsing: `ConnectionUrlParser.parse`.
- **Kein bestehendes Seed/RNG-Muster im Repo** — auch PBT
  (kotest-property) nutzt keinen expliziten `kotlin.random.Random(seed)`.
  `data seed` etabliert dieses Muster neu.
- **Keine bestehende Wert-Generierung pro `NeutralType`** —
  `NeutralTypeArb.kt` (testFixtures) generiert nur Typ-*Metadaten* für
  PBT, keine konkreten Spaltenwerte.
- **Test-Namenskonvention**: Runner-Tests unter
  `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/`,
  CLI-Smoke-Tests unter
  `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/` (Vorbild
  `CliDataImportSmokeTest.kt`: Clikt-Parse → `ProgramResult.statusCode`
  prüfen, SQLite als schnelles Test-Target).
- **Exit-Code-Konvention** (aus `DataImportRunner`/cli-spec §6, analog
  `data transfer`): `2` Argument-/Validierungsfehler, `3` Preflight
  fehlgeschlagen (Inkompatibilität, FK-Zyklen), `4` Verbindungsfehler,
  `5` Schreibfehler, `7` Konfigurationsfehler, `0` Erfolg.

## Scope

Nur **P1** aus `cli-data-seed.md`: `data seed --schema <path> --target
<url> [--count N] [--seed N] [--locale xx]`. **Kein** `--rules`, **kein**
`--ai-backend` in diesem Slice — beide werden erst als eigene Flags
ergänzt, wenn ihre jeweilige Phase (P2/P3) gebaut wird. Begründung:
Projektkonvention **No-Carveouts** verbietet Flags, die angenommen aber
nicht real umgesetzt werden ("kommt in Sub-Slice X"-Stopgaps). Jedes in
diesem Slice ausgelieferte Flag ist vollständig funktionsfähig.

## Architektur-Entscheidungen

**AE-1 — Keine neue Runtime-Dependency.** Handgerollte, deterministische
Wertegeneratoren pro `NeutralType`-Variante (`kotlin.random.Random`),
kein Faker-Port. Vermeidet CVE-/Lizenz-Review einer neuen Abhängigkeit
(Projekt hat aktive Dependency-CVE-Reduktionshistorie);
[`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) nennt Faker als
Vorbild, nicht als Pflicht-Library.

**AE-2 — Direkter `DataWriter`-Aufruf statt `StreamingImporter`.**
`data seed` generiert Zeilen in-memory und schreibt sie direkt über
`DataWriter.openTable(...).write(DataChunk(...))`. Kein Zwischenformat/
Datei-Roundtrip durch die dateibasierte Import-Pipeline — die ist für
Streaming aus Quelldateien gebaut, nicht für In-Memory-Generierung.

**AE-3 — Spaltenreihenfolge/-typen kommen vom Ziel.**
`TableImportSession.targetColumns` ist die Bindungs-Autorität (analog
`data import`). Der Generator erzeugt Werte pro `targetColumn.name`,
nachgeschlagen in `TableDefinition.columns[name]` fürs `NeutralType`.
Eine Zielspalte, die im Quellschema fehlt und im Ziel `NOT NULL` ohne
Default ist → Preflight-Fehler, Exit 3.

**AE-4 — FK-Konsistenz über Werte-Pools.** Tabellen werden in der von
`sortTablesByDependency` gelieferten Reihenfolge befüllt. Für jede
FK-Spalte (`column.references != null`) wird ein Wert aus dem Pool der
bereits generierten Werte der Zielspalte gezogen. Spalten aus
`circularEdges` (echte Zyklen): nullable → `null`, sonst Preflight-Fehler
Exit 3 mit klarer Meldung (kein stiller Crash).

**AE-5 — `--locale`: kleines, aber echtes Set.** Eingebaute Wortlisten
für `en`/`de` (Text-/Email-Generierung). Unbekannte Locale-Werte → Exit 7
statt stillem Fallback (keine stille Degradation).

**AE-6 — `--seed`: optional, `Long`.** Gesetzt → `Random(seed)`,
deterministisch reproduzierbar. Nicht gesetzt → `Random(Random.
nextLong())`, der tatsächlich verwendete Seed wird auf stdout ausgegeben
(`Verwendeter Seed: <n>`), damit ein Lauf im Nachhinein reproduzierbar
bleibt.

**AE-7 — `unique`-Spalten: bounded Retry.** Generator zieht mit
begrenzten Versuchen (50) einen noch nicht verwendeten Wert; bei
Erschöpfung (z. B. `--count` größer als möglicher Wertebereich bei
kleinen Enum-Wertevorräten) → Exit 5 mit Meldung, welche Spalte/Tabelle
betroffen ist.

**AE-8 — Alle Basistabellen, `--count` pro Tabelle.** Default 100 (wie
Spec).

## Neue Dateien

Alle Pfade in diesem Abschnitt sind das **Zielbild dieses ImpPlans** und
existieren vor AP1–AP5 noch nicht (ADR 0011).

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/ColumnValueGenerator.kt` <!-- d-check:ignore (Zielbild: entsteht in AP1; ADR 0011) -->
  — `NeutralType` + `Random` + `SeedLocale` (+ optionaler Werte-Pool für
  FK-Spalten) → `Any?`. `when (type)` über alle 21 `NeutralType`-Zweige.
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/SeedLocale.kt` <!-- d-check:ignore (Zielbild: entsteht in AP1; ADR 0011) -->
  — `enum class SeedLocale { EN, DE }` + Wortlisten; `fromFlag(value:
  String): SeedLocale?` (null bei unbekanntem Wert → Runner mapped auf
  Exit 7).
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/TableRowSeeder.kt` <!-- d-check:ignore (Zielbild: entsteht in AP2; ADR 0011) -->
  — orchestriert Zeilengenerierung für eine Tabelle: `required`/`unique`/
  `references`/`Enum`-Constraints, liefert generierte Zeilen plus die
  Werte-Pools für nachfolgende FK-Konsumenten.
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedRunner.kt` <!-- d-check:ignore (Zielbild: entsteht in AP3; ADR 0011) -->
  — Business-Logik: Schema lesen, FK-Reihenfolge (`SchemaFkEdges` +
  `sortTablesByDependency`), pro Tabelle `TableRowSeeder` aufrufen, über
  `DataWriter` schreiben, Exit-Code liefern (DI-Stil wie
  `DataImportRunner`).
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedRequest.kt` <!-- d-check:ignore (Zielbild: entsteht in AP3; ADR 0011) -->
  — Options-DTO (schema: Path, target: String?, count: Int, seed: Long?,
  locale: String, cliContext, configPath).
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedCommand.kt` <!-- d-check:ignore (Zielbild: entsteht in AP4; ADR 0011) -->
  — Clikt-Schale, analog `DataImportCommand.kt`.
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedWiring.kt` <!-- d-check:ignore (Zielbild: entsteht in AP4; ADR 0011) -->
  — Composition Root (`schemaCodec = YamlSchemaCodec()`, `urlParser`,
  `poolFactory = HikariConnectionPoolFactory::create`, `writerLookup`
  via `DatabaseDriverRegistry`).
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/seed/ColumnValueGeneratorTest.kt` <!-- d-check:ignore (Zielbild: entsteht in AP1; ADR 0011) -->
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataSeedRunnerTest.kt` <!-- d-check:ignore (Zielbild: entsteht in AP5; ADR 0011) -->
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataSeedSmokeTest.kt` <!-- d-check:ignore (Zielbild: entsteht in AP5; ADR 0011) -->

## Geänderte Dateien

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt:19`
  — `DataSeedCommand()` zur `subcommands(...)`-Liste ergänzen.
- `docs/user/anwenderhandbuch.md` — aufgabenorientierter Abschnitt
  ("Brauchen Sie Testdaten für ein Schema → tun Sie ..."), inkl. Hinweis,
  dass `--rules`/`--ai-backend` noch nicht verfügbar sind.
- `docs/planning/next/cli-data-seed.md` → verschoben nach
  `docs/planning/in-progress/cli-data-seed.md` <!-- d-check:ignore (Zielbild: Move beim ersten Code-Commit; ADR 0011) --> (im selben Commit wie
  dieses ImpPlan, sobald der erste Code-Commit landet).
- `docs/planning/open/cli-unimplemented-commands.md` — Zeile `data seed`:
  Basisbefehl jetzt registriert (P1), Link zeigt auf
  `in-progress/cli-data-seed.md`, Hinweis dass `--rules`/`--ai-backend`
  noch fehlen.

## Phasen

- **AP1 — Wertegenerator-Kern.** `ColumnValueGenerator` (alle 21
  `NeutralType`-Zweige) + `SeedLocale`. Unit-Tests: Determinismus bei
  gleichem `Random`-Seed, ein Test je Typ-Zweig, `unique`-Retry-Pfad,
  `Enum`-Auswahl respektiert `values`.
- **AP2 — `TableRowSeeder` + FK-Orchestrierung.** Nutzt `SchemaFkEdges`/
  `sortTablesByDependency`; baut Werte-Pools pro (Tabelle, Spalte) für
  FK-Konsumenten; behandelt `circularEdges` (nullable→null, sonst
  Preflight-Fehler).
- **AP3 — `DataSeedRunner`.** Schema-Read, Preflight (Zielspalten vs.
  Schema, `NOT NULL` ohne Quelle → Exit 3), `DataWriter`-Schreibschleife
  pro Tabelle, Exit-Code-Mapping (2/3/4/5/7/0).
- **AP4 — CLI-Wiring.** `DataSeedCommand` + `DataSeedWiring` +
  Registrierung in `DataCommands.kt`.
- **AP5 — Tests.** `DataSeedRunnerTest` (SQLite-Target, Mehrtabellen-FK,
  Preflight-Fehlerfälle, Determinismus zweier Läufe mit gleichem Seed),
  `CliDataSeedSmokeTest` (Clikt-Parse-Pfad + Exit-Codes).
- **AP6 — Doku.** `anwenderhandbuch.md`, `cli-data-seed.md` +
  `cli-unimplemented-commands.md` Moves/Updates, `make docs-check`.

## Akzeptanzkriterien

- [ ] `data seed --schema <path> --target <url>` erzeugt und importiert
  `--count` (Default 100) Zeilen pro Basistabelle des Schemas.
- [ ] Determinismus: zwei Läufe mit identischem `--seed` und identischem
  Schema erzeugen byte-identische generierte Werte (Test belegt es
  direkt am Generator, nicht nur am Gesamtlauf).
- [ ] FK-Konsistenz: bei ≥ 2 Tabellen mit FK-Beziehung referenzieren alle
  generierten FK-Werte tatsächlich existierende Werte der Zielspalte
  (Test mit mind. zwei abhängigen Tabellen).
- [ ] `unique`-Spalten enthalten in der generierten Zeilenmenge keine
  Duplikate; Erschöpfung des Wertebereichs → Exit 5 mit Spalten-/
  Tabellenname in der Meldung.
- [ ] `Enum`-Spalten enthalten ausschließlich Werte aus
  `NeutralType.Enum.values`.
- [ ] `--locale de`/`--locale en` erzeugen sichtbar unterschiedliche
  Text-/Email-Werte; ein unbekannter Locale-Wert → Exit 7.
- [ ] Zielspalte `NOT NULL` ohne Default, die im Quellschema fehlt →
  Exit 3 mit klarer Preflight-Meldung (kein Absturz/Stacktrace).
- [ ] Echter Cross-Table-FK-Zyklus: nullable Spalte → `null`, sonst
  Exit 3 mit klarer Meldung (kein stiller Datenverlust, kein Crash).
- [ ] CLI-Smoke: `data seed --help`, fehlendes `--schema`/`--target` →
  Clikt-Usage-Fehler; erfolgreicher Lauf gegen SQLite → Exit 0.
- [ ] Kein neues `--rules`/`--ai-backend`-Flag in dieser Phase vorhanden
  (No-Carveouts-Check).
- [ ] `docs/user/anwenderhandbuch.md` beschreibt den Befehl
  aufgabenorientiert inkl. Hinweis auf die noch fehlenden Flags.
- [ ] `docs/planning/next/cli-data-seed.md` liegt (nach dem ersten
  Code-Commit) in `in-progress/` mit aktualisiertem Status; Tracker in
  `open/cli-unimplemented-commands.md` verweist korrekt.
- [ ] `make docker-check` grün für `:hexagon:core`,
  `:hexagon:application`, `:adapters:driving:cli` **und** einmal ohne
  `MODULES` (geteilte Registrierungsstelle `DataCommands.kt`).
- [ ] `make docs-check` grün; `make solid-suppression-gate` grün vor
  Commit.

## Nicht-Scope

- `--rules`-Regeldatei (P2 in `cli-data-seed.md`) — eigenes
  Dateiformat-Design, eigener Slice.
- `--ai-backend` (P3 in `cli-data-seed.md`) — Gate-Niveau-Frage noch
  offen, eigener Slice.
- `--parallel` für `data seed` — P1 ist sequenziell; parallele
  Befüllung wäre eine spätere Erweiterung analog
  [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008),
  nicht Teil dieses Slices.
- Sample-DB-/Multi-Dialekt-E2E-Goldens (ursprünglich P4 in
  `cli-data-seed.md`) — dieser Slice verifiziert gegen SQLite (schnell,
  ausreichend für die Akzeptanzkriterien oben); eine breitere
  Cross-Dialekt-Härtung ist eine mögliche Folgearbeit, kein Blocker für
  P1.

## Verifikation

1. `make docker-check MODULES=":hexagon:core"`
2. `make docker-check MODULES=":hexagon:application"`
3. `make docker-check MODULES=":adapters:driving:cli"`
4. Einmal `make docker-check` **ohne** `MODULES` (geteilte
   Registrierungsstelle geändert).
5. `make docs-check` nach den Doku-/Planning-Änderungen.
6. `make solid-suppression-gate` vor dem Commit.
7. Manueller Smoke-Lauf: gebautes CLI gegen eine Beispiel-Schema-Datei
   mit 2 Tabellen (eine FK-Beziehung, je eine `unique`- und eine
   `Enum`-Spalte) via `data seed --schema ... --target
   sqlite:///tmp/seed-smoke.db --count 20 --seed 42`; Zeilen +
   FK-Konsistenz per Query verifizieren; zweiter Lauf mit demselben
   `--seed` prüft Determinismus.
8. Build-/Test-Output in `/tmp/build.log` umleiten und greppen; Exit-Code
   von `make`-Targets direkt prüfen (nicht via nachgelagertem `echo`).

## Referenzen

- [`cli-data-seed.md`](cli-data-seed.md) — übergeordnetes Vier-Phasen-
  Scope-Dokument (P1–P4).
- [`spec/cli-spec.md` §6.2](../../../spec/cli-spec.md) — Befehls-Zielbild.
- [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) — Requirement.
- [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008)
  — Ursprung der wiederverwendeten FK-Topo-Infrastruktur.
- [`../open/cli-unimplemented-commands.md`](../open/cli-unimplemented-commands.md) — Tracker-Eintrag.
