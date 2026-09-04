# ImpPlan 1.3.0 — `data seed` Phase P1 (deterministischer Generator-Kern)

> **Status:** ERLEDIGT + graduiert nach `done/` (2026-09-04, `33c7e606`).
> Alle Arbeitspakete AP1–AP6 geliefert und live gegen echtes SQLite
> verifiziert. Siehe „## Closure" am Ende.
> **Vorbedingung:** Umsetzt Phase P1 aus
> [`cli-data-seed.md`](../in-progress/cli-data-seed.md) (dort das
> übergeordnete Vier-Phasen-Scope-Dokument P1–P4). Dieses ImpPlan konkretisiert **nur
> P1** bis auf Datei- und Kriterien-Ebene, damit es reviewbar ist und
> gegen die Akzeptanzkriterien unten verifiziert werden kann.
> **Review-Nachzug (2026-09-04):** unabhängiger Codebase-Review vor
> Implementierungsstart fand zwei bisher unadressierte Lücken (Geometry-
> Generierung braucht WKB, das der Repo nirgends erzeugt; Wertbindung
> muss `TargetColumn.jdbcType`/`sqlTypeName` einbeziehen, nicht nur
> `NeutralType`) — beide unten in AE-9/AE-10/AE-11 aufgelöst, siehe auch
> die erweiterten Akzeptanzkriterien.

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
  genutzt von `TransferPreflightPlanner`/`ImportLayerPlanner`). **Korrektur
  (Umsetzung):** `SchemaFkEdges` liegt in `hexagon:application`, das von
  `hexagon:core` aus nicht erreichbar ist (Abhängigkeitsrichtung nur
  application→core) — `TableRowSeeder` baut die Kanten deshalb lokal
  selbst (wenige Zeilen, siehe Closure).
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

**AE-9 — Wertbindung nach `NeutralType` UND `TargetColumn`-Hint.**
`DataChunk.rows` sind Java-native Werte, die die jeweilige
`DataWriter`-Implementierung per `value::class` zur Laufzeit bindet —
`NeutralType` bestimmt die *Werte-Semantik*, `TargetColumn.jdbcType`/
`sqlTypeName` (aus `targetColumns`, ohnehin schon für AE-3 gelesen)
bestimmt die *Bindungsform*. Konkret für P1: `Uuid` erzeugt
`java.util.UUID`-Objekte (nicht `String`) — bindet über denselben
`else -> stmt.setObject(...)`-Fallback, den der bestehende
`data import`-Pfad für UUIDs bereits nutzt (`TypeConverters.kt`
`OtherTypeConverter`), kein neues Risiko. `Enum`/`Json`/`Xml` binden als
einfacher `String` (bestehender Pfad packt sie selbst ins `PGobject`,
siehe `PostgresTableImportSession.bindValue`) — unproblematisch.

**AE-10 — Geometry und FullText: keine Werte-Generierung in P1.** Im
Repo existiert kein WKB-*Encoder* (nur Lesen von `ST_AsBinary` aus
Quell-DBs); alle drei `DataWriter`-Implementierungen verlangen für
Geometriespalten rohes WKB als `ByteArray`. Ein Encoder ist eigener
Aufwand, kein CLI-Wiring-Detail. P1-Verhalten (analog zum FK-Zyklus-
Preflight in AE-4, kein stilles Weglassen): Ziel-Spalte vom Typ
`Geometry` oder `FullText` → nullable: `null`; sonst Preflight-Fehler
Exit 3 mit benanntem Grund ("Geometry-/FullText-Generierung wird in P1
nicht unterstützt"). `FullText` (`tsvector`) ist ohnehin i. d. R.
trigger-populiert, selten `required`. Ein WKB-Encoder ist ein mögliches
Folge-Ticket ([`carveout.md`](../in-progress/carveout.md)), kein P1-Blocker.

**AE-11 — `Array`: Element-Typ-String auf internen `NeutralType`
mappen.** `NeutralType.Array.elementType` ist ein roher, gegen
`SchemaValidator.ARRAY_ELEMENT_TYPE_NAMES` validierter String (kein
`NeutralType`). `ColumnValueGenerator` übersetzt ihn selbst auf einen
internen `NeutralType` (analog zum Präzedenzmuster
`CanonicalValueCodec.elementTypeToNeutral()` im `formats`-Adapter, hier
aber lokal in `hexagon/core` nachgebaut, da `core` nicht von `formats`
abhängen darf) und erzeugt 1–3 Elementwerte rekursiv über denselben
Generator. Bindung als `List<*>` (Postgres-Writer erwartet das für
Array-Spalten), Elementtyp-Hint kommt vom Writer selbst aus
`targetColumn.sqlTypeName` (AE-9) — der generierte `List<*>`-Wert muss
also nur inhaltlich zum validierten `elementType` passen, nicht die
Bindungsform selbst festlegen.

**AE-12 — Generator-Aufteilung gegen Detekt-Komplexität.**
`ColumnValueGenerator` deckt nicht alle 21 Zweige in einem `when` ab,
sondern folgt dem im Repo etablierten Split-Muster
(`PostgresTypeMapper`: parametrische Typen vs. literale Typen getrennt,
Kommentar dort erklärt die Begründung) — sonst reales Risiko für
`CyclomaticComplexMethod`/`LongMethod` (Detekt-Schwellen in
`detekt.yml`).

## Neue Dateien

Alle Pfade unten wurden geliefert (Stand siehe Closure).

- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/ColumnValueGenerator.kt`
  — `NeutralType` + `Random` + `SeedLocale` → `Any?`, aufgeteilt nach
  AE-12. `Array`-Elementmapping (AE-11) lokal in derselben Datei.
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/SeedLocale.kt`
  — `enum class SeedLocale { EN, DE }` + Wortlisten; `fromFlag(value:
  String): SeedLocale?` (null bei unbekanntem Wert → Runner mapped auf
  Exit 7).
- `hexagon/core/src/main/kotlin/dev/dmigrate/core/seed/TableRowSeeder.kt`
  — orchestriert Zeilengenerierung für ein ganzes Schema in FK-sicherer
  Reihenfolge: `required`/`unique`/`references`/`Enum`-Constraints,
  lokal gebaute FK-Kanten (Korrektur, s. o.), Werte-Pools für
  FK-Konsumenten.
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedRunner.kt`
  — Business-Logik: Schema lesen, `TableRowSeeder` aufrufen, über
  `DataWriter` schreiben, Exit-Code liefern (DI-Stil wie
  `DataImportRunner`).
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedRequest.kt`
  — Options-DTO (schema: Path, target: String?, count: Int, seed: Long?,
  locale: String, cliConfigPath).
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedCommand.kt`
  — Clikt-Schale, analog `DataImportCommand.kt`.
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataSeedWiring.kt`
  — Composition Root (`schemaCodec = YamlSchemaCodec()`, `urlParser`,
  `poolFactory = HikariConnectionPoolFactory::create`, `writerLookup`
  via `DatabaseDriverRegistry`).
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/seed/ColumnValueGeneratorTest.kt`
- `hexagon/core/src/test/kotlin/dev/dmigrate/core/seed/TableRowSeederTest.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataSeedRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliDataSeedSmokeTest.kt`

## Geänderte Dateien

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt:19`
  — `DataSeedCommand()` zur `subcommands(...)`-Liste ergänzt.
- `docs/user/anwenderhandbuch.md` — Abschnitt 3.22 "Testdaten erzeugen
  (Seed)", aufgabenorientiert, inkl. Hinweis, dass `--rules`/
  `--ai-backend` noch nicht verfügbar sind.
- `docs/planning/open/cli-unimplemented-commands.md` — Zeile `data seed`
  entfernt (Befehl jetzt registriert, analog zum `config show`-Präzedenz),
  Ersatz-Hinweis mit Link auf `cli-data-seed.md` + dieses ImpPlan.
- `docs/planning/in-progress/cli-data-seed.md` — P1-Zeile als geliefert
  markiert.

## Phasen

- **AP1 — Wertegenerator-Kern.** `ColumnValueGenerator`, aufgeteilt nach
  AE-12 (parametrisch/literal), für alle 21 `NeutralType`-Zweige +
  `SeedLocale`; `Array`-Elementmapping nach AE-11; `Geometry`/`FullText`
  liefern gemäß AE-10 bewusst keinen Wert (das Preflight-Verhalten dafür
  entsteht erst in AP3, hier nur der Vertrag: Generator liefert für
  diese zwei Typen `null` bzw. signalisiert "nicht generierbar"). `Uuid`
  erzeugt `java.util.UUID` (AE-9). Unit-Tests: Determinismus bei
  gleichem `Random`-Seed, ein Test je Typ-Zweig (inkl. `Array` mit
  mind. zwei Elementtypen), `unique`-Retry-Pfad, `Enum`-Auswahl
  respektiert `values`.
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

- [x] `data seed --schema <path> --target <url>` erzeugt und importiert
  `--count` (Default 100) Zeilen pro Basistabelle des Schemas. Live gegen
  echtes SQLite verifiziert (2 Tabellen, 40 Zeilen).
- [x] Determinismus: zwei Läufe mit identischem `--seed` und identischem
  Schema erzeugen byte-identische generierte Werte (Unit-Test am
  Generator/Seeder/Runner **und** live: zwei unabhängige SQLite-Datenbanken,
  exportierte JSON-Dumps byte-identisch).
- [x] FK-Konsistenz: bei ≥ 2 Tabellen mit FK-Beziehung referenzieren alle
  generierten FK-Werte tatsächlich existierende Werte der Zielspalte
  (Unit-Test + live verifiziert).
- [x] `unique`-Spalten enthalten in der generierten Zeilenmenge keine
  Duplikate; Erschöpfung des Wertebereichs → Exit 5 mit Spalten-/
  Tabellenname in der Meldung.
- [x] `Enum`-Spalten enthalten ausschließlich Werte aus
  `NeutralType.Enum.values`.
- [x] `--locale de`/`--locale en` erzeugen sichtbar unterschiedliche
  Text-/Email-Werte; ein unbekannter Locale-Wert → Exit 7. Live
  verifiziert (`beispiel.de`-Domains, deutsche Wortliste).
- [x] Zielspalte `NOT NULL` ohne Default, die im Quellschema fehlt →
  Exit 3 mit klarer Preflight-Meldung (kein Absturz/Stacktrace).
- [x] Echter Cross-Table-FK-Zyklus: nullable Spalte → `null`, sonst
  Exit 3 mit klarer Meldung (kein stiller Datenverlust, kein Crash).
- [x] `Array`-Spalten (mind. zwei verschiedene `elementType`-Werte)
  werden erfolgreich generiert und importiert (Unit-Test).
- [x] `Geometry`- und `FullText`-Zielspalten: nullable → `null`, sonst
  Preflight-Fehler Exit 3 mit benanntem Grund (AE-10) — kein Crash, kein
  falsch generierter Wert.
- [x] `Uuid`-Spalten binden ohne Fehler (Test verifiziert `java.util.UUID`
  als Laufzeittyp, AE-9).
- [x] CLI-Smoke: `data seed --help`, fehlendes `--schema`/`--target` →
  Clikt-Usage-Fehler; erfolgreicher Lauf gegen SQLite → Exit 0.
- [x] Kein neues `--rules`/`--ai-backend`-Flag in dieser Phase vorhanden
  (No-Carveouts-Check — `DataSeedCommand` hat genau 5 Optionen).
- [x] `docs/user/anwenderhandbuch.md` beschreibt den Befehl
  aufgabenorientiert inkl. Hinweis auf die noch fehlenden Flags.
- [x] `cli-data-seed.md` liegt in `in-progress/` mit aktualisiertem
  Status (P1 als geliefert markiert).
- [x] Tracker in `../open/cli-unimplemented-commands.md`: `data seed`-Zeile
  entfernt (Befehl registriert), Ersatz-Hinweis verlinkt korrekt.
- [x] `make docker-check` grün für `:hexagon:core`,
  `:hexagon:application`, `:adapters:driving:cli` **und** einmal ohne
  `MODULES` (geteilte Registrierungsstelle `DataCommands.kt`).
- [x] `make docs-check` grün; `make solid-suppression-gate` grün vor
  Commit.

## Nicht-Scope

- **Constraint-modellierte (ggf. mehrspaltige) Fremdschlüssel**
  (`ConstraintType.FOREIGN_KEY` + `ConstraintReferenceDefinition`) fließen
  nur in die Tabellen-Reihenfolge ein (für die Topo-Sortierung gebraucht),
  nicht in die Werte-Generierung — nur `column.references` bekommt
  FK-konsistentes Werte-Pool-Sampling. Bei der Umsetzung erkannt (nicht
  im ursprünglichen AE-4 bedacht): eine saubere Behandlung bräuchte
  Mehrspalten-Tupel-Sampling, das für P1 zu viel Zusatzaufwand gewesen
  wäre. Betrifft nur Schemata, die FKs ausschließlich über Constraints
  (nicht über `column.references`) modellieren.
- **WKB-Encoder für `Geometry`-Werte-Generierung** (AE-10) — eigener
  Aufwand; P1 behandelt `Geometry`/`FullText` als Preflight-Grenze
  (nullable → `null`, sonst Exit 3), kein echter Blocker für P1 selbst.
  Folge-Ticket bei konkretem Bedarf.
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

## Closure

**Gelieferte Artefakte** (`33c7e606`): `ColumnValueGenerator`/`SeedLocale`/
`TableRowSeeder` (`hexagon:core`, `dev.dmigrate.core.seed`);
`DataSeedRunner`/`DataSeedRequest` (`hexagon:application`);
`DataSeedCommand`/`DataSeedWiring` (`adapters:driving:cli`);
Registrierung in `DataCommands.kt`. Anwenderhandbuch-Abschnitt 3.22
"Testdaten erzeugen (Seed)"; Tracker-Zeile in
`open/cli-unimplemented-commands.md` entfernt (Befehl registriert);
`cli-data-seed.md` als P1-geliefert markiert — alle drei in einem
separaten Doku-Commit im Anschluss an diesen.

**Design-Delta zur Planung.** Zwei Korrekturen gegenüber AE-4/„Neue
Dateien": (1) `SchemaFkEdges` (`hexagon:application`) ist von
`hexagon:core` aus nicht erreichbar — `TableRowSeeder` baut die
FK-Kanten stattdessen lokal selbst (identische Logik, wenige Zeilen).
(2) Constraint-modellierte, ggf. mehrspaltige Fremdschlüssel
(`ConstraintType.FOREIGN_KEY`) bekommen kein Werte-Pool-Sampling — nur
`column.references` (bei der Umsetzung erkannt, jetzt in „Nicht-Scope"
dokumentiert statt stillschweigend anders zu generieren). Beide waren
im ursprünglichen ImpPlan nicht bedacht; keins davon blockierte P1.

**Review vor Implementierungsstart** fand zwei blockierende Lücken
(Geometry-WKB, Wertbindung nach `TargetColumn`-Hint statt nur
`NeutralType`) — aufgelöst in AE-9/AE-10/AE-11 (siehe oben), vor jedem
Code-Commit.

**Tests.** `ColumnValueGeneratorTest` (Determinismus, alle 21
`NeutralType`-Zweige inkl. `Array`/`Enum`/`Geometry`/`FullText`-
Exceptions), `TableRowSeederTest` (FK-Konsistenz, Unique-Erschöpfung,
FK-Zyklus, Preflight), `DataSeedRunnerTest` (jeder Exit-Code-Pfad mit
Fakes, analog `DataImportRunnerCallbackTest`), `CliDataSeedSmokeTest`
(Clikt-Parse-Pfad). Alle drei Module (`:hexagon:core`,
`:hexagon:application`, `:adapters:driving:cli`) grün inkl. Detekt und
Kover-Schwelle; einmal `make docker-check` ohne `MODULES` grün.

**Live.** Schema mit zwei Tabellen (FK, `unique`, `enum`) gegen echtes
SQLite: `data seed --count 20 --seed 42` erzeugte 40 FK-konsistente,
eindeutige Zeilen; ein zweiter, unabhängiger Lauf mit demselben `--seed`
erzeugte einen byte-identischen `data export`-Dump (Determinismus
end-to-end, nicht nur in Unit-Tests); `--locale de` erzeugte sichtbar
deutsche Werte (`beispiel.de`-Domains).

**Folgearbeit** (kein P1-Blocker): P2 (`--rules`), P3 (`--ai-backend`),
WKB-Encoder für `Geometry` (bei Bedarf), Mehrspalten-Constraint-FK-
Werte-Konsistenz (bei Bedarf) — siehe „Nicht-Scope".

## Referenzen

- [`cli-data-seed.md`](../in-progress/cli-data-seed.md) — übergeordnetes
  Vier-Phasen-Scope-Dokument (P1–P4).
- [`spec/cli-spec.md` §6.2](../../../spec/cli-spec.md) — Befehls-Zielbild.
- [`LF-024`](../../../spec/lastenheft-d-migrate.md#lf-024) — Requirement.
- [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008)
  — Ursprung der wiederverwendeten FK-Topo-Infrastruktur.
- [`../open/cli-unimplemented-commands.md`](../open/cli-unimplemented-commands.md) — Tracker-Eintrag.
