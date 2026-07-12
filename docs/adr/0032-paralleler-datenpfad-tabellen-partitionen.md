---
status: accepted
date: 2026-07-12
decision-makers: pt9912
consulted: spec/lastenheft-d-migrate.md, docs/planning/in-progress/ImpPlan-1.0.0-RC-ln007-ln008-parallel-partition-data-path.md
informed: hexagon/core, adapters/driven/streaming, hexagon/application, hexagon/ports-write, docs/planning/in-progress/roadmap.md
---

# Paralleler Datenpfad: Tabellen- und Partitions-Parallelität (`--parallel`, LN-007 + LN-008)

> **Status: accepted (2026-07-12).** `data export`/`import`/`transfer --parallel N`
> verarbeitet unabhängige Tabellen — und die Kind-Partitionen eines partitionierten
> Parents — nebenläufig über einen begrenzten Worker-Pool, FK-sicher per
> Topo-Layer-Barriere. Deckt [`LN-007`](../../spec/lastenheft-d-migrate.md#ln-007)
> („Parallele Tabellenverarbeitung") und [`LN-008`](../../spec/lastenheft-d-migrate.md#ln-008)
> („Partitionierte Tabellen: paralleler Export/Import") gemeinsam.

## Kontext und Problemstellung

[`LN-007`](../../spec/lastenheft-d-migrate.md#ln-007) fordert parallele
Tabellenverarbeitung, [`LN-008`](../../spec/lastenheft-d-migrate.md#ln-008)
zusätzlich „Export/Import pro Partition" und „parallele Verarbeitung verschiedener
Partitionen". Der Datenpfad ist heute in allen drei Richtungen (export/import/transfer)
eine **sequenzielle `for`-Schleife** über die Tabellen; partitionierte PG-Parents
werden als **ein transparenter Parent-SELECT** verarbeitet (Kind-Partitionen aus der
Tabellenliste gefiltert).

Randbedingungen (verifiziert): die Ports (`DataReader.streamTable`,
`DataWriter.openTable`) nehmen **beliebige Tabellennamen** und sind zustandslos
(Session/Sequence + eigene Connection pro Aufruf); eine Kind-Partition ist eine reale
Tabelle. PG/MySQL-Pools erlauben Nebenläufigkeit (`maximumPoolSize=10`), **SQLite=1**
nicht. `kotlinx-coroutines` ist kein Dependency der Datenmodule; der Server-Mode nutzt
bereits `ThreadPoolExecutor` für Job-Nebenläufigkeit.

## Entscheidung

### D1 — Work-Unit-Modell

Eine **Work-Unit** ist ein zu lesender/schreibender Tabellenname. Ein partitionierter
Parent **expandiert** zu seinen Kind-Partitions-Units (schema-qualifiziert); der Parent
wird dann **nicht** als Ganzes verarbeitet (keine Doppelzählung). Nicht-partitioniert =
1 Unit. Ohne Partitions-Metadaten oder bei leerer `partitions`-Liste bleibt es die
1 Parent-Unit (rückwärtskompatibel, transparenter Parent-SELECT).

### D2 — Topo-Layer-Scheduler (FK-Barriere)

FK-sichere Tabellen-Parallelität braucht **Topo-Ebenen statt linearer Ordnung**: neue
reine Funktion `sortTablesIntoLayers(tables, edges): TableLayerResult` (`core/dependency`,
Kahn nach Ebenen; `layers: List<List<String>>` + `circularEdges`). Tabellen einer Ebene
haben keine FK-Kante untereinander → parallel; **zwischen Ebenen eine Barriere**.
FK-Zyklus → Exit 3 (wie bisher). **Kind-Partitionen eines Parents sind Geschwister**
(keine FK-Kanten untereinander) → innerhalb der Ebene ihres Parents frei parallelisierbar
(der Partitions-Kern). Transfer/Import nutzen Layer; **Export nutzt keine Layer** (read-only,
keine FK-Ordnung — eine flache Unit-Menge).

### D3 — Engine: begrenzter fixer ThreadPool

Ein schlanker `ParallelWorkExecutor`: `newFixedThreadPool(degree)` mit benannten
Daemon-Threads, `invokeAll` pro Ebene, **First-Failure** propagiert (verbleibende Units
gecancelt, Pool heruntergefahren), respektiert den `CancellationToken`. Kein
`kotlinx-coroutines`-Dependency — JDBC ist blockierendes I/O, ein Thread-Pool ist der
natürliche Fit (`Dispatchers.IO` wäre auch nur ein Pool). Bei `degree == 1` **exakt
sequenzielles Verhalten** (kein Pool, Direktaufruf) → kein Regressionsrisiko im Default.

### D4 — `--parallel N` (opt-in, Default 1)

Flag auf `data export`, `data import`, `data transfer`. `N >= 1` (sonst Exit 2).
**SQLite-Clamp:** ist Quell- ODER Ziel-Dialekt SQLite, wird `N` auf 1 geklemmt
(Pool-Size 1) mit stderr-Hinweis — sichere Degradierung statt Fehler. `parallelism`
wird in `PipelineConfig` aufgenommen (die frühere „bewusst nicht enthalten"-Note wird
aktualisiert: Parallelität ist mit diesem Slice in Scope, Retry weiterhin nicht).

### D5 — Write-Ziel bei Partitions-Expansion

Transfer: Quell-Kind `C` → **Ziel-Kind `C` direkt** (jeder Worker öffnet seine eigene
Kind-Tabelle; Truncate-pro-Kind ist natürlich korrekt, keine Parent-Contention).
Voraussetzung: das Ziel trägt Kind `C` namensgleich (PG→PG-Round-Trip: ja). Ist das
Ziel **nicht** namensgleich partitioniert (cross-dialect), wird **nicht** expandiert →
1 transparente Parent-Unit (Bestand). Export: Kind `C` → eigene Datei (Name =
Kind-Tabelle), „Export pro Partition". Import: Kind-Datei `C` → Kind-Tabelle `C`.

**Dialekt-Gate (Review-Härtung):** Der Fan-out gilt nur, wenn der Dialekt eine
Kind-Partition als **eigenständige adressierbare Relation** führt
(`DialectCapabilities.partitionChildrenAreTables`) — **PostgreSQL: ja**
(`SELECT … FROM kind`), **MySQL: nein** (Kinder sind Sub-Objekte, nur über
`FROM parent PARTITION (p)` erreichbar). Ohne diese Fähigkeit (MySQL, SQLite) wird
**nicht** expandiert → transparente Parent-Unit. Sonst schlüge `streamTable(pool, "p0")`
auf MySQL fehl (Exit 5), wo der sequenzielle Parent-Pfad gelingt.

### D6 — `--parallel N` (N>1) ist inkompatibel mit `--resume` UND `--atomic` (Exit 2)

Der Checkpoint-Store schreibt ein Lauf-Manifest; paralleles Schreiben aus N Threads ist
ein Race → `--parallel N>1` ⊥ `--resume`. Ebenso ist `--parallel N>1` ⊥ `--atomic`
(Review-Härtung): der Executor unterbricht bei Fehler zwar die Worker (`shutdownNow` +
`awaitTermination`), aber ein Straggler könnte noch committen, während die
Kompensation truncatet → „alle oder keine" wäre racy. Beide Kombinationen werden im
**Preflight** abgelehnt (Exit 2, klare Meldung) — analog
[ADR 0031](0031-atomic-clean-load-rollback.md). Parallele Wiederaufnahme /
atomare Parallel-Läufe sind **Folgearbeit**, kein stiller Stopgap.

## Konsequenzen

- **Durchsatz** bei breiten Schemata (viele unabhängige Tabellen) und bei großen
  partitionierten Tabellen (Kinder parallel), ohne die Streaming-Eigenschaft
  ([`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005)) zu brechen — jeder Worker
  streamt weiterhin chunk-weise.
- **Zustandslose Ports** werden von N Threads geteilt; Ergebnis-Aggregation (Summaries,
  `onTableTransferred`) passiert **nach** `invokeAll` im Haupt-Thread → kein geteilter
  mutabler Zustand während der Parallelphase.
- **SQLite** bleibt effektiv sequenziell (Clamp) — korrekt, da SQLite keine
  deklarative Partitionierung hat und der Pool 1 ist.
- **Pool-Größe-Kopplung (Review-Befund, offene Härtung).** `--parallel N` ist heute
  **nicht** an die Connection-Pool-Größe (Default `maximumPoolSize = 10`) geklemmt. Ein
  Worker hält seine Connection für die ganze Tabelle (Transfer: Quelle **und** Ziel
  gleichzeitig); `N > Pool-Größe` führt daher zu Connection-Acquisition-Timeouts statt
  einer sauberen Meldung. Empfehlung dokumentiert (`--parallel ≤ Pool-Größe`, Flag-Help +
  `cli-spec.md`); ein pool-größen-bewusster Auto-Clamp (Port muss die Max-Größe
  exponieren) ist **Folgearbeit**.
- **Nicht-Scope:** parallele `--resume`-Wiederaufnahme (D6); Bound-Filter/WHERE-Range,
  um einen nicht-partitionierten Parent künstlich zu zerteilen (echte Partitionen sind
  reale Tabellen); Cross-Dialect-Partitions-Parallelität (fällt auf Parent-Transfer
  zurück, D5); Auto-Degree (Default 1).

## Referenzen

- [`LN-007`](../../spec/lastenheft-d-migrate.md#ln-007),
  [`LN-008`](../../spec/lastenheft-d-migrate.md#ln-008), LF 5.1.2.
- [`LN-005`](../../spec/lastenheft-d-migrate.md#ln-005) (Streaming),
  [ADR 0031](0031-atomic-clean-load-rollback.md) (`--atomic` ⊥ `--resume`, Präzedenz D6).
- Partitions-Modell: [ADR 0019](0019-partition-hierarchy-structured-representation.md),
  [ADR 0020](0020-cross-dialect-partitioning-mysql.md).
- ImpPlan: [`ImpPlan-1.0.0-RC-ln007-ln008-parallel-partition-data-path.md`](../planning/done/ImpPlan-1.0.0-RC-ln007-ln008-parallel-partition-data-path.md).
