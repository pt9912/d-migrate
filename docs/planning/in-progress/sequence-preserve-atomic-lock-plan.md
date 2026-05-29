# Implementierungsplan: Atomare Sequence-Preserve Probe + Restore unter Lock

> Status: Draft (2026-05-29)
> Workstream: E.3 Folge-Slice für `preserveCurrentValue`-Atomicity
> Vorarbeit:
> - `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md` §3.2
>   und §9 (Out-of-Scope), §10 Risiken
> - `docs/planning/in-progress/ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md`
>   §6 Risiken Nr. 1

## 1. Ausgangslage

Die 0.9.7-Erstscheibe und der SQLite-Folge-Slice implementieren
`preserveCurrentValue` als zweistufigen Ablauf:

1. **Probe (Read)** — `SequencePreserveStage.run` ruft pro Kandidaten-Op
   einen dialect-spezifischen Probe-Adapter auf, der den aktuellen
   `last_value`/`next_value` liest. Die Connection wird sofort
   wieder freigegeben.
2. **Render + Execute (Write)** — Renderer hängt einen
   `AlterSequenceCurrentValue`-Follow-up an, der zur Execute-Zeit den
   geprobten Wert per `setval`/`UPDATE` zurückschreibt.

Zwischen Probe-Read und Restore-Write liegt eine offene
Transaktionsgrenze: andere App-Sitzungen können in dieser Lücke
`nextval`/`dmg_nextval` aufrufen und die Sequenz weiterdrehen. Der
spätere `UPDATE` setzt sie dann auf einen überholten Wert zurück;
beim nächsten Insert vergibt die App bereits genutzte IDs.

Mitigation in den vorhandenen Slices: dokumentiertes Maintenance-Fenster
("Schreibverkehr vor `--execute` stoppen"). Der Plan-Doc
`ImpPlan-0.9.7-sequence-preserve-current-value.md` §3.2 markiert die
atomare Variante explizit als Out-of-Scope und §9 listet sie als
Folge-Thema. Dieses Dokument schließt diese Lücke und definiert den
atomaren Execute-Time-Pfad.

## 2. Zielbild

1. Probe, die eigentliche sequenzverändernde Operation und Restore laufen
   in **einer Transaktion** auf derselben JDBC-Connection. Die Sequenz ist
   während dieser Transaktion exklusiv für d-migrate sichtbar; parallele
   `nextval`/`dmg_nextval`-Aufrufe der App warten oder schlagen mit einem
   dialect-spezifischen Lock-Timeout fehl.
2. Multi-Sequence-Atomicity ist Pflicht für einen Planlauf: Alle
   Preserve-Kandidaten eines Planes werden in genau einem Connection-/
   Transaktionsrahmen verarbeitet. Bei Fehler/Timeout gilt all-or-none:
   entweder alle Sequenzzustände wurden restauriert oder kein einziger.
3. `d-migrate` hält bei mehreren Sequenzen eine deterministische
   Lock-Reihenfolge (`SequenceObjectRef.name`, optional schema-qualified)
   ein, um Deadlocks zwischen parallelen Migrationen zu vermeiden.
4. Lock-Acquisition hat ein konfigurierbares Timeout; bei Überschreitung
   surfaced d-migrate `SEQUENCE_PRESERVE_LOCK_TIMEOUT` (Blocker) statt
   stillem Hang.
5. Die Lock-Strategie ist pro Dialekt distinkt, aber teilt eine
   gemeinsame Stage-/Runner-Abstraktion.

## 3. In-/Out-of-Scope

### 3.1 In Scope

- Dialect-spezifische "atomic-lock-probe-execute-restore"-Executoren, die
  Lock, Probe, geschützte Hauptoperation und Restore in einer Transaktion
  ausführen.
- Deterministisches Multi-Sequence-Order/Locking (name-sorted) pro Plan,
  inkl. all-or-nothing Verhalten.
- Stage-/Runner-Anpassung: aus zwei separaten Phasen (Probe → Render →
  Execute) wird ein Execute-Time-Runner, der Lock, Probe, geschützte
  Hauptoperation und Restore in einer Transaktion ausführt.
- Lock-Timeout-Konfiguration (Per-Connection-Setting; Default 5 s).
- Timeout-Set/Restore als Teil des gleichen Connectionscopes, damit keine
  Nebeneffekte auf nachgelagerte Statements im Pool entstehen.
- Neuer Blocker-Code `SEQUENCE_PRESERVE_LOCK_TIMEOUT` und
  klassifikation auf `MANUAL_ACTION_REQUIRED`.
- Neuer Blocker-Code `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`, wenn Dialekt oder
  Operationstyp die geforderte Execute-Time-Atomarität nicht garantieren kann.
- Tests gegen Live-Container für jeden Dialekt mit echtem
  concurrent-writer-Pattern.

### 3.2 Out of Scope

- Globaler Schema-Lock (SQLite `BEGIN EXCLUSIVE`-Pattern auf die
  ganze Datenbank) — bricht alle App-Reader, nicht nur Sequence-
  Schreiber.
- App-side Backpressure / Retry-Hinweise — Verantwortung der
  konsumierenden App.
- Multi-Connection-Distributed-Lock (z. B. via PG advisory_lock auf
  cross-database-Ebene) — nur für Single-Process-Migrationen relevant
  und damit hier nicht angegangen.

## 4. Dialect-Strategien

### 4.0 Lock-Matrix (Umsetzungspflichtig)

| Dialekt | Lock-Objekt | Lock-Modus / Scope | Timeout-Quelle | Blockiert |
|---|---|---|---|---|
| PostgreSQL | Sequenzrelation | `LOCK TABLE <quoted_seq_ref> IN ACCESS EXCLUSIVE MODE` | `SET LOCAL lock_timeout` | `nextval` / `dmg_nextval` |
| MySQL | `dmg_sequences`-Zeile | `FOR UPDATE` | `SET SESSION innodb_lock_wait_timeout` | `dmg_nextval`-Update |
| SQLite | komplette DB | `BEGIN IMMEDIATE` / `RESERVED`-Lock | `PRAGMA busy_timeout` | `INSERT`/`UPDATE` im Triggerpfad |

### 4.1 PostgreSQL

```sql
BEGIN;
SET LOCAL lock_timeout = '5s';
LOCK TABLE <quoted_seq_ref> IN ACCESS EXCLUSIVE MODE; -- (1) Lock
SELECT last_value, is_called FROM <quoted_seq_ref>;    -- (2) Probe
-- Geschützte sequenzverändernde Statements auf derselben Connection ausführen.
-- Renderer komponiert SQL aus Probe-Ergebnis
SELECT setval(<regclass_seq_ref>, <last_value>, <is_called>); -- (3) Restore
COMMIT;
```

- `LOCK TABLE ... ACCESS EXCLUSIVE` blockt jeden `SELECT
  nextval(...)`-Aufruf aus anderen Sessions, weil `nextval` einen
  impliziten `ROW EXCLUSIVE`-Lock auf die Sequenzrelation hält
  (PG-Doku: 13.3 "Explicit Locking", Tab. "Conflicting Lock Modes").
  Renderer verwenden einen einzigen schemaqualifizierten Sequence-Ref-Vertrag:
  `quoted_seq_ref` für Relation-SQL und `regclass_seq_ref` für `setval`.
  Beide werden aus demselben `SequenceObjectRef` abgeleitet, damit Lock,
  Probe und Restore dieselbe Relation treffen.
- `lock_timeout = '5s'` wird per `SET LOCAL lock_timeout` am Anfang
  der Transaktion gesetzt; Timeout surfaced als `SQLSTATE 55P03`
  und mappt auf `SEQUENCE_PRESERVE_LOCK_TIMEOUT`.
- Die konkrete Konfliktauflösung von `nextval` gegenüber
  `LOCK TABLE ... ACCESS EXCLUSIVE` ist über integrierte
  Concurrent-Writer-Tests pro PostgreSQL-Version zu validieren.

### 4.2 MySQL

```sql
SET @dmg_prev_lock_wait_timeout = @@SESSION.innodb_lock_wait_timeout;
SET SESSION innodb_lock_wait_timeout = 5;
START TRANSACTION;
SELECT `next_value`, `managed_by`, `format_version`
FROM `dmg_sequences` WHERE `name` = '<key>'
FOR UPDATE;                                          -- (1) Probe + Lock
-- Geschützte sequenzverändernde Statements auf derselben Connection ausführen.
-- Renderer komponiert UPDATE
UPDATE `dmg_sequences` SET `next_value` = <value>
WHERE `name` = '<key>'
  AND `managed_by` IN (...) AND `format_version` IN (...); -- (2) Restore
COMMIT;
SET SESSION innodb_lock_wait_timeout = @dmg_prev_lock_wait_timeout;
```

- `SELECT … FOR UPDATE` belegt einen Row-Lock auf die
  `dmg_sequences`-Zeile; die App-seitige `dmg_nextval`-Routine modifiziert
  dieselbe Zeile und blockt deshalb sauber bis zum Commit.
- `innodb_lock_wait_timeout = 5` per `SET innodb_lock_wait_timeout`
  am Connection-Anfang; Lock-Timeout surfaced als MySQL-Errorcode
  1205 (`ER_LOCK_WAIT_TIMEOUT`).

- `SET SESSION innodb_lock_wait_timeout` wird ausschließlich im Scope der
  dedizierten Verbindung gesetzt; alter Wert wird vor Ausführung gesichert
  und nach Abschluss wiederhergestellt.

### 4.3 SQLite

```sql
-- Executor liest vorherigen PRAGMA busy_timeout-Wert.
PRAGMA busy_timeout = 5000;
BEGIN IMMEDIATE;
SELECT "next_value", "managed_by", "format_version"
FROM "dmg_sequences" WHERE "name" = '<key>';         -- (1) Probe
-- Geschützte sequenzverändernde Statements auf derselben Connection ausführen.
-- Renderer komponiert UPDATE
UPDATE "dmg_sequences" SET "next_value" = <value>
WHERE "name" = '<key>';                              -- (2) Restore
COMMIT;
-- finally: PRAGMA busy_timeout = <previous_busy_timeout>;
```

- `BEGIN IMMEDIATE` erwirbt sofort einen `RESERVED`-Lock auf die
  Datenbank; spätere `INSERT`s der App (die durch den
  `dmg_nextval`-Trigger über `dmg_sequences` gehen) blocken auf
  `SQLITE_BUSY`, bis der Commit den Lock freigibt.
- `SQLite` kennt keine Row-Level-Locks; die DB-weite Sperre ist die
  einzige Option, die `RESERVED`-Stufe blockt aber nur Schreiber, nicht
  Reader — App-Selects bleiben funktional.
- `busy_timeout = 5000` per `PRAGMA busy_timeout` am Connection-Anfang;
  überschreiten surfaced als SQLite-Errorcode 5 (`SQLITE_BUSY`).
- `PRAGMA busy_timeout` wird ebenfalls im dedizierten Connection-Scope gesetzt
  und im `finally` auf den vorherigen Wert zurückgesetzt.

## 5. Umsetzung in Phasen

### Phase A — Vertragsdefinition + Lock-Strategie-Matrix

- Plan-Doc dokumentiert pro Dialekt die exakte Lock-Stufe, die Timeout-
  Quelle und das Mapping auf `SEQUENCE_PRESERVE_LOCK_TIMEOUT`.
- Neue Diagnostic-Codes im `PlannerBlockerClassifier`.
- Pflichtmatrix pro Dialekt und Operationstyp:
  `supportsAtomicPreserve`, `supportsAtomicPreserveAllInPlan` und
  `transactionalProtectedSequenceOperations`.

**DoD A**

- [ ] Per-Dialekt-Lock-Matrix dokumentiert.
- [ ] Klassifier-Mapping für neue Codes registriert.
- [ ] Operationstyp-Matrix dokumentiert und mit Blocker-Pfad verbunden.
- [ ] Carve-Outs (kein cross-DB Lock, kein App-side Retry) dokumentiert.

### Phase B — Execute-Port + Batch-Vertrag

- Neuer Execute-Port in `hexagon:ports-execute` (bewusst nicht in
  `hexagon:ports-read`):

  ```kotlin
  interface AtomicSequencePreserveExecutor {
      fun execute(
          connection: Connection,
          batch: AtomicSequencePreserveBatch,
          lockTimeoutMillis: Long,
          executeProtectedOperations: (
              Connection,
              List<ProtectedOperationId>,
          ) -> AtomicProtectedExecutionResult,
      ): AtomicSequencePreserveResult
  }

  data class AtomicSequencePreserveBatch(
      val requests: List<AtomicSequencePreserveRequest>,
      val protectedOperationIds: List<ProtectedOperationId>,
      val internalFollowUpIds: List<String>,
  )

  @JvmInline
  value class ProtectedOperationId(val value: String)
  
  data class AtomicSequencePreserveRequest(
      val sequenceRef: SequenceObjectRef,
      val renderRestore: (SequenceCurrentValueProbeResult.Read) -> List<String>,
  )
  ```

  - Executor erwartet eine dedizierte JDBC-Connection (einziger Owner), die
    nicht in einer fremden Transaktion läuft.
  - `executeProtectedOperations` führt die eigentlichen sequenzverändernden
    Statements auf derselben Verbindung aus und läuft nach Lock+Probe, aber
    vor dem Restore.
  - `AtomicProtectedExecutionResult` ist eine neue runner-interne
    Zusammenfassung für die geschützten Statements; Exceptions propagieren in
    `AtomicSequencePreserveResult.Failed`.
  - `renderRestore` wird mit dem Probe-Ergebnis pro Sequenz aufgerufen und gibt
    die SQL-Statements für den Restore zurück.
  - Der Executor wickelt `BEGIN ...; Lock+Probe; executeProtectedOperations;
    renderRestore+Restore; COMMIT;` atomar ab und rollt bei Fehler zurück.
  - `AtomicSequencePreserveResult` ist eine sealed class
  (`Applied`/`NotFound`/`LockTimeout`/`Failed`) und löst den heutigen
  Doppelpfad (Probe-Result → Renderer-Output → Executor) durch eine
  Ergebnisklasse ab.
  - `Applied` ist als `List<SequenceObjectRef>` der betroffenen Sequenzen modelliert.
  - `NotFound` und `LockTimeout` können jeweils eine Teilmenge statt der
    ganzen Batch markieren; in beiden Fällen wurde die Batch nicht committed.
  - `Failed` trägt die auslösende Sequenz plus Ursache.
  - Für MySQL/SQLite werden Timeout-/Busy-Settings nach Ausführung im
    `finally` auf Voreinstellung zurückgesetzt.

**DoD B**

- [ ] Execute-Port, Batch-Typ und Result-Klassen existieren in
      `hexagon:ports-execute`.
- [ ] PG-/MySQL-/SQLite-Executoren implementiert.
- [ ] Executor-Tests mit echten Live-Containern (Lock-Race-Reproduktion) für
      Single-Seq und Multi-Seq Batch inkl. Timeout-Leckageprüfung.

### Phase C — Stage-/Runner-Refactor: atomare Execute-Time-Orchestrierung

- `SequencePreserveStage` führt keine Datenbank-Schreiboperation aus. Sie
  identifiziert Preserve-Kandidaten, baut `AtomicSequencePreserveRequest`s
  und markiert die betroffenen Plan-Segmente für den Atomic-Runner.
- Die Render-Pipeline erzeugt neben dem heutigen SQL-String eine
  runner-interne Segmentliste:
  `PlainSqlSegment` für normale Statements und `AtomicPreserveSegment` für
  die geschützte Gruppe aus Sequenzoperationen und internen Restore-Follow-ups.
- Der Execute-Runner nutzt für `--execute` die Segmentliste statt den
  kombinierten SQL-String. `PlainSqlSegment`s laufen wie bisher; ein
  `AtomicPreserveSegment` ruft den neuen Executor mit allen Kandidaten in
  stabiler Reihenfolge auf (Plan-Atomarität).
- SQL-Artefakte für `--plan-only`, Reports und Rollback bleiben weiterhin aus
  dem Renderer ableitbar. Die Segmentliste ist Runner-Metadaten und wird nicht
  als neue öffentliche Plan-Dateiform eingeführt.
- Der Restore-Renderer bleibt pro Dialekt verantwortlich: PG rendert
  `setval`, MySQL/SQLite rendern `UPDATE`.
- `AlterSequenceCurrentValue`-Follow-up bleibt im augmentierten Plan als
  deklaratives Audit-Artefakt. Der Core-`DiffOperation`-Typ erhält kein
  neues `executionMode`-Feld; die Zuordnung zu `AtomicPreserveSegment` liegt
  in einem runner-internen Metadata-Index anhand der Operation-ID.
- Interne Restore-Follow-ups werden im Live-Execute-Pfad nicht als
  Standalone-SQL gerendert und nicht vor dem Commit als `alreadyApplied`
  markiert.
- Nach erfolgreichem Commit schreibt der Runner den Ausführungsstatus in das
  normale Execution-Result; bei Rollback bleibt der Plan wiederholbar.

**DoD C**

- [ ] Stage markiert Kandidaten und erzeugt Requests ohne DB-Schreibzugriff.
- [ ] Render-Pipeline erzeugt runner-interne `ExecutableSegment`s zusätzlich
      zum heutigen SQL-Artefakt.
- [ ] Execute-Runner ruft Executor atomar auf und führt die geschützten
      Sequenzoperationen zwischen Probe und Restore auf derselben Connection aus.
- [ ] Augmentierter Plan enthält den Follow-up weiterhin (für Audit /
      Plan-Artefakt), ohne neue Felder am Core-`DiffOperation`-Typ.
- [ ] Execution-Engine rendert interne Follow-ups nicht als Standalone-SQL.

### Phase D — Multi-Sequence-Atomicity

- Bei mehreren Preserve-Kandidaten in einem Plan: alle Probes +
  Restores in einer einzelnen Transaktion bündeln.
- Lock-Reihenfolge deterministisch über `SequenceObjectRef.name`
  sortieren (ggf. zusätzlich schema), um Deadlock-Diamanten zwischen
  parallelen Migrationen auszuschließen.
- Kein Silent-Degrade: wenn ein Dialekt die Batch-Modus-Eigenschaft
  `supportsAtomicPreserveAllInPlan` nicht unterstützt oder ein geschützter
  Operationstyp nicht transaktional ausführbar ist, blockt der Lauf mit
  `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`.

**DoD D**

- [ ] Multi-Sequence-Transaktion in den Executor-Implementierungen
      wirklich atomar (Commit vs. Rollback all-or-nothing).
- [ ] Deadlock-Test mit zwei parallelen `schema migrate`-Aufrufen
      über dieselben Sequenzen.
- [ ] Deterministische Lock-Sortierung (Schema+Name) wird in CI getestet.
- [ ] Fehlender Batch-/Operation-Support führt zu Blocker statt Warn-Fallback.

### Phase E — Capability-Flags + Docs

- Neue `SequenceCapability.supportsAtomicPreserve` (Default `false`).
- Neue `SequenceCapability.supportsAtomicPreserveAllInPlan` (Default `false`)
  für Batch-Atomarität über alle Preserve-Kandidaten.
- Neue Operation-Capability-Matrix
  `SequenceCapability.transactionalProtectedSequenceOperations` (Default leer)
  für Operationstypen, die innerhalb des Atomic-Runners keine impliziten
  Commits auslösen dürfen.
- KDoc-Update auf `SequenceCurrentValueProbe` und
  `SequencePreserveStage` mit Hinweis auf den atomaren Pfad.
- User-Guide-Eintrag: "preserveCurrentValue ist seit 0.X.Y atomar
  unter Lock; keine Maintenance-Fenster mehr nötig."

**DoD E**

- [ ] Capability-Flags in `SequenceCapabilityDefaults` pro Dialekt
      gesetzt.
- [ ] User-Guide-Eintrag.
- [ ] CHANGELOG-Eintrag.

## 6. Risiken

1. **Lock-Eskalation bricht Reader**: SQLite's
   `BEGIN IMMEDIATE`-Pattern hält den `RESERVED`-Lock auf die ganze
   DB. Längere Renderer-Sequenzen verlängern das Fenster, in dem
   App-Schreiber blocken. Mitigation: Lock-Timeout strikt
   (Default 5 s), `executeProtectedOperations` und `renderRestore` dürfen
   keinen Netzwerk-I/O außerhalb der Datenbankverbindung machen.
2. **Lock-Timeout in Production**: bei stark beanspruchten DBs kann
   selbst der kurze atomare Pfad zu Timeouts führen. Mitigation:
   `SEQUENCE_PRESERVE_LOCK_TIMEOUT`-Blocker statt stiller Reset; der
   Operator entscheidet, ob er das Maintenance-Fenster auf dem
   alten, nicht-atomaren Pfad nimmt.
3. **MySQL ohne kompatibles `dmg_sequences`-Update-Fenster**: bestehende
   `dmg_nextval`-Routine muss kompatibel mit `SELECT ... FOR UPDATE`
   sein und denselben Schlüsselpfad verwenden. Mitigation: Regressionstest
   gegen produktive Trigger-Routine aus 0.9.4.
4. **PG-Sequenz-Lock semantik**: `LOCK TABLE seq IN ACCESS EXCLUSIVE
   MODE` wird als harte Erwartung verwendet und muss per
   Live-Integrationstest für jede Ziel-PG-Version validiert werden.
5. **Multi-Sequence-Deadlock**: ohne Sortierung der Lock-Reihenfolge
   können zwei parallele Migrationen sich gegenseitig blockieren.
   Mitigation: sortierte Reihenfolge + Timeout-Behandlung.
6. **Session-Timeout-Leak**: `SET SESSION` / `PRAGMA` kann Verbindungen im Pool
   kontaminieren; Mitigation: alter Wert lesen, auf Scope begrenzen,
   danach restore im finally-Block.
7. **Re-Entrant-Risiko bei Retry**: Crash nach erfolgreichem Commit, aber vor
   Persistierung des Execution-Results kann zu Wiederanlauf-Sonderfällen führen.
   Mitigation: kein vorab gesetztes `alreadyApplied` im Plan; Retry-Semantik
   folgt dem bestehenden Migration-Journal und wird separat getestet.
8. **Nichttransaktionale DDL / implizite Commits**: Falls ein Dialekt die
   geschützte sequenzverändernde Operation nicht innerhalb der Executor-
   Transaktion ausführen kann (z. B. impliziter Commit), ist all-or-none nicht
   garantiert. Mitigation: Capability für diesen Operationstyp auf `false`
   setzen und stattdessen Blocker verwenden.

## 7. Out-of-Scope / Folge-Themen

- Cross-Process-Koordination (z. B. via PG advisory_lock auf
  systemweiter Ebene). Reserviert für eine spätere Tranche, sobald
  d-migrate-Operatoren in `cron`/Pipelines parallel laufen.
- App-side Retry-Hooks: d-migrate liefert nur den Blocker-Code, die
  App-Integration entscheidet selbst über Backoff.
- SQLite WAL-Mode-spezifische Verbesserungen (`BEGIN CONCURRENT`-
  Vorschlag aus SQLite 3.42+). Kommt mit der nächsten SQLite-Floor-
  Hebung.
