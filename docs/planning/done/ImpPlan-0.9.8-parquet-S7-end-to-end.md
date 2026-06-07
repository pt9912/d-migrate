# S7 — End-to-End-Integration Seekable-Pfad

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](../in-progress/parquet-productive-cut-a.md)
> §3 S7).
>
> Status: Closed (2026-06-08). Sieben Commits (ImpPlan +
> sechs Sub-Slices):
> - `8c218054` docs(parquet) — ImpPlan-Doc (v1, plus vier
>   Review-Runden gegen Code-Realitaet).
> - `34eea7ce` S7-0 — Export-Wiring (Footer-KV + Bundle-Closure) +
>   `writerFactoryBuilder(ExportOutput)`-Signaturwechsel.
> - `a0dc2c5b` S7a — TableImporter Sealed-Dispatch + Konstruktor-
>   Param `seekableReaderFactory` (Position 3 hinten).
> - `5ff17e6f` S7b — StreamingImporter-Stopgap entfernt + Pre-Stream-
>   Check (defense in depth).
> - `2f9cc38a` S7c — Fake-basierte Seekable-Dispatch-Tests inkl.
>   Resume-Skip-Smoke (Plan-Review-v1 Punkt 8).
> - `a25722e5` S7d — E2E-Fixture Single-File-Roundtrip
>   (PG/Testcontainers, gated durch `-PintegrationTests`).
> - S7e (mit diesem Closeout-Commit): E2E-Fixture Bundle-Roundtrip
>   in derselben Klasse + Plan-Doc-Move + Umbrella-§3.4-Update.
>
> Loest den `is ResolvedTableInput.Seekable -> error("S7 ...")`-Stopgap
> im `StreamingImporter` ab und verdrahtet den `TableImporter` so, dass
> er Seekable-Inputs ueber `SeekableDataChunkReaderFactory.create(...)`
> konsumiert. Aktiviert dabei zwei produktive Export-Lambdas, die in
> S3b/S4 angelegt, aber im CLI-Wiring nie aufgerufen wurden:
> `ParquetChunkWriter.extraMetaDataProvider` (Footer-KV) und
> `StreamingExporter.onBundleClosure` (manifest.yaml-Schreibung).
> Liefert Fake-basierte Dispatch-Tests im Streaming-Modul (parquet-frei)
> und eine E2E-Fixture in `:test:e2e-cli`, die einen echten Parquet-
> Roundtrip Single-File + Bundle gegen PG (Testcontainers, analog
> bestehenden E2E-Familien) faehrt; SQLite-Smoke ist optional. **Kein**
> Checkpoint-Specifics-Eintrag (S8); **keine** Bundle-/Single-File-
> Test-Familien (S9a/S9b — Preflight-Codes, KV-Toleranz, Resume-
> Familien).

---

## 1. Scope

Per Umbrella §3 S7-Cell, plus die im Plan-Review aufgedeckte
Export-Wiring-Vorbedingung:

1. **Export-Wiring fuer Footer-KV und Bundle-Closure** (Plan-Review-v1
   Finding 1, neu in S7) — **output-mode-aware** gemaess S4 §2.2 Invariant
   ("Bundle-Pfade verwenden den Default-Provider; Single-File-Pfade reichen
   den `ParquetSingleFileManifestWriter().provider` durch") und unter
   Verwendung der echten APIs (Plan-Review-v2 Finding 1):
   - `ParquetChunkWriterFactory` bekommt einen neuen Konstruktor-
     Parameter `extraMetaDataProvider: (ChunkSchema) -> Map<String, String> = { emptyMap() }`
     und reicht ihn an den `ParquetChunkWriter` durch (Writer kennt das
     Feld seit S4, `ParquetChunkWriter.kt:51`).
   - `DataExportWiring.kt:104` baut den `writerFactoryBuilder`-Lambda
     **output-mode-aware**: bei `--split-files` (Bundle-Modus) bleibt
     `ParquetChunkWriterFactory()` ohne Provider; im Single-File-Modus
     kommt
     `ParquetChunkWriterFactory(extraMetaDataProvider =
     ParquetSingleFileManifestWriter(producerVersion = VersionInfo.PRODUCT_VERSION).provider)`
     zum Einsatz. Damit behaelt der S4-Vertrag (Bundle-Dateien ohne
     Footer-KV, Single-File-Dateien mit Footer-KV) seine Gueltigkeit.
   - `DataExportWiring.kt` setzt zusaetzlich beim
     `StreamingExporter.export(...)`-Aufruf
     (`StreamingExporter.kt:107` hat den Default `{}` seit S3b) einen
     `onBundleClosure`-Hook mit der korrekten `operator fun invoke`-API:
     ```kotlin
     onBundleClosure = ParquetBundleClosure(
         producerVersion = VersionInfo.PRODUCT_VERSION,
     )
     ```
     `ParquetBundleClosure.invoke(context)` (`ParquetBundleClosure.kt:31`)
     ignoriert Aufrufe fuer Nicht-Parquet-Formate per `if (context.format != PARQUET) return`,
     also kein zusaetzliches CLI-seitiges Format-Gating noetig.
2. **`ResolvedTableInput.Seekable`-Konsum produktiv**: Sealed-`when`-
   Sweep im `TableImporter`/`StreamingImporter`-Loop. Der Stopgap
   `is Seekable -> error("S7 ...")` faellt.
3. **`SeekableDataChunkReaderFactory`**-Dispatch im
   `TableImporter`: bei `is Seekable` ruft der Importer die in
   S6 verdrahtete Factory mit `(format, source, table, schema,
   chunkSize, options)` statt der Stream-`DataChunkReaderFactory`.
4. **Modulgrenze**: `:adapters:driven:streaming` bleibt
   parquet-frei. Die Dispatch-Tests verwenden Fake-Factories;
   echter `ParquetSeekableDataChunkReaderFactory`-Bezug lebt
   ausschliesslich in `:test:e2e-cli` und im bereits in S6
   verdrahteten CLI-Wiring.
5. **E2E-Fixture** (`:test:e2e-cli`): Roundtrip-Tests
   `export → import` fuer Parquet Single-File und Parquet Bundle,
   gating ueber `-PintegrationTests` (Umbrella DoD).

## 2. Lieferumfang

### 2.1 Export-Wiring fuer Footer-KV und Bundle-Closure (`:adapters:driven:formats-parquet` + `:adapters:driving:cli`)

Vorbedingung des E2E-Pfads. Beide Komponenten existieren seit
S3b/S4 mit ihren Default-Pfaden inaktiv; was fehlt, ist
output-mode-aware Wiring.

- **`ParquetChunkWriterFactory`-Konstruktor-Erweiterung**:
  ```kotlin
  class ParquetChunkWriterFactory(
      @Suppress("UnusedPrivateMember")
      private val warningSink: ((ValueSerializer.Warning) -> Unit)? = null,
      private val extraMetaDataProvider: (ChunkSchema) -> Map<String, String> = { emptyMap() },
  ) : DataChunkWriterFactory {
      override fun create(format, output, options): DataChunkWriter {
          require(format == DataExportFormat.PARQUET) { ... }
          return ParquetChunkWriter(
              output = output,
              extraMetaDataProvider = extraMetaDataProvider,
          )
      }
  }
  ```
  Default `{ emptyMap() }` haelt den Bundle-Modus unveraendert (S4
  §2.2 Invariant); Single-File-Modus reicht den echten Provider
  durch.
- **`writerFactoryBuilder`-Signatur-Aenderung**: heute
  `() -> DataChunkWriterFactory`
  (`DataExportWiring.kt:105`, `ExportPreflightValidator.kt:23`,
  `DataExportRunner.kt:74`); der Builder hat keinen Zugriff auf
  den aufgeloesten Output-Modus (Plan-Review-v3 Finding 1). Wir
  aendern den Vertrag auf
  `(ExportOutput) -> DataChunkWriterFactory`. `ExportPreflightValidator.kt:105`
  ruft den Builder ohnehin erst, nachdem `ExportOutput.resolve(...)`
  durchgelaufen ist (`output: ExportOutput` ist im Scope, siehe
  `ExportPreflightValidator.kt:102–106`); der Builder bekommt den
  Wert jetzt explizit gereicht. Plan-Review-v4 Finding 2: die
  Owner-Kette ist tiefer als nur Validator/CLI. Alle Sites:
  - **`DataExportRunner.kt:74`** (Konstruktor-Param) +
    `DataExportRunner.kt:101` (Validator-Konstruktion) ziehen die
    neue Signatur mit.
  - **`ExportPreflightValidator.kt:23`** (Konstruktor-Param) +
    `ExportPreflightValidator.kt:105` (Aufruf).
  - **`DataExportWiring.kt:105`** (CLI-Produktiv-Site).
  - **Alle `DataExportRunner*Test*.kt`-Builder-Helper**: greppen
    via `rg "writerFactoryBuilder\s*=\s*\{" hexagon/application/src/test adapters/driving/cli/src/test`,
    jeweils auf `{ _ -> ... }` umstellen (der ExportOutput-
    Parameter wird im Test bisher nicht beruecksichtigt).
- **`DataExportWiring.kt:105` output-mode-aware `writerFactoryBuilder`**:
  ```kotlin
  writerFactoryBuilder = { exportOutput ->
      val parquetFactory = when (exportOutput) {
          // Bundle-Pfad: kein Footer-KV (S4 §2.2-Invariant). Bundle-Closure
          // schreibt manifest.yaml separat.
          is ExportOutput.FilePerTable -> ParquetChunkWriterFactory(
              warningSink = { warnings += it },
          )
          // Single-File-Pfad: Footer-KV mit aufgeloestem Schema.
          is ExportOutput.SingleFile -> ParquetChunkWriterFactory(
              warningSink = { warnings += it },
              extraMetaDataProvider = ParquetSingleFileManifestWriter(
                  producerVersion = VersionInfo.PRODUCT_VERSION,
              ).provider,
          )
          // Stdout: DataExportRunner lehnt das fuer Parquet bereits ab
          // (Capability requiresSeekableOutput, Review-Finding F1). Der
          // Builder wird hier nie gerufen; defensive Default.
          is ExportOutput.Stdout -> ParquetChunkWriterFactory(
              warningSink = { warnings += it },
          )
      }
      CompositeDataChunkWriterFactory(
          defaultFactory = DefaultDataChunkWriterFactory(warningSink = { warnings += it }),
          parquetFactory = parquetFactory,
      )
  },
  ```
  Damit traegt jeder Single-File-Parquet-Export den
  `d-migrate.manifest`-Footer-KV (AP11 §6.2, lesbar via
  `ParquetSingleFilePreflight.kt:91`), Bundle-Dateien bleiben ohne
  KV (Bundle-Manifest.yaml uebernimmt diese Rolle).
- **`DataExportWiring.kt` `onBundleClosure`-Hook**:
  ```kotlin
  // unmittelbar vor dem StreamingExporter.export(...)-Aufruf in der
  // exportExecutor-Lambda:
  onBundleClosure = ParquetBundleClosure(
      producerVersion = VersionInfo.PRODUCT_VERSION,
  ),
  ```
  `ParquetBundleClosure.invoke(context)`
  (`ParquetBundleClosure.kt:31`) ignoriert
  Nicht-Parquet-Aufrufe selbst (`if (context.format != PARQUET) return`),
  also keine CLI-seitige Format-Verzweigung noetig — der Hook lebt
  als reine Funktions-Referenz im Wiring.

- **Test-Strategie fuer den Footer-KV-Pfad** (Plan-Review-v3
  Finding 2): `ParquetChunkWriter.extraMetaDataProvider` ist private
  (`ParquetChunkWriter.kt:51`); direkte Introspektion am
  konstruierten Writer ist nicht moeglich. Stattdessen:
  - **`CompositeDataChunkWriterFactoryTest`** bleibt rein
    Routing-Test (`PARQUET → parquetFactory`,
    `JSON/YAML/CSV → defaultFactory`, output-mode-aware Builder
    liefert die richtige Variante).
  - **Neuer Test in `:adapters:driven:formats-parquet`** —
    `ParquetChunkWriterFactoryFooterKvTest`: erstellt eine Temp-
    Parquet-Datei via `ParquetChunkWriterFactory(extraMetaDataProvider = ...)`,
    schreibt einen Chunk, schliesst den Writer, oeffnet die Datei
    mit `ParquetFileReader.open(...)`, prueft
    `fileMetaData.keyValueMetaData["d-migrate.manifest"]` enthaelt
    YAML mit dem erwarteten Tabellennamen.
  - **Bundle-Default-Case in derselben Datei**: Writer ohne
    Provider, Datei oeffnen, `keyValueMetaData` darf den
    `d-migrate.manifest`-Key NICHT enthalten.
  Damit ist der S4-Invariant ueber den Wire-Vertrag (geschriebene
  Footer-Bytes) belegt, nicht ueber Implementierungs-Internals.

### 2.2 Streaming-Layer-Refactor (`:adapters:driven:streaming`)

- **`TableImportParams.tableInput`**: Type-Widening von
  `ResolvedTableInput.Stream` auf `ResolvedTableInput`
  (Sealed-Parent). Erlaubt dem `TableImporter`, beide Sub-Typen
  zu sehen, ohne den Caller (`StreamingImporter.import`-Loop) zur
  Vorab-Diskriminierung zu zwingen.
  Smoke-Sweep (Plan-Review Punkt 1): `TableImportParams` wird nur
  im Streaming-Modul + dessen Tests konsumiert; keine Cross-Module-
  Brueche.
- **`TableImporter`**-Konstruktor: Pflicht-Parameter
  `seekableReaderFactory: SeekableDataChunkReaderFactory?` (nullable
  wie der StreamingImporter-Param aus F4) wird **hinten angehaengt**
  (Plan-Review Finding 2):
  ```kotlin
  internal open class TableImporter(
      private val readerFactory: DataChunkReaderFactory,
      private val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
      private val seekableReaderFactory: SeekableDataChunkReaderFactory? = null,
  )
  ```
  Position 3 mit Default `null` bricht keinen positional Call-Site
  (`StreamingImporter.kt:39` heute `TableImporter(readerFactory, onTableOpened)`
  bleibt valide); produktive Caller mit Seekable-Pfad ueberschreiben
  named. Tests, die nur Stream-Inputs nutzen, brauchen nichts zu
  aendern.
- **`prepareImport` Dispatch**: `when (val ti = params.tableInput)`
  - `is ResolvedTableInput.Stream` → bestehender Pfad
    (`readerFactory.create(format, openInput(), readOptions, chunkSize)`).
  - `is ResolvedTableInput.Seekable` → Elvis-Resolver statt `!!`
    (Plan-Review Finding 3):
    ```kotlin
    val factory = seekableReaderFactory ?: error(
        "TableImporter received ResolvedTableInput.Seekable but no " +
            "seekableReaderFactory was wired. Consumer (e.g. MCP) " +
            "should not produce Seekable inputs without wiring it."
    )
    factory.create(
        format = params.format,
        source = ti.source,
        table = ti.table,
        schema = ti.schema,
        chunkSize = params.config.chunkSize,
        options = params.readOptions,
    )
    ```
    Damit bleibt direkte `TableImporter.import(...)`-Nutzung in
    Unit-Tests sicher; der aeussere `StreamingImporter`-Pre-Stream-
    Check (siehe unten) fungiert als zweite, frueher feuernde Linie.
  - **Binding-Plan-Konstruktion** (Plan-Review-v3 Finding 3):
    `ChunkContext` (`TableImportLoopSupport.kt:32`) traegt heute
    KEIN Schema-Feld; die Bindings entstehen ueber
    `TableImportBindingSupport.buildBindingPlan(headerColumns: List<String>?, targetColumns = ..., ...)`
    (`TableImportBindingSupport.kt:18-20`). Der `headerColumns`-
    Parameter ist nullable, weil `DataChunkReader.headerColumns()`
    (`DataChunkReader.kt:75`) als `List<String>?` zurueckkommt
    (Plan-Review-v4 Finding 4):
    ```kotlin
    val headerColumns: List<String>? = when (val ti = params.tableInput) {
        is ResolvedTableInput.Stream -> reader.headerColumns()  // nullable
        is ResolvedTableInput.Seekable -> ti.schema.columns.map { it.name }  // non-null
    }
    buildBindingPlan(
        headerColumns = headerColumns,
        targetColumns = ...,
        ...
    )
    ```
    Damit ueberspringt der Seekable-Pfad den Reader-Header-Read
    komplett — Parquet-Reader liefert das Schema implizit ueber den
    Footer und braucht keinen separaten Header-Call.
- **`StreamingImporter.import`-Loop**: der Sealed-`when`-Block faellt
  weg; jeder `discoveredInputs`-Eintrag wird direkt an
  `tableImporter.import(TableImportParams(tableInput = ti, …))`
  uebergeben. `@Suppress("UnusedPrivateMember")` auf
  `seekableReaderFactory` (`StreamingImporter.kt:30`) faellt weg,
  weil die Factory jetzt produktiv durch das `TableImporter`-Field
  flieusst (an den Konstruktor durchgereicht).
- **Pre-Stream-Check fuer Seekable + null-Factory**: ganz vorne im
  Loop (vor dem `tableImporter.import`-Aufruf) — wenn
  `tableInput is Seekable && seekableReaderFactory == null` →
  `error("Seekable input requires seekableReaderFactory; consumer (e.g. MCP) " +
  "should not produce Seekable inputs without wiring it.")`.
  Damit bleibt MCP-Code, der heute null durchreicht und nie
  einen Seekable-Input bekommt, untouched; ein Bug in der Hook-
  Verdrahtung (Phase-1-Hook produziert Seekable in einem Konsumenten
  ohne Factory) schlaegt mit klarer, frueher Meldung fehl, **bevor**
  der TableImporter ueberhaupt erreicht wird. Der innere Elvis-Check
  im TableImporter ist die zweite Verteidigungslinie fuer direkte
  Unit-Test-Nutzung.

### 2.3 Seekable-Dispatch-Tests (`:adapters:driven:streaming`)

Tests bleiben parquet-frei. Neue Test-Datei
`StreamingImporterSeekableDispatchTest.kt`:

- **Fake-`SeekableDataChunkReaderFactory`**: returnt einen
  `FakeReader` mit konfigurierbarem Header + Chunks (analog zum
  bestehenden `FakeReaderFactory`).
- **Test 1**: `StreamingImporter` mit einem Seekable-Input
  (`ImportInput.ResolvedSingleFile` ueber `ImportInputResolver` →
  `ResolvedTableInput.Seekable`) und dem Fake → Importer ruft die
  Seekable-Factory mit `(format, source, table, schema, chunkSize,
  options)`. Assertion: Argumente werden korrekt durchgereicht;
  `readerFactory` (Stream) wird **nicht** beruehrt.
- **Test 2**: `ImportInput.ResolvedBundle` mit zwei Tabellen → der
  Importer dispatched pro Tabelle einmal an die Seekable-Factory.
- **Test 3**: Null-`seekableReaderFactory` + Seekable-Input →
  `error(...)` mit der dokumentierten Meldung. Assertion auf den
  Wortlaut der Fehlernachricht.
- **Test 4**: gemischtes Szenario darf es nicht geben — der
  `ImportInputResolver` produziert immer **eine** Variante pro
  Input. Test stellt sicher, dass Dispatch deterministisch ist
  (gleiches Input → gleiche Factory).
- **Test 5 (Plan-Review-v1 Punkt 8, korrigiert in v2 Finding 3)**:
  Seekable-Input mit `resumeState.committedChunks > 0`. Der
  `DataChunkReader`-Port hat heute nur `nextChunk()` und
  `headerColumns()`; das tatsaechliche Skipping passiert in
  `TableImporter.skipCommittedChunks(...)` als privater Loop ueber
  `nextChunk()`. Der Test verwendet daher einen Fake-Reader, der
  jeden `nextChunk()`-Call mitzaehlt. Assertion bei
  `committedChunks = 5`:
  - Der Fake-Reader sieht **vor** dem ersten committeten Chunk
    mindestens 5 `nextChunk()`-Aufrufe (Skip-Loop).
  - Der erste Chunk, den die Session-`commitChunk(...)`-Spy sieht,
    ist `chunkIndex = 5` (= post-skip).
  Damit ist nachgewiesen, dass der Seekable-Dispatch auch durch
  den Skip-Pfad laeuft, ohne eine neue Reader-API zu behaupten.
  Voller Resume-E2E bleibt S9b-Aufgabe.
- **Regression**: bestehende
  `StreamingImporterTest`/`StreamingImporterTestPart{2,3}`-Tests,
  die heute den Stopgap-Throw erwarten (gibt es? — Sanity-Check),
  werden auf Dispatch-Korrektheit umgeschrieben.

### 2.4 E2E-Fixture (`:test:e2e-cli`)

Existing-File-Konvention im Modul: `DataImportE2EMysqlTest.kt`,
`DataExportE2EPostgresTest.kt`, `E2ERoundTripPostgresTest.kt`.

Neue Datei: `DataParquetRoundTripE2EPostgresTest.kt` (Driver-Auswahl
nach Plan-Review-v3: PG/Testcontainers als Pflicht, analog
`E2ERoundTripPostgresTest.kt`; SQLite-Variante optional, siehe §6).

- **Schema-Fixture**: identisches Schema wie die bestehenden
  E2E-Tests (`users(id, name)`), damit Differential-Diagnose
  einfach ist.
- **Test-Setup** (Plan-Review-v4 Finding 6): die Ziel-Tabelle
  legt der Test **selbst per JDBC** an, weil `data import` keine
  DDL ausfuehrt — analog
  `test/e2e-cli/.../DataImportE2EPostgresTest.kt:111` (`CREATE TABLE users (...)`
  vor dem Import-Call). Quell-Tabelle fuer den Export ebenfalls
  vor dem Export-Call mit ein paar Test-Rows befuellen
  (`INSERT INTO users ...`).
- **Bundle-Roundtrip**:
  - Vor-Setup: Quell-DB mit `users`-Tabelle + 3 Zeilen.
  - `data export --format parquet --output <tempdir> --split-files`
    schreibt ein Bundle.
  - Assertion: `<tempdir>/manifest.yaml` existiert,
    `<tempdir>/users.parquet` existiert.
  - Ziel-Tabelle in fremder DB via JDBC anlegen
    (CREATE TABLE users(...)), dann `data import --format parquet
    --source <tempdir>` → Tabelle hat erwartete Zeilen
    (SELECT-Vergleich).
- **Single-File-Roundtrip**:
  - Vor-Setup: gleiche Quell-DB-Tabelle.
  - `data export --format parquet --output <tempfile>.parquet`.
  - Ziel-Tabelle in fremder DB via JDBC anlegen.
  - `data import --format parquet --source <tempfile>.parquet` ohne
    `--table` → Footer-KV-Inferenz liefert den Tabellennamen
    (Review-Finding A4 wird hier produktiv geprueft).
- **Gating**: `taskNamePattern("integration")` im Modul-Build laeuft
  nur mit `-PintegrationTests`; Default-Lauf skipped. Symmetrisch zu
  den bestehenden E2E-Tests.

### 2.5 MCP-Sicherheitsnetz (`:adapters:driving:mcp`)

In Batch 12 (F4) ist `seekableReaderFactory` auf null defaulted
worden; MCP fasst es nie an. S7 baut das Dispatch jetzt produktiv —
ein theoretischer MCP-Pfad, der Seekable produziert, wuerde mit
dem neuen `error(...)` ueber den Pre-Stream-Check sterben.

Heute kommt MCP nie zu Seekable, weil:

1. `McpDataImportJobWorker.execute` (Batch 7 / Finding B1) lehnt
   `format=parquet` upfront mit `MCP_DATA_IMPORT_UNSUPPORTED_FORMAT`
   ab — die ImportInput-Aufloesung wird gar nicht erst angestossen.
2. MCP verdrahtet `inputResolutionHook = ImportInputResolutionHook.NoOp`
   (Default) — die NoOp-Variante macht keine
   `Directory → ResolvedBundle`-Transformation, also bleibt der
   Hook-Output Stream-only.

Beide Schichten sind belt-and-braces; S7 dokumentiert das im
`StreamingImporter`-Kommentar (Ablauf-Klausel zur Sicherheits-
Architektur).

### 2.6 Tests (Gesamtuebersicht)

| Datei | Modul | Inhalt |
| ----- | ----- | ------ |
| `StreamingImporterSeekableDispatchTest.kt` (neu) | `:adapters:driven:streaming` | Fake-basierte Dispatch-Tests |
| `StreamingImporterTest.kt` u.a. (Update) | `:adapters:driven:streaming` | Stopgap-Throw-Erwartungen → Dispatch-Erwartungen |
| `TableImporterTest.kt` (falls existent, Update) | `:adapters:driven:streaming` | Sealed-`when`-Pfad pro Subtyp |
| `DataParquetRoundTripE2EPostgresTest.kt` (neu) | `:test:e2e-cli` | Bundle + Single-File Roundtrip gegen PG via Testcontainers |

## 3. Definition of Done

| DoD-Item | Belegbefehl |
| -------- | ----------- |
| Footer-KV wird im Single-File-Modus geschrieben | `ParquetChunkWriterFactoryFooterKvTest` (`:adapters:driven:formats-parquet`) schreibt eine Temp-Parquet-Datei via Factory mit Provider, liest sie via `ParquetFileReader.open(...)` zurueck und assertet `fileMetaData.keyValueMetaData["d-migrate.manifest"]` enthaelt den erwarteten YAML |
| Footer-KV wird im Bundle-Modus NICHT geschrieben | derselbe Test mit Factory ohne Provider — die wiederholt geschriebene Datei traegt den `d-migrate.manifest`-Key nicht (S4 §2.2-Invariant haelt) |
| Composite routet PARQUET → ParquetChunkWriterFactory | `CompositeDataChunkWriterFactoryTest` bleibt rein Routing/Wiring (kein Footer-KV-Assertion-Job — Composite kennt den Provider nicht) |
| Bundle-`manifest.yaml` wird produktiv geschrieben | E2E-Test (siehe unten) bzw. `DataExportWiringTest`-Case mit verdrahtetem `onBundleClosure` |
| Stopgap-Branch entfaellt | `rg -n "Seekable consumption is not yet wired|S7 adds the TableImporter dispatch" adapters/driven/streaming/src/main/kotlin/` leer (Plan-Review-v2 Finding 4: tatsaechlicher Stopgap-Text ist „S7 adds the TableImporter dispatch path via seekableReaderFactory.", nicht „S7 ...") |
| Dispatch-Tests gruen | `make docker-test MODULES=":adapters:driven:streaming"` |
| `seekableReaderFactory` ist im TableImporter genutzt | Detekt-`UnusedPrivateMember`-Suppress weg, `@Suppress` greppbar leer in `StreamingImporter.kt` |
| Modulgrenze gehalten | `grep -rn "import dev.dmigrate.format.parquet" adapters/driven/streaming/` leer |
| E2E-Bundle-Roundtrip gruen | `make integration INTEGRATION_TASKS="-PintegrationTests :test:e2e-cli:test --tests *DataParquetRoundTripE2EPostgresTest"` (Plan-Review-v4 Finding 6: Filter passt jetzt zum geplanten Klassennamen) |
| E2E-Single-File-Roundtrip gruen (inkl. Footer-KV-Inferenz ohne `--table`) | (selber Befehl, anderer Test-Case) |
| Resume-Smoke fuer Seekable (Plan-Review-v1 Punkt 8, v3 Finding 4) | Dispatch-Test mit `resumeState.committedChunks = N` verifiziert per Fake-Reader, dass mindestens `N` `nextChunk()`-Aufrufe vor dem ersten `commitChunk(...)` passieren UND der erste committete Chunk `chunkIndex = N` traegt |
| Repo-weit Build gruen | `make docker-check` (Output in `/tmp/build.log`) |
| Kover ≥90% in `:adapters:driven:streaming` + `:adapters:driving:cli` + `:adapters:driven:formats-parquet` | implizit in `make docker-check` |

## 4. Sub-Slice-Schnitt

| Slice | Inhalt | Hauptdatei(en) | Test-Quelle |
| ----- | ------ | -------------- | ----------- |
| **S7-0** | Export-Wiring: `ParquetChunkWriterFactory(extraMetaDataProvider = ...)`-Konstruktor-Erweiterung; **`writerFactoryBuilder`-Signaturwechsel zu `(ExportOutput) -> DataChunkWriterFactory` in `DataExportRunner.kt:74` + `ExportPreflightValidator.kt:23,105` + allen `DataExportRunner*Test`-Helpern** (Plan-Review-v4 Finding 2); CLI baut den Composite output-mode-aware; `onBundleClosure = ParquetBundleClosure(VersionInfo.PRODUCT_VERSION)`. Vorbedingung des E2E-Pfads. | `ParquetChunkWriterFactory.kt`, `DataExportRunner.kt`, `ExportPreflightValidator.kt`, `DataExportWiring.kt`, alle `DataExportRunner*Test*.kt`-Builder-Helper | `ParquetChunkWriterFactoryFooterKvTest` (Round-Trip-Footer-Read) in `:adapters:driven:formats-parquet`; `CompositeDataChunkWriterFactoryTest` bleibt Routing-only; `DataExportWiringTest` deckt `onBundleClosure`-Verdrahtung |
| **S7a** | `TableImportParams.tableInput`-Widening + `TableImporter`-Konstruktor-Param **als 3. Position hinten** + interner Sealed-Dispatch mit Elvis-Resolver | `TableImporter.kt`, `TableImportParams.kt` | bestehende Tests bleiben gruen (Konstruktor-Default `null` faengt sie ab) |
| **S7b** | `StreamingImporter.import`-Loop Sealed-when entfernen + Pre-Stream-Check (null-Factory + Seekable) + Param-Durchreichen an `TableImporter` | `StreamingImporter.kt` | bestehende Tests, Stopgap-Throw-Erwartungen umgeschrieben |
| **S7c** | Fake-basierte `StreamingImporterSeekableDispatchTest` inkl. Resume-Smoke (Fake-Reader zaehlt `nextChunk()`-Aufrufe; bei `committedChunks = N` mindestens N Calls vor dem ersten `commitChunk`, erster committeter Chunk hat `chunkIndex = N`) | neuer Test in `streaming/test` | neue Tests |
| **S7d** | E2E-Fixture Single-File-Roundtrip in `:test:e2e-cli` gegen PG/Testcontainers (Footer-KV-Inferenz `--source x.parquet` ohne `--table`) | `DataParquetRoundTripE2EPostgresTest.kt` | neuer Test, `-PintegrationTests`-gated |
| **S7e** | E2E-Fixture Bundle-Roundtrip + Plan-Doc-Closeout + Umbrella-§3.4-Update | selbe Datei + Doc-Move | neuer Test-Case in selber Klasse |

S7-0 muss vor S7d/S7e fertig sein, sonst kann der E2E-Test den
Footer-KV-/Bundle-Manifest-Pfad nicht beweisen. S7a/S7b sind eng
gekoppelt; bei Bedarf zu einem Commit zusammenfassen, wenn der
Dispatch sonst nicht atomar gruen waere.

## 5. Bewusst NICHT in S7

- **Kein Checkpoint-Specifics-Eintrag** (`SingleFileCheckpointSpecifics`,
  `BundleCheckpointSpecifics`). → S8.
- **Kein** `ImportExecutionPlanner.prepare`-Reorder
  (Checkpoint-Load vor Phase-2-Hook). → S8.
- **Kein** non-null `resumeExpectedSha256` im Phase-2-Hook —
  bleibt heute `null`, Pass-Through ueber `ParquetSingleFileResolver.phase2`. → S8.
- **Keine Bundle-/Single-File-Test-Familien** (CLI-Preflight-Codes,
  Resume-Familien, DuckDB-/Arrow-KV-Toleranz). → S9a/S9b.
- **Keine MCP-Parquet-Freigabe** — MCP lehnt parquet weiterhin in
  `McpDataImportJobWorker.execute` ab. Eine spaetere Slice (post-Cut A)
  kann die MCP-Hooks verdrahten, wenn es eine Anforderung gibt.

## 6. Offene Designentscheidungen (vor Implementierung)

1. **E2E-Driver-Auswahl**: PG (Testcontainers) als Pflicht, SQLite
   als optionaler Smoke?
   - **Entschieden nach Plan-Review-v3**: PG/Testcontainers als
     Pflicht, weil die bestehenden E2E-Familien (`DataImportE2EMysqlTest.kt`,
     `DataExportE2EPostgresTest.kt`, `E2ERoundTripPostgresTest.kt`)
     dort liegen und `JdbcTestHelper.kt` darauf zugeschnitten ist.
     Konsistenz mit der existierenden Test-Familie schlaegt die
     SQLite-Schnelligkeit.
   - SQLite-Variante als zusaetzlicher Smoke (`-PintegrationTests`-
     gated genauso, aber ohne Testcontainers-Cost) ist nice-to-have,
     kein Pflicht-Item — kann in S9b nachgereicht werden, wenn die
     Test-Familie reift.
2. **Reader-`schema`-Quelle fuer Seekable**: laut `ResolvedTableInput.Seekable.schema`
   schon Pflicht. Aber wie kommt das Schema in den
   `ChunkContext`/`TargetColumn`-Pfad?
   - **Vorschlag**: `prepareImport` extrahiert `ti.schema.columns`
     genauso wie heute Header-Zeilen extrahiert werden. Der Reader
     macht in dem Pfad keinen Header-Read (Footer reicht).
3. **Resume-Verhalten fuer Seekable**: heute reicht der
   `committedChunksOffset` aus dem `ImportTableResumeState` durch.
   Parquet-Reader muss Row-Groups skippen koennen, um an die
   Resume-Position zu kommen.
   - **Entschieden nach Plan-Review-v4 Finding 5**: S7 liefert
     einen kleinen **Dispatch-Skip-Smoke** in §2.3 Test 5 (Fake-
     Reader zaehlt `nextChunk()`-Aufrufe + erster committeter
     Chunk hat `chunkIndex = N`), damit der S3-Skip-Code-Pfad
     ueber den neuen Seekable-Dispatch ausgefuehrt wird. **Volle
     Resume-E2E-Familien** (Resume-Reference-Aufloesung, Manifest-
     Re-Hydration, KV-Toleranz) bleiben S9b.
4. **Wie reagiert der Dispatch auf MCP-`UnsupportedSeekable`-Factory-
   Konstellation (nach F4)?**
   - Plan-Review-v2 Finding 4 (korrigiert in v4 Finding 3):
     `SeekableDataChunkReaderFactory.unsupported(reason)` lebt
     weiterhin am Port (`hexagon/ports-read/.../SeekableDataChunkReaderFactory.kt:54`)
     als Companion-Factory; F4 hat lediglich MCP-Wiring von
     diesem Sentinel auf `null` defaulted. Was wirklich passiert,
     wenn jemand den Sentinel **explizit** injiziert: die Factory
     ist non-null → Pre-Stream-Null-Check faengt nichts, Elvis-
     Resolver im TableImporter faengt nichts, der Code laeuft in
     `factory.create(...)` (`SeekableDataChunkReaderFactory.kt:79–88`)
     und der Sentinel wirft seine **eigene**
     `UnsupportedOperationException` mit der konfigurierten
     `reason`-Message. Damit ist die Sentinel-Fehlermeldung
     diskoverabel (klare reason-String aus dem Konsumenten); der
     null-Check und der Elvis-Resolver decken nur den
     "vergessen-zu-verdrahten"-Fall ab.
5. **Test-Daten-Mengen in E2E**: 3 Zeilen reichen fuer Smoke?
   - **Vorschlag**: ja. KV-Toleranz und groessere Sets sind
     S9a/S9b-Aufgabe.
6. **Parquet-Bundle-Export ohne `--schema`**: laeuft das bereits
   sauber via S3b? Pruefung bei Plan-Review.

## 7. Folgeaufgaben

- **S8**: Checkpoint-Specifics + Phase-2-Hook bekommt echten
  Resume-Sha; `prepare`-Reorder.
- **S9a**: Bundle-CLI-Preflight-Codes (`MANIFEST_*`, `BUNDLE_*`),
  Resume-Familie, KV-Toleranz.
- **S9b**: Single-File-CLI-Preflight-Codes
  (`PARQUET_SINGLE_FILE_*`), Phase-1/2-Tests gegen ECHTE Parquet-
  Files (heute nur Hook-Routing-Coverage), Resume.
