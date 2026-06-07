# S6 — CLI-Wiring fuer Import und Export

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](./parquet-productive-cut-a.md)
> §3 S6).
>
> Status: In Progress.
>
> Verdrahtet die in S3/S4/S5a/S5b gelieferten Bausteine im
> CLI-Modul, ergaenzt `StreamingImporter` um den Pflicht-
> Konstruktor-Parameter `seekableReaderFactory`, fuehrt
> zwei parquet-freie Hook-Ports (Phase-1/Phase-2) durch
> `ImportPreflightResolver` und `DataImportRunner` und
> schaltet `--no-checkpoint` frei. **Kein** End-to-End-
> Konsum von `ResolvedTableInput.Seekable` durch
> `TableImporter`/`StreamingImporter` (S7), **keine**
> `CheckpointOperationSpecifics`-Auspraegung (S8),
> **keine** Bundle-/Single-File-Test-Familien (S9a/S9b).

---

## 1. Scope

Per Umbrella §3 S6-Cell, neu konkretisiert nach
Code-Review-Findings:

1. **Modulgrenze halten** (`hexagon/application/build.gradle.kts:1-2`
   "never on adapters"): Kein direkter Aufruf von
   `ParquetSingleFilePreflight`/`ParquetBundleResolver`
   aus `DataImportRunner` oder
   `ImportPreflightResolver`. Stattdessen zwei
   parquet-freie Hook-Ports (siehe §2.5/§2.6), deren
   produktive Impl im CLI-Modul lebt.
2. **CLI-Format-Choices**: `parquet` in `.choice(...)` von
   `DataImportCommand.kt:41-44` und
   `DataExportCommand.kt:35-38`.
3. **Pfad-only-/Stdin-Ablehnung** fuer `--format parquet`:
   in `DataImportHelpers.validateCliFlags` und
   `DataExportHelpers` (Symmetrie), bevor das Wiring den
   Reader-/Writer-Pfad baut.
4. **`StreamingImporter`-Constructor-Bruch**: Pflicht-
   Parameter `seekableReaderFactory: SeekableDataChunkReaderFactory`
   (AP12 §5.1). Konsum bleibt S7-Stopgap.
5. **`CompositeDataChunkWriterFactory`** im CLI-Modul
   (AP12 §5.2).
6. **`resolveFormat`-Hook** (`DataImportHelpers.kt:25-66`)
   um `.parquet`-Extension und `manifest.yaml`-Detektion;
   das ist die Voraussetzung, dass ein Bundle-Verzeichnis
   ohne explizites `--format parquet` erkannt wird.
7. **Phase-1-Hook** (parquet-frei): vom Resolver gerufen,
   transformiert `ImportInput.SingleFile`/`Directory` in
   `ResolvedSingleFile`/`ResolvedBundle`, wenn
   `format == PARQUET`. Liefert den Phase-1-`contentSha256`
   auf `ImportInput.ResolvedSingleFile` (Feld existiert
   schon, S5b).
8. **Phase-2-Hook** (parquet-frei): vom Runner gerufen
   **vor** `executionPlanner.prepare(...)`, damit
   Fingerprint, Resume-Context und Initialmanifest auf
   dem finalen Input arbeiten. In S6 immer mit
   `resumeExpectedSha256 = null`; der Specifics-getriebene
   Pfad ist S8.
9. **`--no-checkpoint`-Flag** (AP12 §4.2): konfliktet mit
   `--resume` (Exit 2 in
   `DataImportHelpers.validateCliFlags`), deaktiviert die
   Phase-1-Sha256-Berechnung und schaltet den Checkpoint-
   Store no-op.
10. **Build**: `:adapters:driven:formats-parquet` als Dep
    im CLI-Modul.

## 2. Lieferumfang

### 2.1 `StreamingImporter`-Constructor (`adapters/driven/streaming`)

- `StreamingImporter.kt:19-45`: neuer Pflicht-Parameter
  `seekableReaderFactory: SeekableDataChunkReaderFactory`
  (kein Default, AP12 §5.1).
- Der `is ResolvedTableInput.Seekable -> error("S7 ...")`-
  Stopgap aus S5a/S5b bleibt; Konsum in S7.
- Call-Sites:
  - `DataImportWiring.kt:93` (CLI): erhaelt
    `ParquetSeekableDataChunkReaderFactory()` aus
    `adapters/driven/formats-parquet`.
  - Streaming- und Application-Modul-Tests, die
    `StreamingImporter` direkt konstruieren: Fake
    `SeekableDataChunkReaderFactory` aus Test-Scope.

### 2.2 CLI-Format-Choices (`adapters/driving/cli`)

- `DataImportCommand.kt:41-44`:
  `.choice("json", "yaml", "csv", "parquet")`.
- `DataExportCommand.kt:35-38`:
  `.choice("json", "yaml", "csv", "parquet").required()`.
- `DataExportFormat.fromCli("parquet")` ist bereits
  durch S3 nachgezogen
  (`hexagon/ports-common/src/main/kotlin/dev/dmigrate/format/data/DataExportFormat.kt:14`
  `PARQUET("parquet", listOf("parquet"))`,
  `fromCli` arbeitet ueber `entries`). Kein S3-Defizit;
  S6 muss nur die `.choice(...)`-Liste der CLI-Commands
  erweitern.

### 2.3 `resolveFormat` + `EXTENSION_FORMAT_MAP` (`hexagon/application`)

- `DataImportHelpers.kt:30-35`: `EXTENSION_FORMAT_MAP` um
  `"parquet" to "parquet"`.
- `DataImportHelpers.kt:43-66` `resolveFormat`: zusaetzliche
  Inferenzregel — wenn `sourcePath` ein Verzeichnis ist und
  `sourcePath/manifest.yaml` existiert, wird `"parquet"`
  inferiert (Bundle-Markerdatei). Der Aufrufer
  (`ImportPreflightResolver.kt:30-31`) prueft heute
  `Files.exists(sourcePath)` erst nach `resolveFormat`;
  fuer den `manifest.yaml`-Hook braucht der
  Inferenz-Branch denselben Existenz-Check — entweder
  durch Vorziehen oder durch defensive
  `Files.isDirectory`/`Files.exists`-Pruefung im Helper.
  Die Hook-Implementierung lebt komplett parquet-frei in
  `DataImportHelpers` (nur `Files.exists`-Check).
- `Cannot detect format from ...`-Fehlertext um `parquet`
  in der Auflistung erweitern.

### 2.4 `validateCliFlags`-Erweiterung (`hexagon/application`)

- `DataImportHelpers.kt:68-110`: neue Branch unmittelbar
  nach den bestehenden Konflikt-Checks:
  - `request.noCheckpoint && !request.resume.isNullOrBlank()`
    → `stderr("Error: --no-checkpoint and --resume are mutually exclusive.")`
    → Exit 2.
- Pfad-only fuer Parquet:
  - `format == PARQUET && request.source == "-"`
    → Exit 2 mit klarem Text.
  - Achtung: `resolveFormat` braucht Zugriff auf
    `request.format` und `sourcePath`; die Pfad-only-
    Pruefung lebt deshalb entweder direkt nach
    `resolveFormat` im Resolver oder als zusaetzliche
    Helper-Funktion `validateFormatPathRequirements`. Wir
    waehlen die Helper-Variante, damit
    `validateCliFlags` reine Request-Validierung bleibt
    und die Format-bezogene Pruefung nahe der
    Format-Aufloesung sitzt.
- `DataExportHelpers`: symmetrische Stdout-Ablehnung fuer
  `--format parquet` (Detail-Branch — Ort haengt vom
  Export-Helper-Layout ab, das im Wiring-Pass mit
  konkretisiert wird).

### 2.5 Phase-1-Hook-Port (`hexagon/application`)

Parquet-frei in der Application; produktive Impl im CLI.

- Neuer fun-interface-Port in
  `hexagon/application/.../ImportRunnerTypes.kt` oder als
  separate Datei, z.B.

  ```kotlin
  fun interface ImportInputPhase1Hook {
      fun maybeFinalize(
          rawInput: ImportInput,
          format: DataExportFormat,
          computeContentSha256: Boolean,
      ): ImportInput
  }
  ```

- Default-Impl (nicht-parquet): liefert `rawInput`
  unveraendert zurueck. Wird als
  `ImportInputPhase1Hook { raw, _, _ -> raw }`-Lambda im
  Default-Wiring genutzt.
- CLI-Impl
  (`adapters/driving/cli/.../ParquetImportInputPhase1Hook.kt`):
  - `format == PARQUET && rawInput is ImportInput.Directory`
    → `ParquetBundleResolver.resolve(...)`
    → `ImportInput.ResolvedBundle`.
  - `format == PARQUET && rawInput is ImportInput.SingleFile`
    → `ParquetSingleFilePreflight.phase1(path,
       explicitTable = rawInput.table,
       computeContentSha256 = computeContentSha256)`
    → `ImportInput.ResolvedSingleFile(...)`.
  - sonst: Pass-through.
- Einhaengung in
  `ImportPreflightResolver.kt:38-43`: nach
  `resolveImportInput`, **vor**
  `resolveSchemaPreflight`. So sieht der bestehende
  Schema-Preflight bereits den finalen Input.
- Konstruktor-Parameter von
  `ImportPreflightResolver` und damit transitiv
  `DataImportRunner` bekommt den Hook injiziert
  (genauso wie heute `schemaPreflight`,
  `targetResolver`, `urlParser`).

### 2.6 Phase-2-Hook-Port (`hexagon/application`)

Parquet-frei in der Application; produktive Impl im CLI.

- Neuer fun-interface-Port:

  ```kotlin
  fun interface ImportInputPhase2Hook {
      fun finalize(
          input: ImportInput,
          resumeExpectedSha256: String?,
      ): ImportInput
  }
  ```

- Default-Impl: Identity.
- CLI-Impl
  (`adapters/driving/cli/.../ParquetImportInputPhase2Hook.kt`):
  - `input is ImportInput.ResolvedSingleFile`
    → `ParquetSingleFilePreflight.phase2(phase1,
       resumeExpectedSha256)` → finalisierte
    `ResolvedSingleFile`-Variante (ggf. unveraendert).
  - sonst: Pass-through.
  - Bundle-Faelle haben keine Phase-2 (Phase-1 ist final
    via `ParquetBundleResolver`).
- Einhaengung in `DataImportRunner.runImport`
  (`DataImportRunner.kt:172-209`):
  - Direkt nach Zeile 177, **vor** dem
    `executionPlanner.prepare(...)`-Aufruf, wird
    `context.preparedImport` durch ein neu
    aufgebautes `SchemaPreflightResult` ersetzt, dessen
    `input` die Phase-2-Ausgabe ist.
  - `resumeExpectedSha256 = null` in S6 (S8 reicht den
    Wert aus `CheckpointOperationSpecifics`).
  - Phase-2-Fehler werden als `ImportPreflightException`-
    aequivalente Throwables gefangen und auf Exit-Code 3
    abgebildet (Symmetrie zur Phase-1-Fehlerbehandlung).

### 2.7 `CompositeDataChunkWriterFactory` (`adapters/driving/cli`)

- Neuer Typ im CLI-Modul (AP12 §5.2): Konstruktor nimmt
  `DefaultDataChunkWriterFactory` und
  `ParquetChunkWriterFactory`, delegiert
  `when (format) { PARQUET -> parquet; else -> default }`.
- `DataExportWiring.kt:104`:
  `writerFactoryBuilder = { CompositeDataChunkWriterFactory(...) }`.

### 2.8 `DataImportWiring` (`adapters/driving/cli`)

- `DefaultDataImportWiringFactory`
  (`DataImportWiring.kt:93-113`): baut
  `ParquetSeekableDataChunkReaderFactory()` und reicht es
  als neuen `StreamingImporter`-Pflicht-Parameter durch.
- Injiziert beide Hooks
  (`ImportInputPhase1Hook`, `ImportInputPhase2Hook`)
  mit den parquet-impls.
- `request.noCheckpoint` wird in das Phase-1-Hook-Argument
  (`computeContentSha256 = !request.noCheckpoint`)
  uebersetzt — fuer Default-Pfade ohne Parquet
  hat der Parameter keinen Effekt (Default-Hook
  ignoriert ihn).

### 2.9 `--no-checkpoint`-Flag-Pflug (`adapters/driving/cli` + `hexagon/application`)

- `DataImportCommand.kt`:
  `option("--no-checkpoint").flag()` mit Help-Text aus
  AP12 §4.2.
- `DataImportRequest`/`DataImportOptions`: neues Feld
  `noCheckpoint: Boolean = false`.
- `validateCliFlags`: `--no-checkpoint` ⊕ `--resume`
  (siehe §2.4).
- `ImportCheckpointManager`: bei
  `request.noCheckpoint == true`
  - `resolveCheckpointContext` liefert
    `ImportCheckpointContext(store = null, dir = null)`
    (oder vergleichbar); damit ist `CheckpointStore` no-op,
    und der Resume-Pfad bleibt deaktiviert.
  - `writeInitialManifest`/`buildCallbacks` muessen den
    null-`store`-Fall sauber durchlassen — verifizieren,
    dass die heutige Logik das schon kann; sonst minimal
    nachziehen.
- **Bewusst nicht**: Erweiterung von
  `CheckpointOperationSpecifics`. Die `--no-checkpoint`-
  Semantik fuer S6 ist "kein Lesen, kein Schreiben des
  bestehenden Manifest-Modells".

### 2.10 Build (`adapters/driving/cli/build.gradle.kts`)

- Neue Dep:
  `implementation(project(":adapters:driven:formats-parquet"))`.
- Bisher gelistet: `:adapters:driven:formats`,
  `:adapters:driven:streaming`.

### 2.11 Tests

S6 liefert nur die strukturellen CLI-/Helper-Tests, die
direkt aus dem Wiring fallen. Format-spezifische
Test-Familien (Bundle-Codes, Single-File-Codes,
Resume-Familien, KV-Toleranz) sind in S9a/S9b gebuendelt.

Bestehende Testdatei-Konventionen
(`DataImportWiringTest`, `DataExportWiringTest`,
`CliDataImportTest`, `CliDataExportTest`,
`DataImportRunnerTest`-Familie) werden weiterverwendet —
**keine neuen `DataImportCommandTest`/`DataExportCommandTest`-
Dateien**, weil das Repo schon eine etablierte
Wiring-/Helper-/Preflight-Test-Architektur hat.

- `DataImportHelpersResolveFormatTest`
  (oder Erweiterung des bestehenden Helper-Tests):
  - `.parquet`-Extension → `parquet`.
  - Directory mit `manifest.yaml` → `parquet` ohne
    explizites `--format`.
  - Directory ohne `manifest.yaml` und ohne Extension →
    bisheriges Fehlverhalten (Exit 2 / Fehlertext um
    `parquet` erweitert).
- `DataImportHelpersValidateCliFlagsTest`:
  - `--no-checkpoint` + `--resume` → Exit 2 mit
    spezifischem stderr-Text.
  - `--no-checkpoint` allein → kein Exit.
- `DataImportWiringTest`:
  - `StreamingImporter`-Constructor erhaelt eine echte
    `ParquetSeekableDataChunkReaderFactory`-Instanz.
  - Phase-1-Hook und Phase-2-Hook sind die parquet-
    Implementierungen.
- `DataExportWiringTest`:
  - `writerFactoryBuilder` baut eine
    `CompositeDataChunkWriterFactory`; `PARQUET` →
    Parquet-Factory; andere Formate → Default.
- `CompositeDataChunkWriterFactoryTest`:
  - `create(format = PARQUET, ...)` delegiert; `create`
    mit anderem Format → Default-Factory; `require`
    der Parquet-Factory bleibt unberuehrt.
- `CliDataImportTest`/`CliDataExportTest`
  (Clikt-Integrationsebene):
  - `--format parquet` mit `--source -` (Stdin) → Exit 2.
  - `--format parquet` mit Pfad → akzeptiert
    (Smoke; tieferer Pfad bleibt S9a/S9b).
- `ImportPreflightResolverParquetHookTest`
  (application, mit Fake-Hooks):
  - Phase-1-Hook wird mit korrektem `(rawInput, format,
    computeContentSha256)` gerufen.
  - Hook-Ausgabe (`ResolvedSingleFile`/`ResolvedBundle`)
    landet im `SchemaPreflightResult.input`.
  - `noCheckpoint=true` → `computeContentSha256=false`
    durchgereicht.
- `DataImportRunnerPhase2HookTest`
  (application, mit Fake-Hooks und Fake-ExecutionPlanner):
  - Phase-2-Hook wird vor
    `ImportExecutionPlanner.prepare`
    gerufen.
  - `resumeExpectedSha256` ist in S6 immer `null`.
  - Phase-2-Wurf → Exit 3.
- `DataImportRunnerNoCheckpointTest`:
  - `noCheckpoint=true` → Checkpoint-Store-Pfade no-op
    (kein `save`-Aufruf, kein `load`-Lesen);
    Phase-1-Hook bekommt `computeContentSha256=false`.

## 3. Definition of Done

| DoD-Item | Belegbefehl |
| -------- | ----------- |
| `.choice(...)` enthaelt `parquet` in Import und Export | `CliDataImportTest`, `CliDataExportTest` gruen |
| Pfad-only-/Stdin-Ablehnung produktiv | `CliDataImportTest`, `CliDataExportTest`, `DataImportHelpersValidateCliFlagsTest` gruen |
| `resolveFormat` kennt `.parquet` + `manifest.yaml` | `DataImportHelpersResolveFormatTest` gruen |
| `StreamingImporter`-Constructor hat den neuen Pflichtparameter | Compile-gruen, alle Call-Sites aktualisiert |
| `CompositeDataChunkWriterFactory` im CLI-Wiring | `CompositeDataChunkWriterFactoryTest`, `DataExportWiringTest` gruen |
| Phase-1-Hook im Resolver | `ImportPreflightResolverParquetHookTest` gruen |
| Phase-2-Hook vor `executionPlanner.prepare` | `DataImportRunnerPhase2HookTest` gruen |
| `--no-checkpoint` plumbing | `DataImportRunnerNoCheckpointTest`, `DataImportHelpersValidateCliFlagsTest` gruen |
| Modulgrenze gehalten | `grep -rn "ParquetSingleFilePreflight\|ParquetBundleResolver" hexagon/` leer |
| Repo-weit Build gruen | `make docker-check` (Output in `/tmp/build.log`) |
| Modul-Tests gruen | `make docker-test MODULES=":adapters:driving:cli :adapters:driven:streaming :hexagon:application :adapters:driven:formats-parquet"` |
| Kover ≥90% in `:adapters:driving:cli` + `:hexagon:application` | implizit in `make docker-check` |

## 4. Bewusst NICHT in S6

- **Kein End-to-End-Konsum** von
  `ResolvedTableInput.Seekable` durch
  `TableImporter`/`StreamingImporter`. Der Stopgap
  `is Seekable -> error("S7 ...")` bleibt. → S7.
- **Keine `CheckpointOperationSpecifics`-Auspraegung**:
  `SingleFileCheckpointSpecifics` (Feld `contentSha256`)
  und `BundleCheckpointSpecifics` werden erst in S8
  eingefuehrt. → Konsequenz: `resumeExpectedSha256` an
  Phase-2 ist in S6 immer `null`, der Phase-2-Hook
  liefert in S6 keine Resume-Sha256-Verifikation.
- **Kein interner Umbau von `ImportExecutionPlanner.prepare`**:
  Der bestehende Ablauf innerhalb von `prepare`
  (Writer-Resolution → Options-Build → InputContext →
  Checkpoint-Context → Resume-Context →
  Initial-Manifest → Callbacks) bleibt unveraendert.
  Die Orchestrierungsreihenfolge **Phase-1 → connect →
  Phase-2 → prepare** wird sehr wohl in S6 hergestellt
  (§2.5–§2.6), damit Fingerprint, Resume-Context und
  Initialmanifest gegen den finalisierten
  `ImportInput.ResolvedSingleFile` rechnen. Was strikt
  S8 bleibt, ist der **interne** Reorder von
  `prepare` selbst — sobald `SingleFileCheckpointSpecifics`
  einen non-null `resumeExpectedSha256` liefert, muss der
  Phase-2-Hook zwischen Checkpoint-Load und InputContext
  geschoben werden; die Snapshot-Architektur hierfuer
  baut S8.
- **Keine Bundle-/Single-File-Test-Familien** (CLI-
  Preflight-Codes, Resume, KV-Toleranz, Phase-1/2-
  Fehlerklassen). → S9a/S9b.
- **Keine `ImportPreflightValidator`-Erweiterung** ueber
  S5a/S5b hinaus.

## 5. Folgeaufgaben

- **S7**: `StreamingImporter`-Stopgap entfaellt, der in
  S6 verdrahtete `seekableReaderFactory` wird in
  `TableImporter` konsumiert; produktive Seekable-
  Dispatch-Tests (Fakes) + E2E-Fixture in
  `:test:e2e-cli`.
- **S8**: `FileCheckpointStore`/`ImportCheckpointManager`
  bekommen `SingleFileCheckpointSpecifics` +
  `BundleCheckpointSpecifics`; der Phase-2-Hook erhaelt
  den echten `resumeExpectedSha256`-Wert; `ImportExecutionPlanner.prepare`
  wird bei Bedarf reorderiert, damit Checkpoint-Load und
  Phase-2 sauber verzahnen.
- **S9a/S9b**: Test-Familien fuer CLI-Preflight-Codes,
  Resume, KV-Toleranz, Phase-1/2-Fehlerklassen, Format-
  Resolver-Hook-Edge-Cases.
