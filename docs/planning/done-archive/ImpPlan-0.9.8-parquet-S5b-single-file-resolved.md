# S5b — ImportInput.ResolvedSingleFile + Resolver-when-Zweig

> Sub-Slice der Cut-A-Umsetzung
> ([`parquet-productive-cut-a.md`](parquet-productive-cut-a.md)
> §3 S5b).
>
> Status: Closed (2026-06-06). Analog zu S5a, aber fuer den
> Single-File-Footer-KV-Pfad aus S4. **Kein** Runner-Hook
> (S6), **kein** End-to-End-Konsum (S7).

---

## 1. Scope

Per Umbrella §3 S5b-Cell:

1. `ImportInput.ResolvedSingleFile`-Sealed-Variante in
   `hexagon:ports-write`.
2. `ImportInputResolver`-when-Zweig fuer `ResolvedSingleFile`
   → 1-Element-`List<ResolvedTableInput.Seekable>`.
3. Sealed-Sweep ueber alle `ImportInput`-Konsumenten —
   strukturell parallel zum S5a-Sweep, nur eine weitere
   Sealed-Variante.

## 2. Lieferumfang

### 2.1 Port-DTO (`hexagon:ports-write`)

- `ImportInput.ResolvedSingleFile(table, path, schema,
  contentSha256?)` — Parquet-frei im Vertrag.
  `contentSha256` Default `null` (AP11 §6.4: Hash ist
  optional, wird vom CLI-Resolver bei aktivem Resume
  gesetzt).

### 2.2 Streaming-Modul

- `ImportInputResolver.resolve()` bekommt einen
  `is ImportInput.ResolvedSingleFile`-Branch, der ein
  `ResolvedTableInput.Seekable` mit
  `SeekableChunkSource.Local(input.path)` und dem schon
  aufgeloestem Schema baut. Returntype-Widening ist nicht
  noetig — S5a hatte das bereits auf
  `List<ResolvedTableInput>` gewidet.
- `StreamingImporter`-Stopgap `is Seekable -> error("S7
  ...")` bleibt aktiv (Umbrella S5b: kein End-to-End-Konsum
  hier).

### 2.3 Sealed-Sweep

- `ImportPreflightValidator` hatte 4 when-Stellen
  (`effectiveTables`, `inputFilesByTable`, `inputTopology`,
  `inputPath`) — alle vier bekommen einen
  `is ImportInput.ResolvedSingleFile`-Branch. Single-File
  spiegelt das bestehende `SingleFile`-Verhalten
  (`single-file`-Topology, Pfad zur Datei).
  - Bonus: die vier `when`-Bloecke wurden in private
    Helfer-Methoden ausgelagert, weil
    `resolveInputContext` sonst die Detekt-
    CyclomaticComplexity-Schwelle (25) gerissen haette.
- `DataImportSchemaPreflight`/`SchemaRefImportPreflightAdapter`:
  haben `else -> input`, kein expliziter Branch noetig.
- 7 Test-Files
  (`DataImportRunnerCallbackTest`,
  `DataImportRunnerDirectoryTest`,
  `DataImportRunnerResumeTest`,
  `DataImportRunnerExitCodeTest`,
  `DataImportRunnerHappyPathTest`,
  `DataImportRunnerHappyPathTestPart2`,
  `DataImportRunnerTest`) bekommen mechanisch je einen
  `is ImportInput.ResolvedSingleFile`-Branch:
  `listOf(input.table)` oder `input.table`, je nach
  Kontext.

### 2.4 Tests

- `ImportInputResolverResolvedSingleFileTest` (streaming,
  1 Case): `ResolvedSingleFile` → 1-Element-`Seekable`-
  Liste mit korrektem Pfad + Schema.
- `StreamingPortsTest` (ports-write): 2 neue Cases fuer
  die neue Port-DTO (Felder + Default-`contentSha256`-
  null + Equality) — Kover ≥90% gehalten.

## 3. Definition of Done (verifiziert 2026-06-06)

| DoD-Item | Belegbefehl | Ergebnis |
| -------- | ----------- | -------- |
| `ImportInput.ResolvedSingleFile` exhaustive | `make docker-check` (Repo-weit) | BUILD SUCCESSFUL — alle 4 `ImportPreflightValidator`-when-Stellen + 7 Test-Files erweitert |
| Resolver liefert `Seekable` | `ImportInputResolverResolvedSingleFileTest` | gruen |
| Detekt-CyclomaticComplexity nicht ueber Limit | `make docker-check` | gruen (when-Helper extrahiert) |
| Kover ≥90% in `ports-write` | (Implizit in docker-check) | OK |

## 4. Bewusst NICHT in S5b

- **Kein CLI-Wiring** des Single-File-Pfads
  (`ParquetSingleFilePreflight` aus S4 → `ImportInput.ResolvedSingleFile`).
  Lebt in S6 zusammen mit dem Bundle-Wiring und der
  Phase-2-Target-JDBC-Fallback-Stelle (AP11 §5.3).
- **Kein `--no-checkpoint`-Wiring**. Lebt in S6
  (AP12 §4.2).
- **Kein Runner-Phase-2-Hook**. Lebt in S6
  (AP12 §5 Runner-Hook ist Pflicht-Aufgabe von S6).
- **Kein End-to-End-Konsum**. Lebt in S7.
- **Kein Checkpoint-Schreiben** des `contentSha256`
  (AP11 §6.4 `SingleFileCheckpointSpecifics`). Lebt in
  S8.

## 5. Folgeaufgaben

- **S6**: CLI-Wiring entscheidet pro `--source`-Pfad
  zwischen `ParquetBundleResolver` (Verzeichnis,
  S5a-Output → `ImportInput.ResolvedBundle`) und
  `ParquetSingleFilePreflight.phase1/phase2` (Datei,
  S4-Output → `ImportInput.ResolvedSingleFile`), reicht
  das Ergebnis an `StreamingImporter` und persistiert
  den Phase-1-`contentSha256` per Checkpoint (Bridge zu
  S8).
- **S7**: Stopgap-Branch im `StreamingImporter`
  (`is Seekable -> error("...")`) entfaellt;
  `TableImporter` versteht
  `ResolvedTableInput.Seekable`-Subtyp via
  `seekableReaderFactory`.
