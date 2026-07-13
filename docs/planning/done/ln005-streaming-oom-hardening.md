# LN-005: Streaming-Datenpfad — OOM-Härtung & Akzeptanz-Validierung

**Status**: ERLEDIGT + graduiert nach `done/` (2026-07-13) — R1 (`cdef4aba`), R2+R4 (`073afe08`),
Heap-Cap-Akzeptanztest + roadmap-✅ (`412f7afc`). Alle Akzeptanzkriterien erfüllt (Heap-Test grün
unter `-Xmx 256m`, kein OOM); CI grün. **Folge-Ticket:** `pipeline.parallelism`-Config-Wiring
([`open/pipeline-parallelism-config-unwired.md`](../open/pipeline-parallelism-config-unwired.md),
gleicher No-op-Befund wie `chunk_size`, aber im Scope des parallelen Datenpfads).
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

**Form (festgezurrt, Review 2026-07-13): Konstruktor-/Factory-Parameter — NICHT die
`streamTable`-Signatur.** Blast-Radius-Fund: `streamTable` hat **81 Call-Sites** und zwei Overloads
(4-Param + 5-Param `resumeMarker`); ein per-Call-`fetchSize` dort erzeugt Overload-Ambiguität (null
fünfter Parameter) und 38 chunkSize-Site-Edits. Stattdessen wird `fetchSize` beim **Bau des Readers**
gesetzt — nur **6 `dataReader()`-Call-Sites**, `streamTable` bleibt unangetastet:

- `DatabaseDriver.dataReader(fetchSize: Int? = null): DataReader` — optionaler Parameter, Default
  `null` = Dialekt-Konstante (rückwärtskompatibel; die 3 no-arg-Test-Calls brechen nicht).
- Jeder Reader-Konstruktor nimmt `fetchSizeOverride: Int? = null` und setzt
  `override val fetchSize = fetchSizeOverride ?: <Dialekt-Default>`. Wert **immutable pro Instanz**
  → parallel-sicher (jeder `dataReader(...)`-Aufruf liefert eine frische Instanz;
  `AbstractJdbcDataReader.kt:130` wendet ihn an).
- Nur **6 `dataReader()`-Call-Sites** (Produktion: `DataTransferRunner.kt:181,252`,
  `DataExportWiring.kt:129`; 3 Test — bleiben no-arg). Die 3 Produktionssites reichen den CLI-/Config-
  Wert durch.
- **Config-Key `pipeline.fetch_size` + Reparatur `pipeline.chunk_size` (Review-Entscheid „jetzt
  mitverdrahten").** Verifiziert: `pipeline.chunk_size` ist zwar spec-dokumentiert
  (`connection-config-spec.md:242`), aber im Runtime ein **stiller No-op** — `PipelineCheckpointResolver`
  liest nur `pipeline.checkpoint.*` (Test belegt: `pipeline.chunk_size` → `resolve()==null`); `chunkSize`
  kommt real nur aus `--chunk-size`. Dieser Slice verdrahtet beide Keys **echt**: neuer
  `PipelineTuningResolver` (Muster wie `PipelineCheckpointResolver`) liest `pipeline.chunk_size` +
  `pipeline.fetch_size` (Number, `>0`); Präzedenz **CLI-explizit > Config > Default** (die Options
  `--chunk-size`/`--fetch-size` werden nullbar, der Default `10_000` bzw. Dialekt-Default wandert in den
  Merge). `chunk_size` gilt für `export`/`import`/`transfer`, `fetch_size` für `export`/`transfer`.
- CLI-Flag `--fetch-size` **nur `data export`/`transfer`** (Validierung `> 0`; SQLite = Hint) →
  effektiver Wert (CLI/Config-Merge) → `DataExportOptions`/`DataTransferRequest`-Feld → die 3
  `dataReader(...)`-Call-Sites. `fetchSize` bleibt **nicht** in `PipelineConfig` (die speist die
  Streaming-Schleife mit dem bereits gebauten Reader) — es wird am Reader-Bau angewandt.
- **`data import` außen vor** (liest per `DataChunkReader`, `TableImporter.kt:12-13,43`); **Verify**
  (`data transfer --verify`) baut seinen Read-Back-Reader (Quelle+Ziel) ebenfalls über
  `dataReader(...)` → nutzt denselben Wert.

**Semantik-Argument**: `fetchSize` ist Connection-/Cursor-Tuning (wie viele Zeilen der JDBC-Cursor
vorablädt) — natürlicherweise einmal beim Reader-Bau gesetzt; `chunkSize` bleibt per-`streamTable`-Call
(variiert je Operation/Resume). Die beiden Ebenen bleiben sauber getrennt. *(Die zuerst erwogene
`ReadTuning`-am-`streamTable`-Variante wurde wegen des 81-Site-Blast-Radius + Overload-Ambiguität
verworfen — Review-Entscheid.)*

> **AE-1a (entschieden): kleiner ADR 0033.** `fetchSize` von „treiberintern, nicht user-tunable"
> (Stance in `PipelineConfig.kt:11-14`) zu **user-konfigurierbar via `--fetch-size`** (am Reader-Bau)
> ist eine bewusste Vertragsänderung — analog
> [`LN-009`](../../../spec/lastenheft-d-migrate.md#ln-009)/[`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013)
> → ADR 0030/0031. Der ADR hält Grund + die „am Reader-Bau, nicht in `PipelineConfig`"-Entscheidung fest.

### AE-2 — Parquet-Row-Group (R2)

`ExampleParquetWriter.builder(...)` (`ParquetChunkWriter.kt:78-85`) bekommt ein explizites
`.withRowGroupSize(rowGroupBytes)`. Der Wert ist ein **Konstruktor-Parameter** mit Default
**32 MiB** (`DEFAULT_ROW_GROUP_BYTES`, statt parquet-java-~128 MB), damit `parallelism × rowGroup`
bei realistischem `--parallel` in einem moderaten Heap-Budget bleibt. **Trade-off**: kleinere
Row-Groups kosten etwas Lese-/Kompressionseffizienz (kleinere Kompressionsfenster, mehr
Row-Group-Metadaten) — 32 MiB ist der bewusste Kompromiss zwischen Heap-Sicherheit unter `--parallel`
und Scan-Effizienz.

> **Status-Update 2026-07-13:** Der **Config-Key** `export.parquet.row_group_bytes` wurde — nachgezogen
> auf User-Wunsch (konsistent zum `pipeline.chunk_size`-Wiring) — **verdrahtet**: `ParquetExportConfigResolver`
> liest den Key (positive Ganzzahl; `≤ 0`/nicht-numerisch → Exit 7), `DataExportWiring` reicht den
> effektiven Wert (Config > Default 32 MiB) über `ParquetChunkWriterFactory` an `ParquetChunkWriter`.
> Kein CLI-Flag (Nischen-Detail, einmal pro Umgebung gesetzt). Der explizite Default bleibt der
> eigentliche OOM-Fix; der Config-Key ist die Tunability darüber.

### AE-3 — chunkFailures-Deckel (R4)

`chunkFailures` wächst **nur bei `--on-error log`** (beide Append-Sites mit `OnError.LOG` geguardet,
`TableImportLoopSupport.kt:245,284`; `skip` zeichnet nicht auf → Liste bleibt leer). Der `log`-Pfad
wird auf eine feste Obergrenze gedeckelt (erste N + Overflow-Zähler statt unbounded). `abort`
(Default) und `skip` bleiben unberührt — **kein** Semantik-Wechsel, nur ein Deckel. Der
Import-Report zeigt „N Fehler protokolliert (+ M weitere unterdrückt)".

---

## Phasen

- **Phase A (R1)** — konfigurierbarer `fetchSize` via `dataReader(fetchSize)`:
  `DatabaseDriver.dataReader(fetchSize: Int?)`-Default-Methode + 3 Reader-Konstruktoren
  (`fetchSizeOverride`) + 3 Driver-Overrides + `--fetch-size` (**nur export/transfer**) →
  `DataExportOptions`/`DataTransferRequest`-Feld → 3 Produktions-`dataReader()`-Sites
  (`DataTransferRunner:181,252`, `DataExportWiring:129`) + **ADR 0033**. Kein `PipelineConfig`-Feld,
  kein Config-Key (s. AE-1). `streamTable` unangetastet. TDD: Reader nutzt Override; Dialekt-Default
  bei `null`; Verify-Read-Back nutzt den Wert; `≤ 0` → Exit 2.
- **Phase B (R2)** — explizite Parquet-Row-Group-Größe (`rowGroupBytes`-Konstruktor-Param, Default
  32 MiB; Config-Key deferred, s. AE-2) + Test (winzige Größe → mehrere Row-Groups; Default → eine).
- **Phase C (R4)** — `chunkFailures`-Deckel im `log`-Pfad + Test (Overflow bei `log`; `abort`/`skip`
  unverändert).
- **Phase D (Akzeptanztest)** — **neues Schwester-Modul `test/perf-data-path`** <!-- d-check:ignore (neues Modul, entsteht in Phase D dieses Slices) --> (der DDL-fokussierte
  `test/perf-large-schema` passt vom Namen/den Deps nicht; das neue Spec braucht `streaming`/
  `application`): synthetischer lazy `DataReader` (siehe Test-Design) + heap-gedeckeltes Scale-Spec
  (perf-Tag, `HeapDumpOnOutOfMemoryError`, kleines `-Xmx`, kover minBound 0) für Export **und**
  Transfer; opt-in via `make docker-perf`.
- **Phase E (Doku)** — roadmap Zeile 638 `🚧¹ → ✅` (Fußnote auf „validiert" umschreiben);
  `cli-spec.md` `--fetch-size` (export/transfer); `connection-config-spec.md`
  `export.parquet.row_group_bytes` (R2); CHANGELOG `[Unreleased]`; `make docs-check`.

## Akzeptanzkriterien

- [ ] Heap-Cap-Datenpfad-Test streamt Export **und** Transfer unter kleinem `-Xmx` **ohne** OOM.
  **Dimensionierungsregel (gegen False-Green)**: das synthetische Gesamtvolumen muss `≥ 4× -Xmx`
  betragen — Zeilenbreite (gepaddete Strings) × N so gewählt, dass ein „hält-doch-alles"-Bug den Heap
  garantiert sprengt. Beispiel: `-Xmx 256 MB` ⇒ Nutzdaten ≥ 1 GB (nicht bloß 1 Mio. schmale Zeilen
  ≈ 100–200 MB, die unter 256 MB u. U. **nicht** OOMen und den Test false-green machen). Roter Lauf
  ⇒ HeapDump.
- [ ] `--fetch-size` wirkt end-to-end an **export/transfer** (Reader nutzt den Wert; Verify-Read-Back
  nutzt denselben), Default = Dialekt-Konstante; `≤ 0` → Exit 2; **kein** `--fetch-size` an `import`,
  kein Config-Key (CLI-only, s. AE-1).
- [ ] Parquet-Export nutzt die explizite Row-Group-Größe (Default 32 MiB, `rowGroupBytes`-Param;
  Config-Key deferred; Test belegt: winzige Größe → mehrere Row-Groups, Default → eine).
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
