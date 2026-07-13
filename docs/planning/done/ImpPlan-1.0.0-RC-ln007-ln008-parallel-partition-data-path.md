# ImpPlan 1.0.0-RC — LN-007 + LN-008: Paralleler Datenpfad (Tabellen + Partitionen)

> Status: **DONE / graduiert** (2026-07-12; [ADR 0032](../../adr/0032-paralleler-datenpfad-tabellen-partitionen.md)).
> Phase A (Scheduler/Executor/Flag), B (Transfer parallel + Partitions-Fan-out), C (Export +
> Import parallel), D (Live/Spec/Doku/Gates) abgeschlossen; beide roadmap-Einträge → ✅.
> Deckt zusammen
> [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007) („Parallele
> Tabellenverarbeitung") **und** [`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008)
> („Partitionierte Tabellen: paralleler Export/Import"). User-abgestimmt 2026-07-12:
> **breiter Scope** (genereller Worker-Pool über alle Tabellen — Tabellen-Parallelität eingeschlossen),
> **alle drei Datenpfade** (export/import/transfer), **Engine = bounded ThreadPool**
> (kein `kotlinx-coroutines`-Dep in den Daten-/Streaming-Modulen).

## Kontext / Ist-Stand (verifiziert 2026-07-12)

- **Alle drei Datenpfade sind sequenzielle `for`-Schleifen**, keine Parallelität:
  - Export: `StreamingExporter` (`FilePerTable`-Loop `discoveredTables.withIndex()`)
    → `TableExporter.export` → `DataReader.streamTable`.
  - Import: `StreamingImporter` (Loop `discoveredInputs.withIndex()`) → `TableImporter.import`.
  - Transfer: `TransferExecutor` (Loop `for (table in context.tables)`) → `transferTable`
    (`reader.streamTable` + `writer.openTable`), Reihenfolge aus
    `TransferPreflightPlanner.planTables` → `topoSort` (linear, FK-Ordnung).
- **`kotlinx-coroutines` ist KEIN Dep** der streaming/driver/application-Datenmodule
  (nur transitiv im MCP über ktor). Server-Mode nutzt bereits `ThreadPoolExecutor`/
  `Semaphore`/`ExecutorService` (`server/application/job/BoundedAsyncJobExecutor`) —
  bewährtes In-Repo-Muster, das wir schlanker spiegeln.
- **Partitionierte PG-Parents** werden **als transparenter Parent** verarbeitet;
  Kind-Partitionen sind via `relispartition`-Guard aus `listTableRefs`
  (`PostgresTableMetadataQueries`) gefiltert. Kind-Namen+Grenzen liegen als
  `TableDefinition.partitioning: PartitionConfig` vor — im **Transfer** bereits im
  `srcSchema`/`tgtSchema` geladen (ungenutzt), im **Export** nicht (TableLister-only).
- **Ports nehmen beliebige Tabellennamen**: `DataReader.streamTable(pool, table, …)`
  und `DataWriter.openTable(pool, table, …)` — eine Kind-Partition streamt/schreibt
  sich per Namen **ohne Port-Änderung**.
- **Pools**: PG/MySQL `maximumPoolSize=10` (Nebenläufigkeit ok); **SQLite=1**
  (`HikariConnectionPoolFactory`) — paralleles Borgen blockiert bis Timeout.
- **`CancellationToken`** ist durch alle drei Executor durchgereicht.
- **`sortTablesByDependency`** (`core/dependency`, Kahn) liefert linearen `sorted` +
  `circularEdges`. **`PipelineConfig`** trägt heute nur `chunkSize`+`checkpoint`.

## Kern-Korrektheits-Einsichten

1. **Kind-Partitionen eines Parents sind Geschwister** (keine FK-Kanten untereinander)
   → parallel FK-sicher, ohne die äußere Tabellen-Ordnung anzutasten (der Partitions-Kern des Slice).
2. **FK-sichere Tabellen-Parallelität braucht Topo-LAYER statt linearer Ordnung**
   (Tabellen-Parallelität): Tabellen ohne offene Abhängigkeit bilden eine Ebene, die parallel läuft;
   zwischen Ebenen eine **Barriere**. Kahn nach Ebenen.
3. **Ports sind zustandslos** (Session/Sequence + eigene Connection pro Aufruf) →
   N Worker-Threads dürfen dieselbe `reader`/`writer`-Instanz nutzen; Aggregation
   (Summaries, `onTableTransferred`) passiert **nach** `invokeAll` im Haupt-Thread
   (kein geteilter mutabler Zustand während der Parallelphase).

## Architektur-Entscheidungen

Die Entscheidungen (D1–D6) liegen im **[ADR 0032](../../adr/0032-paralleler-datenpfad-tabellen-partitionen.md)**
(Work-Unit-Modell, Topo-Layer-Barriere, bounded ThreadPool, `--parallel`-Flag +
SQLite-Clamp, Write-Ziel bei Partitions-Expansion, `--resume`-Ausschluss). Der ImpPlan
setzt sie phasenweise um; die Phasen-DoDs unten sind die prüfbaren Kriterien.

## Phasen

### Phase A — Scheduler + Executor + Flag-Plumbing (verhaltensneutral)

`sortTablesIntoLayers` in `core/dependency`; `ParallelWorkExecutor` (fixed pool,
`invokeAll`, First-Failure, `degree==1`-Direktpfad); `--parallel`-Flag auf allen drei
Commands → Wiring-Options → Request; `parallelism` in `PipelineConfig`; SQLite-Clamp +
`N>=1`-Validierung + `--resume`-Konflikt. **Noch kein Pfad parallelisiert** (degree
fließt, wird mit 1 verdrahtet).

**DoD:**
- [x] `sortTablesIntoLayers` liefert für einen Diamond-FK-Graph korrekte Ebenen
      (Wurzeln in Ebene 0, jede Tabelle erst nach allen FK-Zielen); Zyklus →
      `circularEdges` nicht leer. Unit-Tests decken: lineare Kette, Diamond, Insel
      (kein FK), Selbst-Referenz, Zyklus.
- [x] `ParallelWorkExecutor`: (a) höchstens `degree` Units gleichzeitig aktiv
      (Semaphore-/Latch-Beweis im Test), (b) erste Worker-Exception wird propagiert
      und verbleibende Units werden nicht mehr gestartet, (c) `CancellationToken`
      zwischen Units respektiert, (d) `degree==1` läuft ohne Pool (Direktaufruf,
      im Test über Thread-Identität belegt).
- [x] `--parallel N` an `data export`/`import`/`transfer`; `N<1` → Exit 2;
      `--parallel N>1` + `--resume` → Exit 2 (beide mit klarer Meldung).
- [x] SQLite-Clamp: `--parallel 4` mit SQLite-Quelle/Ziel → effektiv 1 + stderr-Hinweis.
- [x] `PipelineConfig.parallelism` (Default 1, `require(>=1)`); Doku-Note aktualisiert.
- [x] Voller Build grün (`build koverVerify`), **Verhalten unverändert** (alle
      Bestands-Datenpfad-Tests bleiben grün, degree=1).

### Phase B — Transfer parallel (LN-007-Kern + Partitions-Expansion)

`TransferPreflightPlanner` → Layer statt linear; `TransferExecutor` fährt Ebenen
sequenziell, Units je Ebene durch `ParallelWorkExecutor`; partitionierte Parents
expandiert (Quell-Kind → Ziel-Kind, D5). Thread-sichere Summen-Aggregation nach
`invokeAll`.

**DoD:**
- [x] Transfer mit FK-Kette A→B→C hält die Ebenen-Barriere ein (C nie vor B, B nie
      vor A) auch bei `--parallel 4` — Test über aufgezeichnete Start/Ende-Reihenfolge.
- [x] Partitionierter Parent mit N Kindern → N parallele Kind-Transfers (Quell-Kind →
      Ziel-Kind); Parent selbst wird **nicht** transferiert (keine Doppelzählung);
      Summe der Kind-Row-Counts == Parent-Row-Count.
- [x] Ziel nicht namensgleich partitioniert → Fallback auf 1 Parent-Unit (kein Expand).
- [x] Fehler in einem Kind/Tabelle → Exit 5, Geschwister-Units des Laufs gecancelt,
      keine hängenden Threads.
- [x] `--parallel 1` erzeugt byte-/count-identisches Ergebnis wie vor dem Slice.
- [x] Modul-`check` grün (`hexagon:application` + betroffene Adapter).

### Phase C — Export + Import parallel

Export: `StreamingExporter` baut eine flache Unit-Menge (Tabellen + Kind-Partitionen →
Datei pro Kind), durch den Pool; Partitions-Metadaten via SchemaReader-Aufruf **nur
wenn** eine Tabelle partitioniert ist. Import: `StreamingImporter` baut Layer aus
Ziel-Schema-FKs, expandiert Kinder (Kind-Datei → Kind-Tabelle), durch den Pool; ohne
FK-Info → sequenzieller Fallback.

**DoD:**
- [x] Export `--parallel 4`: unabhängige Tabellen nebenläufig; partitionierter Parent →
      eine Datei **pro Kind** (Name = Kind-Tabelle); nicht-partitionierte Tabellen
      unverändert (eine Datei); kein SchemaReader-Aufruf, wenn keine Tabelle
      partitioniert ist.
- [x] Import `--parallel 4`: Kind-Dateien → Kind-Tabellen nebenläufig; FK-Layer-Barriere
      eingehalten; ohne ableitbare FK-Info fällt der Lauf auf sequenziell zurück
      (dokumentiert, kein stiller Falsch-Parallelismus).
- [x] Export→Import-Round-Trip mit `--parallel` reproduziert die Row-Counts exakt.
- [x] `--parallel 1` byte-/count-identisch zum Bestand für beide Pfade.
- [x] Modul-`check` grün (streaming + application).

### Phase D — Live-Härtung + Spec/Doku/ADR + Gates

**DoD:**
- [x] Live (sample-db, PG-partitioniert, Pagila `payment`): PG→PG-Transfer
      `--parallel 4` → `compare` IDENTICAL, identische Row-Counts, Kind-Partitionen
      nachweislich parallel (Log/Thread-Namen); Export `--parallel` → Datei pro Kind.
- [x] Neuer sample-db-Smoke-Target + in `make/sample-db.mk` verdrahtet.
- [x] [ADR 0032](../../adr/0032-paralleler-datenpfad-tabellen-partitionen.md) accepted +
      in `docs/adr/README.md` indexiert.
- [x] cli-spec `--parallel`-Zeilen (export/import/transfer); **beide roadmap-Einträge
      → ✅** (+ Fußnote); dieser ImpPlan nach `done/` graduiert.
- [x] `make docs-check` grün (Kover-Ledger inkl. etwaiger neuer Excludes dokumentiert);
      voller Docker-Build grün.

## Gesamt-Akzeptanz (Slice-DoD)

- [x] `data export`/`import`/`transfer --parallel N` parallelisiert unabhängige
      Tabellen **und** Kind-Partitionen eines Parents, FK-sicher (beide Anforderungen).
- [x] Default (`--parallel 1`) ist voll rückwärtskompatibel (keine Verhaltensänderung).
- [x] SQLite degradiert sicher auf sequenziell; Cross-Dialect-Partitionen fallen auf
      Parent-Transfer zurück.
- [x] Kein `kotlinx-coroutines`-Dep in Daten-/Streaming-/Driver-Modulen.
- [x] beide roadmap-Einträge auf ✅; ADR 0032 + ImpPlan in `done/`.

## Nicht-Scope

- **Parallele `--resume`-Wiederaufnahme** (D6) — Folgearbeit (Checkpoint-Store
  thread-safe / per-Kind-Manifest).
- **Bound-Filter/WHERE-Range auf `streamTable`** um einen nicht-partitionierten Parent
  künstlich zu zerteilen — nicht nötig (echte Partitionen sind reale Tabellen).
- **Cross-Dialect-Partitions-Parallelität** (Ziel nicht namensgleich partitioniert) —
  fällt sauber auf transparenten Parent-Transfer zurück (D5).
- **Auto-Degree (CPU-basiert)** — Default bleibt 1 (opt-in, konservativ).

## Referenzen

- [`LN-007`](../../../spec/lastenheft-d-migrate.md#ln-007),
  [`LN-008`](../../../spec/lastenheft-d-migrate.md#ln-008),
  [`spec/lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md) 5.1.2.
- [`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005) (Streaming >10 TB — der
  Pool teilt sich Chunk-Streaming, kein Widerspruch),
  [`LN-013`](../../../spec/lastenheft-d-migrate.md#ln-013) (`--atomic` ⊥ `--resume`
  als Präzedenz für D6).
- [`spec/cli-spec.md`](../../../spec/cli-spec.md) — `data export`/`import`/`transfer`.
- Partitions-Modell: `hexagon/core/src/main/kotlin/dev/dmigrate/core/model/PartitionConfig.kt` (done-Slices
  `docs/planning/done/partition-hierarchy-reconstruction.md` +
  `cross-dialect-partitioning.md`).
- ADR für den Parallel-Vertrag → **ADR 0032** (Phase D).
