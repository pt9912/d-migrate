# S5a — ParquetBundlePreflight + Resolver + ImportInput.ResolvedBundle

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S5a).
>
> Status: Closed (2026-06-06). Bundle-Lese-Pfad bis zum
> `ImportInput.ResolvedBundle`-DTO und `ResolvedTableInput.Seekable`-
> Liste vollstaendig. End-to-End-Konsum durch
> `TableImporter`/`StreamingImporter` ist explizit S7.

---

## 1. Scope

Per Umbrella §3 S5a-Cell:

1. `ImportInput.ResolvedBundle`-Sealed-Variante in
   `hexagon:ports-write` (AP9 §4.1).
2. `ParquetBundlePreflight` mit AP7 §9.1-Schritten 1-8 und
   AP7 §9.2-Fehlerklassen.
3. `ParquetBundleResolver` als CLI-Einstiegspunkt; ruft den
   Preflight und uebersetzt zum Port-DTO via
   `ParquetBundleAdapter` (AP9 §4.3).
4. `ImportInputResolver`-when-Zweig fuer `ResolvedBundle` →
   `List<ResolvedTableInput.Seekable>`.
5. Sealed-`when (input)`-Sweep ueber alle Konsumenten
   (`ImportPreflightValidator`, `DataImportSchemaPreflight`,
   `SchemaRefImportPreflightAdapter` + 7 Tests).

## 2. Architektur

### 2.1 Sealed-Sweep ist DoD-Punkt, nicht Stopgap

Umbrella S5a verlangt explizit den `ImportInputResolver`-
when-Zweig. Per Memo [[no-carveouts]] wuerden Stopgap-
Branches "kommt in Sub-Slice X" das Schneiden falsch
machen — hier ist der Branch aber struktureller
DoD-Bestandteil: der Resolver IST der Eintrittspunkt fuer
ResolvedBundle.

Der einzige verbleibende Stopgap-aehnliche Punkt sitzt im
`StreamingImporter`: er erhaelt jetzt potenziell
`Seekable`-Werte vom Resolver, leitet sie aber an den
`TableImporter` weiter, der nur `Stream` versteht.
Die hier eingebaute Pruefung
`is Seekable -> error("S7 wires the seekableReaderFactory ...")`
ist bewusst ein Stopgap fuer S7 — Umbrella §3 S5a-Cell
benennt das explizit ("End-to-End-Konsum durch
TableImporter/StreamingImporter ist S7, nicht hier"). Der
Branch erinnert beim ersten Aufruf an die offene Aufgabe.

### 2.2 ImportInputResolver-Returntype gewidet

Vor S5a war `resolve(): List<ResolvedTableInput.Stream>`.
Mit dem `ResolvedBundle`-Branch produziert der Resolver auch
`Seekable`-Werte; der Returntype ist auf sealed parent
`List<ResolvedTableInput>` gewidet. Konsumenten muessen ggf.
mit `when (tableInput)` narrowen — `StreamingImporter`
macht es heute, `TableImporter` wird in S7 die zweite
Seite implementieren.

## 3. Lieferumfang

### 3.1 Port-DTOs (`hexagon:ports-write`)

- `ImportInput.ResolvedBundle(bundleRoot, tables,
  resumeFingerprint)` — bewusst Parquet-frei im Vertrag.
- `ResolvedBundleTableBinding(table, path, schema,
  expectedSha256)` — pro Tabelle, mit optionalem SHA-256
  fuer Live-Pruefung im AP7-Preflight.
- `BundleResumeFingerprint(manifestSha256, formatVersion,
  producerVersion, tableOrder)` — Checkpoint-Vergleichs-
  basis (AP9 §4.1).
- `ImportInput.kt` import von `ChunkSchema` aus
  `dev.dmigrate.format.data` — `hexagon:ports-common`-
  Dependency war schon transitiv via `ports-write -> api
  ports-common`.

### 3.2 Adapter-DTOs (`formats-parquet/preflight`)

- `ResolvedParquetBundle(bundleRoot, manifestSha256,
  formatVersion, producerVersion, schemaSource, tables)`
  — Adapter-internes Ergebnis-DTO des Preflights.
  Manifest-spezifische Felder (`schemaSource`, vollstaendige
  Spaltenmetadaten) leben hier, nicht im Port.
- `ResolvedParquetTableBinding(table, path, schema,
  expectedSha256)`.

### 3.3 Preflight (`formats-parquet/preflight`)

- `ParquetBundlePreflight.run(bundleRoot, tableFilter?,
  tableOrder?)` implementiert AP7 §9.1 Schritte 1-8:
  1. Bundle-Verzeichnis-Existenz.
  2. `manifest.yaml` lesbar + parsebar (via S4 `ParquetManifestReader`
     mit `Context.BUNDLE`).
  3. `formatVersion` MAJOR-Pruefung gegen Reader-MAJOR=1.
  4. (Producer-Warnung kommt mit S6 in CLI-Wiring.)
  5. ManifestReader validiert Pflichtfelder.
  6. Kollisionsschutz K1-K5 (AP7 §6.2): table-/file-
     Duplikate, file-outside-bundle, file-missing,
     orphan-`.parquet`-Files.
  7. ManifestReader validiert `schemaSource`-Enum.
  8. Optional SHA-256-Pruefung pro Tabelle.
- `ParquetBundlePreflightException` mit Fehlercode-Praefix
  in der `message`-Zeile (AP7 §9.2).

### 3.4 Adapter + Resolver (`formats-parquet/preflight`)

- `ParquetBundleAdapter.toResolvedBundle(bundle):
  ImportInput.ResolvedBundle` (AP9 §4.3) — einzige
  Uebersetzungsstelle Adapter-DTO → Port-DTO. `internal`
  weil der `ResolvedParquetBundle`-Parameter `internal`
  ist.
- `ParquetBundleResolver.resolve(bundleRoot, tableFilter?,
  tableOrder?): ImportInput.ResolvedBundle` — CLI-fertige
  API.

### 3.5 Streaming-Modul

- `ImportInputResolver.resolve()`-Returntype gewidet auf
  `List<ResolvedTableInput>`.
- Neuer when-Zweig fuer `ImportInput.ResolvedBundle` →
  Liste von `ResolvedTableInput.Seekable` mit
  `SeekableChunkSource.Local(binding.path)`.
- `StreamingImporter.export()`-Loop bekommt
  `when (tableInput) { is Stream -> ...; is Seekable ->
  error("S7 ...") }` als Narrowing.

### 3.6 Sealed-Sweep

Folgende Stellen bekamen einen `is ResolvedBundle`-Zweig:

- `ImportPreflightValidator` (3 when-Stellen):
  `effectiveTables`, `inputFilesByTable`, `inputTopology`,
  `inputPath`.
- `DataImportSchemaPreflight` + `SchemaRefImportPreflightAdapter`:
  beide hatten `else -> input` als Fallback und brauchen
  keinen expliziten Branch (`ResolvedBundle` faellt in
  `else`).
- 7 Test-Dateien
  (`DataImportRunnerCallbackTest`,
  `DataImportRunnerDirectoryTest`,
  `DataImportRunnerResumeTest`,
  `DataImportRunnerExitCodeTest`,
  `DataImportRunnerHappyPathTest`,
  `DataImportRunnerHappyPathTestPart2`,
  `DataImportRunnerTest`): jeweils `is ResolvedBundle ->
  input.tables.map { it.table }` oder
  `input.tables.first().table` als minimalste valide
  Antwort.

### 3.7 Tests

- `ParquetBundleResolverTest` (formats-parquet, 6 Cases):
  - Round-Trip ueber `ParquetBundleClosure` (S3b) →
    `ParquetBundleResolver`: Bundle-Schemas kommen
    byte-identisch zurueck; SHA-256 ist 64-Hex; Fingerprint
    enthaelt `tableOrder`.
  - `MANIFEST_NOT_FOUND` bei fehlender `manifest.yaml`.
  - `MANIFEST_FILE_UNREFERENCED` bei Orphan-`.parquet`-Datei.
  - `MANIFEST_SHA256_MISMATCH` bei manipulierter Datei.
  - `tableFilter` wendet sich an.
  - `tableOrder` wendet sich an.
- `ImportInputResolverResolvedBundleTest` (streaming, 1 Case):
  `ResolvedBundle` → `List<Seekable>` mit korrektem
  `SeekableChunkSource.Local`-Pfad und Schema-Durchreichung.
- `StreamingPortsTest`: 3 neue Cases fuer die neuen
  Port-DTOs (Equality, Default-`expectedSha256`,
  Fingerprint-Equality) — Kover ≥90% in `ports-write`
  gehalten.

## 4. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| `ImportInput.ResolvedBundle` exhaustive | `make parquet-sweep`-Output | keine non-exhaustive `when (input)` mehr |
| Resolver liefert `Seekable` | `ImportInputResolverResolvedBundleTest` | gruen |
| `ParquetBundlePreflight` AP7 §9.2-Fehlercodes | `ParquetBundleResolverTest` Mismatch-/Orphan-/Missing-Cases | gruen |
| Adapter sammelt Metadaten | `ParquetBundleResolverTest` Round-Trip-Case | gruen |
| Repo-Build gruen | `make docker-check` | BUILD SUCCESSFUL |
| Module-Tests gruen | `make docker-test MODULES=":adapters:driven:formats-parquet :adapters:driven:streaming"` | BUILD SUCCESSFUL |

## 5. Bewusst NICHT in S5a

- **Kein End-to-End-Konsum durch `TableImporter`**.
  Stopgap-Branch im `StreamingImporter` markiert S7. Per
  Umbrella S5a-Cell explizit S7-Sub-Slice.
- **Kein CLI-`--source` für Bundle-Pfade**. Lebt in S6.
- **Kein Producer-Warnung** ("Fremd-Bundles" — AP7 §9.1
  Schritt 4). Lebt im CLI-Wiring (S6) — der Preflight
  wirft heute keine Warnung, weil die Stelle ohne CLI-
  Stderr keinen Sender hat.
- **Kein `MERGED_CONFLICT`-Logging** fuer
  Nullability-Konflikte (AP2 §9 Schluss). Lebt in einem
  AP2.c-Folge-Slice.
- **Kein Checkpoint-Schreiben** des
  `resumeFingerprint`. Lebt in S8.

## 6. Folgeaufgaben

- **S5b**: `ImportInput.ResolvedSingleFile`-Sealed-Variante
  + `ImportInputResolver`-when-Zweig (analog zu S5a, aber
  fuer Single-File-Footer-KV aus S4).
- **S6**: CLI-Wiring entscheidet pro `--source`-Pfad, ob
  `ParquetBundleResolver` (Verzeichnis) oder
  `ParquetSingleFilePreflight` (S4) laeuft, und reicht
  das Ergebnis als `ImportInput.ResolvedBundle` bzw.
  `ImportInput.ResolvedSingleFile` an den
  `StreamingImporter` durch.
- **S7**: `StreamingImporter`-Constructor um
  `seekableReaderFactory: SeekableDataChunkReaderFactory`-
  Parameter erweitern; `TableImporter` versteht
  `ResolvedTableInput.Seekable` (ParquetReader-Pfad). Der
  Stopgap-`error()`-Branch entfaellt.
