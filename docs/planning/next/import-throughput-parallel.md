# Import-Durchsatz: paralleler Import (mehrere gleichzeitige Streams)

> Status: **Draft mit Scope** (nach next/ promotet 2026-06-26). Design-Spike erledigt +
> Schnitt-1 (schicht-paralleler Tabellen-Import) mit Phasen/Akzeptanz ausgearbeitet; aktiv
> erst bei Volumen-Trigger.
>
> Trigger: Abschluss des COPY-Fast-Path-Tickets
> ([`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md), 2026-06-25).
> Beide dort gelieferten Hebel — Schritt 0 (`reWriteBatchedInserts`) und der
> COPY-Bulk-Fast-Path — beschleunigen einen **einzelnen** Import-Stream
> (Protokoll-Effizienz pro Strom). Der parallele Import ist die dazu
> **orthogonale** Achse und wurde im Ticket bewusst ausgeklammert (Abschnitt
> „Orthogonale Achse: Parallelität (bewusst außerhalb dieses Tickets)").
>
> Aktivierungsbedingung: sobald Volumen-Migrationen den Durchsatz eines
> einzelnen Streams als Ziel überschreiten **und** die unten genannten
> Korrektheitsfragen für ein konkretes Szenario geklärt werden können. Dann
> wandert der Eintrag mit ausgearbeitetem Scope nach `../next/`.
>
> Status-Update 2026-06-26: **Design-Spike erledigt** (siehe Abschnitt unten) — die
> drei Korrektheitsfragen sind am Code beantwortet, ein gestufter Slice-Zuschnitt liegt
> vor (Schicht-paralleler Tabellen-Import zuerst, Chunk-Parallelität vertagt). Es fehlt
> nur noch der **Volumen-Trigger**; dann ist Schnitt (1) `next/`-fähig.

---

## Worum es geht

Statt eines Stroms mehrere **gleichzeitige** Verbindungen/Streams gegen die
Ziel-DB: eine je Tabelle, oder eine große Tabelle chunk-partitioniert über N
Streams. Diese Achse **multipliziert** sich mit der Protokoll-Effizienz aus dem
COPY-Ticket — beide zusammen ergeben den maximalen Import-Durchsatz.

## Offene Korrektheits-/Architektur-Fragen (vor jedem Scope zu klären)

- **FK-/Ladereihenfolge** über gleichzeitig geladene Tabellen (referenzielle
  Integrität). Der heutige Import respektiert eine topologische Tabellenreihenfolge;
  gleichzeitige Streams müssen entweder dieselbe Ordnung über Abhängigkeiten hinweg
  einhalten oder Constraints temporär aussetzen (`session_replication_role` /
  deferred FKs) und am Ende validieren.
- **Globaler `triggersDisabled`-Zustand + Pool-Dimensionierung.** Das Deaktivieren
  von Triggern (`ALTER TABLE … DISABLE TRIGGER USER`) ist heute pro Tabelle und
  läuft innerhalb der Session-Transaktion; bei N gleichzeitigen Sessions ist der
  Gültigkeitsbereich (Transaktion vs. Verbindung) und die Connection-Pool-Größe neu
  zu bewerten.
- **Decke ist die Ziel-Instanz.** Importer-seitige Parallelität ist durch die
  vertikale Kapazität der **einen** Ziel-PG-Instanz (CPU/IO/WAL) begrenzt — ab
  einem Punkt bringt ein weiterer Stream nichts mehr. Protokoll-Effizienz (das
  COPY-Ticket) senkt die Arbeit pro Zeile und bleibt darum auch unter dieser Decke
  wirksam; Parallelität skaliert nur bis zur Sättigung der Ziel-Instanz.

## Warum kein eigener Slice (jetzt)

Diese Fragen sind eigenständige Korrektheits-/Architektur-Themen und gehören
ausdrücklich **nicht** in den COPY-Fast-Path gemischt. Ein Scope ergibt erst
Sinn, wenn ein konkretes Volumen-Szenario die Mehrarbeit rechtfertigt und die
FK-/Trigger-/Sättigungs-Fragen für genau dieses Szenario beantwortet sind.

## Verwandte Tracker

- Quelle und Closure-Kontext:
  [`import-throughput-copy-path.md`](../done/import-throughput-copy-path.md)
  (Abschnitt „Orthogonale Achse: Parallelität").
- Komplementärer, von dieser Achse unabhängiger Speed-Hebel:
  [`import-throughput-binary-copy.md`](../open/import-throughput-binary-copy.md)
  (mehr Typen über COPY, statt mehr Streams).
- Mess-Substrat (Vorher/Nachher unter Caps): die 4c-Harness
  (`make sample-db-tpch-perf`,
  [`tpc-4c-volume-acceptance-slice.md`](../done/tpc-4c-volume-acceptance-slice.md)).

---

## Design-Spike (2026-06-26): Antworten auf die offenen Fragen

> Kein Code. Beantwortet die drei Korrektheits-/Architektur-Fragen am echten Code-Stand,
> als Vorbedingung für einen späteren Slice. Befunde direkt verifiziert.

### Heutiger Import (Ist-Stand)

- **Strikt sequenziell, ein Stream.** `TransferExecutor` iteriert Tabellen in einer nackten
  `for`-Schleife; keine Threads/Coroutines/Pools im Import-Pfad (`StreamingImporter`,
  `TableImporter`).
- **Topologische Tabellenordnung.** `TransferPreflightPlanner.planTables` → `topoSort` →
  `sortTablesByDependency` (Kahn, `TableDependencySort.kt`) sortiert Eltern vor Kinder aus den
  FK-Kanten des **Zielschemas**; Zyklen werden abgewiesen, Self-Edges gefiltert.
- **FK live erzwungen.** PG **verbietet** generisches FK-Abschalten:
  `PostgresDataWriter` wirft bei `disableFkChecks` `UnsupportedOperationException`
  („use schema ordering or DEFERRABLE constraints instead"). Die referenzielle Integrität
  hängt heute **vollständig an der topologischen Reihenfolge** + Per-Chunk-Commit.
- **Pro Tabelle eine Connection** (`pool.borrow()` in `*DataWriter`), Per-Chunk-Commit
  (`AbstractTableImportSession.commitChunk`). Hikari-Default `maximumPoolSize=10`; SQLite
  hart auf 1 (`HikariConnectionPoolFactory`).
- **Chunking** offset/limit-basiert (`JdbcChunkSequence`), Resume über **kumulativen**
  Per-Tabelle-Marker (`ResumeMarker`/`CheckpointManifest`) + sequenzielles Chunk-Skipping.

### Q1 — FK-/Ladereihenfolge über gleichzeitige Streams

**Antwort: Tabellen-granulare Parallelität in Abhängigkeits-Schichten ist FK-sicher — ohne
FK-Abschalten.** Kahn liefert die Tabellen bereits in „Wellen": alle aktuell in-degree-0-Knoten
bilden eine **Schicht (Antikette)**, die untereinander **keine** FK-Beziehung hat (sonst lägen
sie in verschiedenen Schichten). Damit:

- Schichten laufen **sequenziell** (Eltern-Schicht vollständig committed vor Kind-Schicht →
  Elternzeilen existieren → Kind-Inserts bestehen, FK **enforced**, kein PG-Workaround nötig).
- Tabellen **innerhalb** einer Schicht laufen **gleichzeitig** (keine gegenseitige FK).
- Kleine, lokale Erweiterung: `sortTablesByDependency` statt flacher Liste **Schichten**
  emittieren (Kahn-Runden gruppieren). Self-referenzielle Tabellen bleiben Single-Stream.

Alternativen (nicht nötig für den ersten Schnitt, dokumentiert): `DEFERRABLE INITIALLY DEFERRED`
oder `session_replication_role = replica` (PG, **verbindungs-scoped**, aber Superuser/Replikations-
Recht) würden auch Cross-Schicht-Parallelität erlauben — höheres Korrektheits-/Rechte-Risiko.

### Q2 — `triggersDisabled`-Scope + Pool-Dimensionierung

**Befund (Scope je Dialekt):**

| Dialekt | Mechanismus | Scope | Multi-Stream |
|---|---|---|---|
| PG | `ALTER TABLE … DISABLE TRIGGER USER` (`PostgresSchemaSync`, eigene Txn, sofort committed) | **katalog-global** (alle Verbindungen) | nur sicher bei **tabellen-exklusivem** Besitz |
| MySQL | `SET FOREIGN_KEY_CHECKS=0` | **Session** (eine Verbindung) | sicher |
| SQLite | `PRAGMA foreign_keys=OFF` | Verbindung | sicher (aber Pool=1 → ohnehin kein Parallel-Write) |

**Antwort:** Der PG-Trigger-Disable ist katalog-global, aber **pro Tabelle**. Bei
**tabellen-granularer** Parallelität besitzt genau **ein** Stream eine Tabelle → dessen
disable/enable betrifft nur diese Tabelle → **kein Konflikt**. Der Konflikt entsteht erst, wenn
**zwei Streams dieselbe Tabelle** anfassen (= Intra-Tabellen-/Chunk-Parallelität): dann müsste
der Trigger-Lifecycle **koordiniert** werden (einmal disable vor allen Streams, einmal enable
nach allen). → Spricht dafür, Chunk-Parallelität zu vertagen.

**Pool:** Parallelitätsgrad `P` braucht `≥ P` Connections + Reserve (Metadaten/Cleanup); Default 10
trägt kleine `P`, sonst konfigurierbar hochsetzen. **Nested-Borrow-Falle** beachten (ein Orchestrator
darf nicht innerhalb eines geborgten Borrows erneut borgen). SQLite bleibt `P=1` (Single-Writer).

### Q3 — Sättigungsdecke der Ziel-Instanz

**Antwort:** Importer-seitige Parallelität skaliert nur bis zur vertikalen Kapazität der **einen**
Ziel-Instanz (CPU/IO/WAL). `P` daher **konfigurierbar + gedeckelt** (Vorschlag Default
`min(Schichtbreite, Pool, kleiner Cap z. B. 4–8)`), und gegen die **4c-Harness**
(`make sample-db-tpch-perf`, Caps 2 CPU/4 GB aus [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md))
empirisch messen — jenseits der Sättigung bringt ein weiterer Stream nichts; die Protokoll-Effizienz
(COPY-Ticket) bleibt darunter wirksam.

### Ergebnis: gestufter Slice-Zuschnitt

1. **Schicht-paralleler Tabellen-Import (erster, sicherer Schnitt).** Kahn-Schichten; Tabellen je
   Schicht bis Grad `P` gleichzeitig; FK enforced (kein PG-Disable); PG-Trigger-Disable sicher,
   da Tabelle = ein Stream; Pool `≥ P`; SQLite ausgenommen; `P` gedeckelt + 4c-gemessen.
2. **Intra-Tabellen-/Chunk-Parallelität (vertagter Folge-Sub-Slice).** Durch das **kumulative**
   Resume-Marker-Modell + offset-Chunking **blockiert**: bräuchte PK-Range-/Keyset-Chunking +
   **Per-Stream**-Resume-State + koordinierten Trigger-Lifecycle. Eigener, größerer Slice.

Damit sind die drei Korrektheitsfragen beantwortet; der erste Schnitt (1) ist scope-reif und
**next/-fähig**, sobald ein konkretes Volumen-Szenario den Trigger setzt. (2) bleibt ausdrücklich
nachgelagert.

## Scope Schnitt-1 (geplant, next/): schicht-paralleler Tabellen-Import

> Aktiv erst bei Volumen-Trigger. Phasen + Akzeptanzkriterien für den ersten, FK-sicheren Schnitt.

### Phasen

- **P1 — Schicht-Zerlegung.** `sortTablesByDependency` (Kahn) so erweitern, dass es statt einer
  flachen Liste **Abhängigkeits-Schichten** liefert (Knoten, die in derselben Kahn-Runde in-degree-0
  werden). Rein additiv; bestehende flache Reihenfolge = Konkatenation der Schichten. **DoD:**
  Unit-Test, dass jede Schicht eine Antikette ist (keine schichtinterne FK) und Schichtreihenfolge
  topologisch.
- **P2 — Parallel-Orchestrierung.** Im Transfer-Runner Schichten **sequenziell**, Tabellen je
  Schicht **gleichzeitig** bis Grad `P` (bounded). Pool `≥ P` (Hikari `maximumPoolSize`); SQLite
  hart `P=1`. Nested-Borrow-Falle meiden (Orchestrator borgt nicht innerhalb eines Borrows). FK
  bleibt **enforced** — kein PG-`DISABLE`; PG-Trigger-Disable bleibt sicher (Tabelle = ein Stream).
  **DoD:** Fehler in einem Stream bricht die Schicht sauber ab (kein Teilstand über Schichtgrenze).
- **P3 — Deckelung + Messung.** `P` konfigurierbar (CLI/`.d-migrate.yaml`), Default
  `min(Schichtbreite, Pool, kleiner Cap)`. Gegen die 4c-Harness (`make sample-db-tpch-perf`,
  Caps aus [ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md)) Vorher/Nachher
  messen. **DoD:** dokumentierter Speedup auf Mehr-Tabellen-Workload; jenseits Sättigung kein
  Regress.

### Akzeptanzkriterien

- Referenzielle Integrität ohne FK-Abschalten erhalten (PG): Cross-Dialect-Round-Trip identisch
  zur sequenziellen Baseline (`schema compare == baseline`).
- Trigger-Zustand korrekt: keine Trigger feuern während des Loads, alle danach wieder aktiv —
  auch bei Fehlerabbruch in einem Stream.
- SQLite unverändert sequenziell (`P=1`), keine Regression.
- Messbarer Durchsatz-Gewinn auf einem Mehr-Tabellen-Workload unter den normierten Mess-Caps
  ([ADR 0018](../../adr/0018-normalized-perf-measurement-environment.md)); `P` gedeckelt, kein
  Über-Sättigungs-Regress.

### Nachgelagert (eigener Slice, nicht Schnitt-1)

Intra-Tabellen-/Chunk-Parallelität — blockiert durch das kumulative Resume-Marker-Modell +
offset-Chunking; bräuchte PK-Range-/Keyset-Chunking + Per-Stream-Resume-State.
