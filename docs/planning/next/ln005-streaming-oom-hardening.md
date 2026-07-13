# LN-005: Streaming-Datenpfad — OOM-Härtung & Akzeptanz-Validierung

**Status**: Entwurf (2026-07-13) — Scope user-abgestimmt, noch kein Implementierungs-Commit.
**Ziel**: Das Lastenheft-Akzeptanzkriterium [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005)
(„sehr große Datenmengen >10 TB ohne OutOfMemory verarbeiten") **beweisbar** machen und die
verbleibenden Scale-Tuning-Kanten schließen — ohne den (bereits streaming-sicheren) Datenpfad
umzubauen.
**Vorbedingungen**: keine offenen Plan-/ADR-Abhängigkeiten. Baut auf dem ausgelieferten
chunk-weisen Pull-Streaming (Basis seit 0.3.0) und dem parallelen Datenpfad
([`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007)/[`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008),
[ADR 0032](../../adr/0032-paralleler-datenpfad-tabellen-partitionen.md)) auf.

---

## Kontext / Ist-Stand (verifiziert)

Der Roadmap-Eintrag [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) „Streaming-Pipeline
**Optimierung** (kein OOM bei >10 TB)" steht auf `🚧`. Die Fußnote hält fest: chunk-weises
Pull-Streaming (`TableExporter`, `chunkSize=10_000`) + Resume/Checkpoint sind ausgeliefert, aber
das „>10 TB ohne OOM"-Kriterium ist **nicht validiert** — der einzige Heap-Cap-/HeapDump-Test
(`test/perf-large-schema`) prüft die **DDL-Render**-Pipeline, nicht den Datenpfad.

Eine Code-Analyse des gesamten Export-/Import-/Transfer-/Verify-Pfads (2026-07-13) bestätigt:
**Der Datenpfad ist strukturell bounded — es gibt keine Stelle, die eine ganze Tabelle in den
Speicher lädt.** Belege:

- **JDBC-Read-Kern lazy**: `JdbcChunkSequence` füllt pro Iteration eine `ArrayList(chunkSize)`,
  emittiert den Chunk und verwirft ihn — kein `toList()` über das ResultSet.
  `AbstractJdbcDataReader` setzt `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY` + `stmt.fetchSize`
  und `setAutoCommit(false)` wo nötig.
- **fetchSize/Cursor pro Dialekt korrekt**: PostgreSQL `autoCommit=false` + `fetchSize=1000`
  (pgjdbc-Cursor-Rezept); MySQL `useCursorFetch=true` (JDBC-URL) + realer `fetchSize=1000`;
  SQLite lazy in-process. Batch-Insert-Tuning (`reWriteBatchedInserts`/`rewriteBatchedStatements`)
  ist gesetzt.
- **Alle Format-Writer/Reader streamen** (JSON/YAML/CSV/Parquet) — kein Document-Tree im RAM.
- **Transfer** committet per Chunk; **Verify** hält je Tabelle nur einen additiven `BigInteger`
  (O(1)); **Checkpoint** speichert eine Marker-Position je Tabelle (O(Tabellenzahl), nicht O(Zeilen)).
- **Paralleler Pfad** ist auf `parallelism × chunkSize` Zeilen gedeckelt (fixed ThreadPool,
  keine entkoppelnde Queue → das Nachladen des nächsten Chunks IST die Backpressure).

**Folgerung**: [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) ist damit primär eine **Akzeptanz-/Validierungslücke**, kein Rewrite. Die
Analyse fand zusätzlich einige echte, aber nachrangige Scale-Tuning-Kanten (unten R1/R2/R4).

### Befunde

| # | Schwere | Befund | Beleg | Slice? |
|---|---------|--------|-------|--------|
| **R1** | MEDIUM | `fetchSize` ist hart auf `1000` pro Dialekt verdrahtet, **nicht** per CLI/Config wählbar; `PipelineConfig` schließt ihn bewusst aus. Bei sehr breiten Zeilen möchte man `fetchSize < chunkSize` bzw. gezielt tunen können. | `PostgresDataReader.kt:31`, `MysqlDataReader.kt:37`, `SqliteDataReader.kt:28`; `PipelineConfig.kt:11-14` | ✅ in Scope |
| **R2** | MEDIUM | Parquet-Writer setzt **keine** explizite Row-Group-Größe → parquet-java-Default (~128 MB Block) pro Writer; im parallelen File-per-Table-Export = `parallelism × ~128 MB`. | `ParquetChunkWriter.kt:78-85` (`ExampleParquetWriter.builder(...)` ohne `.withRowGroupSize`) | ✅ in Scope |
| **R4** | LOW | `chunkFailures`-Liste wächst unbounded **nur bei `--on-error log`** (beide Append-Sites mit `OnError.LOG` geguardet; `skip` → `CONTINUE` ohne Aufzeichnung, Liste bleibt leer). Ein Eintrag je fehlgeschlagenem Chunk → `totalRows/chunkSize` im pathologischen Fall. | `TableImportLoopSupport.kt:22,245,284` | ✅ in Scope |
| R3 | MEDIUM | Einzelne Riesen-LOB-Zelle wird via `rs.getObject` voll materialisiert (inhärent JDBC — Chunking schützt nicht vor einer einzelnen Multi-GB-Zelle). | `JdbcChunkSequence.kt:156` | ❌ dokumentierte Grenze |
| R5 | LOW | Paralleler Executor submittet alle Work-Units auf einmal (bounded durch Tabellenzahl, kein Zeilendaten-Risiko). | `ParallelWorkExecutor.kt:64` | ❌ vernachlässigbar |

---

## Scope (user-abgestimmt 2026-07-13)

**Voller Härtungs-Slice**: Akzeptanztest (Kern) **+ R1 + R2 + R4**.

1. **Heap-gedeckelter Datenpfad-Akzeptanztest** (schließt die `🚧¹`): beweist bounded memory
   über einen **synthetischen lazy `DataReader`**, der N Mio. Zeilen generiert (kein Backing-DB),
   durch den echten `StreamingExporter`/Transfer in einen verwerfenden Sink, unter kleinem `-Xmx`
   mit `HeapDumpOnOutOfMemoryError`.
2. **R1** — `fetchSize` konfigurierbar (`--fetch-size` + `pipeline.fetch_size`), **nur an
   `data export`/`transfer`** (Import liest aus Format-Dateien, nicht per JDBC-`DataReader`;
   `--verify` liest Quelle+Ziel per `DataReader` zurück und nutzt denselben Wert).
3. **R2** — explizite (kleinere/konfigurierbare) Parquet-Row-Group-Größe.
4. **R4** — `chunkFailures`-Liste **im `log`-Pfad** deckeln (reiner Deckel; `skip`-Semantik unverändert).

R3 (LOB) bleibt eine **dokumentierte Grenze**; R5 ist Nicht-Scope.

---

## Architektur-Entscheidungen (Review-Fokus)

### AE-1 — fetchSize-Threading (R1)

`fetchSize` ist heute ein `protected open val` auf `AbstractJdbcDataReader`, pro Dialekt
überschrieben (`PostgresDataReader.kt:31`, `MysqlDataReader.kt:37`, `SqliteDataReader.kt:28`).

**Signatur-Form (festgezurrt): `ReadTuning`-Parameterobjekt.** `DataReader` hat bereits zwei
`streamTable`-Overloads (4-Parameter + 5-Parameter mit `resumeMarker: ResumeMarker?`, Default-Impl).
Ein bloßes `fetchSize: Int? = null` an den 4-Parameter-Overload macht **beide** zu
5-Positions-Signaturen mit nullbarem fünften Parameter → ein positionales `null` wird **ambig**, und
der Resume-Overload (genau der Transfer-/Checkpoint-Pfad) bräuchte `fetchSize` ohnehin auch. Daher:
`chunkSize` + `fetchSize` werden in ein kleines, erweiterbares
`ReadTuning(chunkSize: Int = 10_000, fetchSize: Int? = null)` (neuer Wert-Typ in `ports-read`)
gebündelt; beide Overloads nehmen `tuning: ReadTuning` (4-Param mit Default; 5-Param + `resumeMarker`).
Der abstrakte Reader nutzt `effectiveFetchSize = tuning.fetchSize ?: fetchSize` (Dialekt-Default
bleibt bei `null`). *(Die leichtere Variante „`fetchSize` an beide Overloads hinter `resumeMarker`,
verschiedene Arität" wurde erwogen — `ReadTuning` gewinnt, weil es die Overload-Ambiguität
grundsätzlich beseitigt und die nächste Tuning-Größe nicht denselben Sprawl auslöst.)*

- `PipelineConfig` bekommt `fetchSize: Int? = null` (null = Dialekt-Default). Der Kommentar
  „`fetchSize` … gehört nicht hierher" (`PipelineConfig.kt:11-14`) wird **umgekehrt** (→ ADR 0033).
- CLI-Flag `--fetch-size` **nur an `data export`/`transfer`** (Validierung `> 0`; SQLite = Hint,
  dokumentieren); Config-Key `pipeline.fetch_size` (Muster wie `pipeline.chunk_size`).
- **`data import` bleibt außen vor**: der Import liest aus Format-Dateien über `DataChunkReader`/
  `DataChunkReaderFactory` (`TableImporter.kt:12-13,43`), nie über einen JDBC-`DataReader` — ein
  `--fetch-size` dort wäre tote Oberfläche.
- **Verify nutzt denselben Wert**: `data transfer --verify` liest Quelle+Ziel per `DataReader`
  zurück (`verify/TransferVerifier.kt`), also greift `fetchSize` dort real; der Transfer-`--fetch-size`
  fließt in den Read-Back.
- **Betroffen (verifizierte `streamTable`-Caller)**: `TableExporter` (Export), `TransferExecutor`
  (Transfer), `TransferVerifier` (Verify-Read-Back) — **nicht** `TableImporter`. Dazu: Port
  `DataReader` (beide Overloads → `ReadTuning`), `AbstractJdbcDataReader`, die drei Reader (nur
  Doc/Default), CLI-Commands (export/transfer) + Wiring, `PipelineConfig`, Config-Parser, neuer
  `ReadTuning`-Typ in `ports-read`.

> **AE-1a (entschieden): kleiner ADR 0033.** Die Umkehr der dokumentierten Stance („fetchSize ist
> treiberintern, nicht user-tunable", `PipelineConfig.kt:11-14`) + die neue Config-Oberfläche + das
> `ReadTuning`-Objekt sind eine bewusste Vertragsänderung — analog
> [`LN-009`](../../../spec/lastenheft-d-migrate.md#ln-009)/[`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013)
> → ADR 0030/0031. Ein Kommentar allein verlöre die Begründung der alten Stance.

### AE-2 — Parquet-Row-Group (R2)

`ExampleParquetWriter.builder(...)` (`ParquetChunkWriter.kt:78-85`) bekommt ein explizites
`.withRowGroupSize(...)`. Default **32 MB** (statt parquet-java-~128 MB), damit `parallelism × rowGroup`
bei realistischem `--parallel` in einem moderaten Heap-Budget bleibt; optional per Config
überschreibbar. Config-Key **`export.parquet.row_group_bytes`** (nicht unter `pipeline.` — Row-Group
ist ein Format-/Export-Detail; die Config hat bereits eine `export:`-Sektion und das
formatspezifische Muster `import.csv.*`; `pipeline.fetch_size` passt dagegen zum
`pipeline.chunk_size`-Muster). **Trade-off**: kleinere Row-Groups kosten etwas
Lese-/Kompressionseffizienz (kleinere Kompressionsfenster, mehr Row-Group-Metadaten) — 32 MB ist der
bewusste Kompromiss zwischen Heap-Sicherheit unter `--parallel` und Scan-Effizienz.

### AE-3 — chunkFailures-Deckel (R4)

`chunkFailures` wächst **nur bei `--on-error log`** (beide Append-Sites mit `OnError.LOG` geguardet,
`TableImportLoopSupport.kt:245,284`; `skip` zeichnet nicht auf → Liste bleibt leer). Der `log`-Pfad
wird auf eine feste Obergrenze gedeckelt (erste N + Overflow-Zähler statt unbounded). `abort`
(Default) und `skip` bleiben unberührt — **kein** Semantik-Wechsel, nur ein Deckel. Der
Import-Report zeigt „N Fehler protokolliert (+ M weitere unterdrückt)".

---

## Phasen

- **Phase A (R1)** — `ReadTuning` + konfigurierbarer `fetchSize`: neuer `ReadTuning`-Typ (`ports-read`)
  + beide `streamTable`-Overloads auf `ReadTuning` + `AbstractJdbcDataReader.effectiveFetchSize` +
  `PipelineConfig.fetchSize` + `--fetch-size` (**nur export/transfer**) + `pipeline.fetch_size` +
  Kommentar-/Spec-Update + **ADR 0033**. TDD: Reader wendet Override an; Dialekt-Default greift bei
  `null`; Verify-Read-Back nutzt den Transfer-Wert; CLI→PipelineConfig-Wiring; `≤ 0` → Exit 2.
- **Phase B (R2)** — explizite Parquet-Row-Group-Größe (Default 32 MB, `export.parquet.row_group_bytes`)
  + Test (Writer setzt die konfigurierte Größe; Default gedeckelt).
- **Phase C (R4)** — `chunkFailures`-Deckel im `log`-Pfad + Test (Overflow bei `log`; `abort`/`skip`
  unverändert).
- **Phase D (Akzeptanztest)** — **neues Schwester-Modul `test/perf-data-path`** <!-- d-check:ignore (neues Modul, entsteht in Phase D dieses Slices) --> (der DDL-fokussierte
  `test/perf-large-schema` passt vom Namen/den Deps nicht; das neue Spec braucht `streaming`/
  `application`): synthetischer lazy `DataReader` (siehe Test-Design) + heap-gedeckeltes Scale-Spec
  (perf-Tag, `HeapDumpOnOutOfMemoryError`, kleines `-Xmx`, kover minBound 0) für Export **und**
  Transfer; opt-in via `make docker-perf`.
- **Phase E (Doku)** — roadmap Zeile 638 `🚧¹ → ✅` (Fußnote auf „validiert" umschreiben);
  `cli-spec.md` `--fetch-size` (export/transfer); `connection-config-spec.md` `pipeline.fetch_size` +
  `export.parquet.row_group_bytes`; CHANGELOG `[Unreleased]`; `make docs-check`.

## Akzeptanzkriterien

- [ ] Heap-Cap-Datenpfad-Test streamt Export **und** Transfer unter kleinem `-Xmx` **ohne** OOM.
  **Dimensionierungsregel (gegen False-Green)**: das synthetische Gesamtvolumen muss `≥ 4× -Xmx`
  betragen — Zeilenbreite (gepaddete Strings) × N so gewählt, dass ein „hält-doch-alles"-Bug den Heap
  garantiert sprengt. Beispiel: `-Xmx 256 MB` ⇒ Nutzdaten ≥ 1 GB (nicht bloß 1 Mio. schmale Zeilen
  ≈ 100–200 MB, die unter 256 MB u. U. **nicht** OOMen und den Test false-green machen). Roter Lauf
  ⇒ HeapDump.
- [ ] `--fetch-size`/`pipeline.fetch_size` wirkt end-to-end an **export/transfer** (Reader nutzt den
  Wert; Verify-Read-Back nutzt denselben), Default = Dialekt-Konstante; `≤ 0` → Exit 2; **kein**
  `--fetch-size` an `import`.
- [ ] Parquet-Export nutzt die explizite Row-Group-Größe (Default 32 MB, via
  `export.parquet.row_group_bytes` überschreibbar; Test belegt den Builder-Wert).
- [ ] `chunkFailures` ist bei `--on-error log` gedeckelt (Test belegt Overflow; `abort`/`skip`
  unverändert).
- [ ] roadmap [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) (Zeile 638) → `✅`; ADR 0033
  angelegt; `make docs-check` grün; betroffene Modul-Tests grün.

## Test-Design (synthetischer lazy DataReader)

Ein In-Memory-`DataReader` (im Testmodul), der eine `ChunkSequence` liefert, die N Zeilen **lazy**
generiert (Iterator, der pro `nextChunk()` einen frischen `ArrayList(chunkSize)` mit synthetischen
Werten füllt — **nie** alle N gleichzeitig hält). Damit lässt sich der echte
`StreamingExporter`/`TransferExecutor` gegen einen zählenden/verwerfenden Sink treiben. Läuft der
Test unter kleinem `-Xmx` durch, ist die Streaming-Naht bewiesen bounded; hält irgendeine Schicht
(Reader-Emit, Format-Writer-Buffer, Aggregation) die ganze Menge, kippt er in OOM + HeapDump.
Kein echter >10-TB-Datenbestand nötig — die Aussage ist „Speicher ∝ chunkSize, nicht ∝ Zeilenzahl".

**Dimensionierung (kritisch)**: Zeilenbreite (gepaddete Strings) × N so wählen, dass das synthetische
Gesamtvolumen `≥ 4× -Xmx` liegt — sonst könnte ein „hält alles"-Bug unter dem Heap-Cap durchrutschen
(false-green). Der Sink zählt nur (Row-Count/Checksum) und hält keine Zeilen.

---

## Nicht-Scope

- **R3 (LOB-Streaming)** — eine einzelne Multi-GB-Zelle über `getBinaryStream`/`getCharacterStream`
  statt `getObject` zu streamen ist inhärent JDBC-tief und dialektspezifisch; bleibt als
  **dokumentierte Grenze** (Chunking schützt nicht vor einer Riesen-Einzelzelle). Eigener Trigger
  in `open/`, falls je gefordert.
- **R5** — Executor-„submit-all" ist bounded durch Tabellenzahl, kein Zeilendaten-Risiko.
- **`--on-error skip`-Aufzeichnung** — `skip` zeichnet heute bewusst **nicht** auf (nur `log`); der
  R4-Deckel ändert daran nichts. Ein „`skip` protokolliert auch" wäre eine Semantik-Änderung, kein
  Deckel — separater Scope, falls je gefordert.
- **Echter >10-TB-DB-Test** — nicht CI-tragbar; die synthetische Heap-Cap-Aussage ist die
  belastbare, reproduzierbare Validierung.
- Inkrementelle/Delta-Migration ([`LN-006`](../../../spec/lastenheft-d-migrate.md#ln-006)) —
  separates Lastenheft-Item.

## Referenzen

- [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) — Lastenheft-Anforderung.
- [`roadmap.md`](../in-progress/roadmap.md) Zeile 638 (`🚧¹`) — der zu schließende Eintrag.
- [`test/perf-large-schema`](../../../test/perf-large-schema/build.gradle.kts) — Heap-Cap-Test-Vorlage
  (`HeapDumpOnOutOfMemoryError`, perf-Tag, kover minBound 0).
- [ADR 0032](../../adr/0032-paralleler-datenpfad-tabellen-partitionen.md) — paralleler Datenpfad
  (`parallelism` in `PipelineConfig`).
- [ADR 0004](../../adr/0004-documentation-and-planning-structure.md) — Lebenszyklus `next/`→`in-progress/`.
