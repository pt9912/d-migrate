# Implementierungsplan: Atomare Sequence-Preserve Probe + Restore unter Lock

> Status: In Progress (2026-06-01) — Phasen A + B + C erledigt;
> Phasen D + E offen.
> - **Phase A** (2026-05-31): Vertraege + Classifier + Capability-Defaults.
> - **Phase B** (2026-05-31): `hexagon:ports-execute`-Modul +
>   PG/MySQL/SQLite-Executoren mit Live-Container-/in-process-Tests.
> - **Phase C** (2026-06-01): Sub-Slices C.2 → C.4 → C.3 → C.1 → C.5
>   landed (Commits `1c09147d` → `11d04e57` → `8c2e0a07` → `174c3891`
>   → `b4f548b0` plus CI-Fix `39bcaa29` + Coverage-Puffer `d72e572f`).
>   Master ist auf dem Atomic-Runner-Pfad, 12/12 E2E-Live-IT pro
>   Dialekt grün, kein `SequenceCurrentValueProbeRunner` und kein
>   `sequenceCurrentValueProbe`-Slot mehr im Repo (Probe-Adapter
>   selbst bleiben als toter Code — Dead-Code-Cleanup ist eigener
>   Folge-Slice).
> - **Phase D**: Cross-Plan-Deadlock-Beweis + `supportsAtomicPreserveAllInPlan`-
>   Flag-Flip — offen.
> - **Phase E**: User-Guide + CHANGELOG + KDoc-Sync (Docs-only) — offen.
> Workstream: E.3 Folge-Slice für `preserveCurrentValue`-Atomicity
> Vorarbeit:
> - `docs/planning/done/ImpPlan-0.9.7-sequence-preserve-current-value.md` §3.2
>   und §9 (Out-of-Scope), §10 Risiken
> - `docs/planning/done/ImpPlan-0.9.7-sqlite-sequence-preserve-current-value.md`
>   §6 Risiken Nr. 1

## 1. Ausgangslage

Die 0.9.7-Erstscheibe und der SQLite-Folge-Slice implementieren
`preserveCurrentValue` als zweistufigen Ablauf:

1. **Probe (Read)** — `SequencePreserveStage.run` ruft pro Kandidaten-Op
   einen dialect-spezifischen Probe-Adapter auf, der den
   dialektspezifischen aktuellen Stand liest (PG: `last_value` auf der
   Sequenzrelation; MySQL/SQLite: `dmg_sequences.next_value` auf der
   Helper-Tabelle). Die Connection wird sofort wieder freigegeben.
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
- Stage-/Runner-Anpassung: die heutige Drei-Phasen-Abfolge
  (Probe-in-Stage → Render → Execute) verliert ihre eigenständige Probe-
  Phase; Render bleibt erhalten, Probe und Restore wandern in einen
  Execute-Time-Runner, der Lock, Probe, geschützte Hauptoperation und
  Restore in einer Transaktion ausführt.
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
| PostgreSQL | Advisory-Lock-Key über `(schema, name)` | `pg_advisory_xact_lock(hashtext(...))` | `SET LOCAL lock_timeout` (SQLSTATE 55P03) | parallele d-migrate-Läufe (App-`nextval` ist PG-seitig lock-free; siehe §4.1 Korrektur und Risiko Nr. 8) |
| MySQL | `dmg_sequences`-Zeile | `SELECT … FOR UPDATE` (Row-Lock) | `SET SESSION innodb_lock_wait_timeout` (Errorcode 1205) | App-seitige `dmg_nextval`-Updates auf derselben Zeile |
| SQLite | komplette DB | `BEGIN IMMEDIATE` / `RESERVED`-Lock | `PRAGMA busy_timeout` (Errorcode `SQLITE_BUSY`) | jeden App-Schreiber (DB-weit), Reader bleiben funktional |

### 4.1 PostgreSQL

```sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT pg_advisory_xact_lock(<stable_hash(sequence_ref)>); -- (1) Lock
SELECT last_value, is_called FROM <quoted_seq_ref>;        -- (2) Probe
-- Geschützte sequenzverändernde Statements auf derselben Connection ausführen.
SELECT setval(<regclass_seq_ref>, <last_value>, <is_called>); -- (3) Restore
COMMIT;
```

**Korrektur (2026-05-31, Phase B.2):** Die ursprüngliche Plan-Version
empfahl `LOCK TABLE <seq> IN ACCESS EXCLUSIVE MODE`. PG akzeptiert
diesen Statement gegen eine Sequenzrelation jedoch nicht
(`ERROR: cannot lock relation … / Detail: This operation is not
supported for sequences.`), und `nextval` ist in PG by-design
lock-free — es gibt keine Sperre, die ein `nextval` aus einer
parallelen Session blockt. Phase B.2 setzt deshalb auf
`pg_advisory_xact_lock` als realistische, ehrliche Alternative.

- `pg_advisory_xact_lock(key)` serialisiert **parallele d-migrate-
  Läufe**, die denselben Sequenz-Hash beanspruchen. Die App-seitige
  `nextval`-Race bleibt prinzipbedingt offen — sie wird aber
  deutlich kleiner als beim heutigen Zwei-Transaktionen-Pfad, weil
  Probe + Restore jetzt in **einer** Transaktion auf **einer**
  Connection passieren. Plan-Doc-Risiko Nr. 6 (siehe §6) erfasst die
  verbleibende App-`nextval`-Race ausdrücklich.
- Der Lock-Key ist ein deterministischer `bigint`-Hash über
  `(schema, name)` plus einem Namespace-Präfix (z. B.
  `hashtext("d-migrate:seq:" || schema || "." || name)::bigint`),
  damit der Lock dialect-übergreifend kollisionsfrei bleibt.
  Advisory locks sind transaktionsskopiert (`xact_lock`), brauchen
  also kein explizites `pg_advisory_unlock` — `COMMIT`/`ROLLBACK`
  gibt sie frei.
- `lock_timeout = '5s'` wirkt weiterhin: `pg_advisory_xact_lock`
  blockiert bis der konfliktäre Halter freigibt; SQLSTATE `55P03`
  (`lock_not_available`) auf Timeout. Mapping auf
  `SEQUENCE_PRESERVE_LOCK_TIMEOUT` bleibt.
- Nach der Advisory-Lock-Korrektur braucht nur der Restore-Renderer
  einen schemaqualifizierten Sequence-Ref (`regclass_seq_ref` für
  `setval`); der Lock arbeitet rein auf dem deterministischen
  `bigint`-Hash. Der Probe liest `last_value` über denselben
  `regclass_seq_ref`, damit Probe und Restore dieselbe Relation treffen.
- Die Konfliktauflösung wird in Phase B.2 durch zwei Live-Container-
  Tests gepinnt: (a) Happy-Path-Probe+Restore committet atomar;
  (b) ein konkurrierender `pg_advisory_xact_lock`-Halter triggert
  SQLSTATE 55P03 nach `lockTimeoutMillis`. Ein dritter Test gegen
  app-seitiges `nextval` (Race-Smoke) gehört dokumentiert in das
  bestehende `:test:integration-concurrency`-Modul, **nicht** in
  diesen Executor-Slice — die Race ist plan-bewusst und nicht durch
  diesen Executor lösbar.

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

**DoD A** *(erledigt 2026-05-31)*

- [x] Per-Dialekt-Lock-Matrix dokumentiert. *(§4.0 Tabelle plus
      per-Dialekt-KDoc-Begruendung in
      `SequenceCapabilityDefaults.kt`.)*
- [x] Klassifier-Mapping für neue Codes registriert.
      *(`PlannerBlockerClassifier.SEQUENCE_PRESERVE_LOCK_TIMEOUT_CODE`
      und `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED_CODE` →
      `MANUAL_ACTION_REQUIRED`, Test
      `PlannerBlockerClassifierTest`-Block „Atomic-Preserve Phase A
      codes classify to MANUAL_ACTION_REQUIRED".)*
- [x] Operationstyp-Matrix dokumentiert und mit Blocker-Pfad
      verbunden. *(`SequenceCapability.
      transactionalProtectedSequenceOperations: Set<String>` mit
      `emptySet()`-Default pro Dialekt; Blocker-Pfad ist
      `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` aus dem Classifier.
      Phase B refactort `Set<String>` zu
      `Set<ProtectedOperationId>`, sobald der Executor-Vertrag
      existiert.)*
- [x] Carve-Outs (kein cross-DB Lock, kein App-side Retry)
      dokumentiert. *(§3.2 unveraendert plus expliziter
      KDoc-Verweis in `SequenceCapability` auf den Out-of-Scope-
      Block.)*

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

**DoD B** *(erledigt 2026-05-31)*

- [x] Execute-Port, Batch-Typ und Result-Klassen existieren in
      `hexagon:ports-execute`. *(B.1: `AtomicSequencePreserveExecutor`
      + `AtomicSequencePreserveBatch` + `AtomicSequencePreserveRequest`
      + sealed `AtomicSequencePreserveResult`
      (`Applied`/`NotFound`/`LockTimeout`/`Failed`) +
      `AtomicProtectedExecutionResult`.)*
- [x] PG-/MySQL-/SQLite-Executoren implementiert. *(B.2
      `PostgresAtomicSequencePreserveExecutor` mit
      `pg_advisory_xact_lock(hashtext(...))` — die ursprüngliche
      `LOCK TABLE`-Strategie aus §4.1 wurde live als nicht-haltbar
      erkannt und im Plan-Doc auf advisory-Lock korrigiert; B.3
      `MysqlAtomicSequencePreserveExecutor` mit
      `SELECT 1 FROM dmg_sequences WHERE name=? FOR UPDATE` +
      `SET SESSION innodb_lock_wait_timeout`; B.4
      `SqliteAtomicSequencePreserveExecutor` mit `BEGIN IMMEDIATE`
      + `PRAGMA busy_timeout` über autocommit=true und expliziten
      `BEGIN IMMEDIATE`/`COMMIT`/`ROLLBACK`-SQL-Statements.)*
- [x] Executor-Tests mit echten Live-Containern (Lock-Race-Reproduktion)
      für Single-Seq und Multi-Seq Batch inkl. Timeout-Leckageprüfung.
      *(B.2: `PostgresAtomicSequencePreserveExecutorIntegrationTest`
      mit Applied/Multi-Seq-Sort/NotFound/LockTimeout/Autocommit-Restore
      gegen Testcontainers-PG; B.3:
      `MysqlAtomicSequencePreserveExecutorIntegrationTest`
      mit Applied/NotFound/LockTimeout/SessionTimeout-Restore gegen
      Testcontainers-MySQL; B.4:
      `SqliteAtomicSequencePreserveExecutorIntegrationTest`
      mit Applied/Multi-Seq-Sort/NotFound/LockTimeout/SessionTimeout-
      Restore gegen file-backed in-process SQLite.)*

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

#### Sub-Slice-Aufteilung Phase C

Phase C wird in fünf Slices zerlegt. Die Slice-Namen folgen der logischen
Architektur-Schicht (C.1=Stage, C.2=Render, C.3=Runner, C.4=Wiring,
C.5=Live-IT), die **Ausführungsreihenfolge** ist aber bewusst eine andere:

> **C.2 → C.4 → C.3 → C.1 → C.5**

Begründung *(Re-Cut 2026-06-01)*: C.1 ist der einzige Slice, der das Verhalten
der Live-Preserve-IT verändert — alle anderen Slices sind additiv und
master-grün. Würde C.1 zuerst landen, wäre master für die Lebensdauer
mehrerer Commits rot, oder Stage müsste eine Dual-Emission tragen
(`probe-driven follow-up + atomicBatch` parallel) — genau der Stopgap, den
[[feedback_no_carveouts]] verbietet. Der Re-Cut dreht die Reihenfolge um:
zuerst die additiven Consumer (C.2/C.4/C.3), dann der aktivierende Producer
(C.1), dann der E2E-Beweis (C.5). Die Stage-Änderung in C.1 entfernt damit
in einem Commit den alten Pfad und aktiviert gleichzeitig den neuen — kein
Flag, kein Shim, kein toter Code.

Eingaben für C.2/C.3 sind die in Phase B fertiggestellten Typen
(`AtomicSequencePreserveBatch`, `ProtectedOperationId`, der Executor-Port) —
nicht Stage-Output. Damit ist die Abhängigkeitskette stabil unter dieser
Reihenfolge.

Pro Slice der erwartete Master-Status:

| Slice | Master-Build | Live-Preserve-IT | Was passiert |
|---|---|---|---|
| C.2 | grün | unverändert (heutiger Pfad) | Render-Pipeline emittiert zusätzlich `List<ExecutableSegment>`. Ohne `atomicBatch` aus Stage degeneriert sie zu `PlainSqlSegment`-only. SQL-String-Output bytemäßig identisch. |
| C.4 | grün | unverändert | `SchemaMigrateWiring` löst pro Dialekt den Atomic-Executor auf (Phase-B-Code). DI-Slot belegt, niemand ruft. Capability-Flag-Flip + `transactionalProtectedSequenceOperations`-Sets aus C.4 sind reine Daten — Stage liest sie noch nicht (das macht erst C.1). |
| C.3 | grün | unverändert | `JdbcMigrationExecutor` konsumiert `List<ExecutableSegment>`. `AtomicPreserveSegment`-Branch existiert und ist per Konstruktor-DI mit dem Executor verbunden — wird aber nie betreten, weil Stage noch keinen Batch liefert. Fake-Executor-Tests decken den Branch ab. |
| C.1 | grün | flippt auf atomaren Pfad | Stage stoppt den Probe-Aufruf, produziert `AtomicSequencePreserveBatch` + `internalFollowUpIds`. Ab diesem Commit routet die C.2-Segmentierung die geschützten Ops + Restore-Renderer in den `AtomicPreserveSegment`-Pfad. Toter Probe-Wiring-Pfad wird in derselben Tranche entfernt. |
| C.5 | grün | beweist den Flip | End-to-End Live-IT pro Dialekt (Applied/Multi-Seq/LockTimeout/Failed). |

##### C.1 — Stage-Refactor: Requests + ProtectedOperationIds

- `SequencePreserveStage` (`:hexagon:application/.../SequencePreserveStage.kt`)
  ruft `SequenceCurrentValueProbe` nicht mehr direkt auf. Stattdessen baut sie
  pro Kandidat einen `AtomicSequencePreserveRequest` mit einem
  `renderRestore`-Closure, der das spätere Probe-Result über den
  dialect-spezifischen Restore-Renderer (PG `setval`, MySQL/SQLite `UPDATE`)
  in SQL übersetzt.
- Stage berechnet pro Kandidat die zugehörigen `ProtectedOperationId`s der
  sequenzverändernden Hauptoperationen anhand der Operation-Capability-Matrix
  `SequenceCapability.transactionalProtectedSequenceOperations`. Operationstypen,
  die nicht in der Matrix stehen, sind nicht atomar abdeckbar — Stage emittiert
  dafür `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` als Blocker (kein stiller
  Fallback auf den nicht-atomaren Pfad).
- `Outcome.Succeeded` erhält ein neues Feld `atomicBatch:
  AtomicSequencePreserveBatch?`. `null` bedeutet: keine zu schützenden
  Sequenzen. Non-null wird in C.3 vom Runner konsumiert.
- Stage selbst macht weiterhin keinen DB-Schreibzugriff — und ab C.1 auch keinen
  DB-Read mehr.

**DoD C.1** *(erledigt 2026-06-01, commit `174c3891`)*

- [x] `SequencePreserveStage.run` ruft `SequenceCurrentValueProbe` nicht mehr auf.
- [x] Stage erzeugt `AtomicSequencePreserveBatch` mit `requests`,
      `protectedOperationIds` und `internalFollowUpIds` korrekt für Single- und
      Multi-Seq-Pläne. *(`protectedOperationIds` tragen Parent-INSTANCE-IDs;
      `internalFollowUpIds` die :preserve-IDs der Audit-Follow-ups.)*
- [x] Operationstyp ohne Eintrag in `transactionalProtectedSequenceOperations`
      → `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED` Blocker (Test im erweiterten
      `SequencePreserveStageTest`). *(Capability-Gate via
      `capabilityResolver`-Param testbar gemacht; UNSUPPORTED-Test mit
      synthetischer Allowlist + leerer Allowlist verankert.)*
- [x] `AlterSequenceCurrentValue`-Follow-ups werden weiterhin in den
      augmentierten Plan eingehängt (Audit-Artefakt), aber nicht mit einem
      Probe-Wert gefüllt — sie bleiben Marker für Renderer und Runner.
      *(`DiffOperation.AlterSequenceCurrentValue.Companion.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE = 0L`;
      isCalled=true für PG-Renderer-Safety; restoreValue=null;
      rollbackImpossible=true.)*
- [x] Tote Probe-Wiring-Pfade (inkl. `SequenceCurrentValueProbeRunner`-
      Dispatcher und `sequenceCurrentValueProbe`-Slot in
      `SchemaMigrateWiring`) entfernt — kein `@Deprecated`-Shim, kein
      Re-Export. *(`SequenceCurrentValueProbeRunner.kt` gelöscht;
      `SequenceCurrentValueProbeFn`-Typealias entfernt; Probe-Adapter
      selbst (`PostgresSequenceCurrentValueProbe` etc.) bleiben als
      toter Code stehen — Dead-Code-Cleanup ist eigener Folge-Slice
      per User-Scope-Entscheidung.)*

##### C.2 — Render-Pipeline → `ExecutableSegment`-Sicht

- Neuer Vertragstyp in `:hexagon:ports-execute` (neben
  `AtomicSequencePreserveBatch`) — der Typ formt den Runner-Vertrag mit
  und gehört daher in dieselbe Schicht wie die Atomic-Executor-Port-Typen,
  nicht in das CLI-/Application-Modul:

  ```kotlin
  sealed interface ExecutableSegment
  data class PlainSqlSegment(
      val statements: List<MigrationExecutionStatement>,
  ) : ExecutableSegment
  data class AtomicPreserveSegment(
      val batch: AtomicSequencePreserveBatch,
      val protectedStatements: List<MigrationExecutionStatement>,
  ) : ExecutableSegment
  ```

- Renderer-Output bleibt für `--plan-only`, Reports und Rollback unverändert
  (heutige SQL-String-Liste). Zusätzlich erzeugt die Pipeline für den
  `--execute`-Pfad eine `List<ExecutableSegment>`-Sicht. **Beide Artefakte
  werden aus einer einzigen runner-internen Operation-Liste abgeleitet** —
  die SQL-String-Liste ist eine `map`-Projektion derselben Quelle, die
  Segmentliste eine `groupBy(role)`-Projektion. Es gibt keinen Render-
  Doppelpfad, der divergieren könnte.
- `AlterSequenceCurrentValue`-Follow-ups erscheinen in der Plan-Only-/Report-/
  Rollback-SQL weiterhin als Standalone-Statements (Audit). Im
  `--execute`-Segment-Output landen sie **nicht** als `PlainSqlSegment`-
  Statements; ihre Ausführung gehört in den Restore-Schritt innerhalb von
  `AtomicSequencePreserveExecutor.execute`.
- Geschützte Hauptoperationen erscheinen ausschließlich im
  `AtomicPreserveSegment.protectedStatements` (nicht zusätzlich in einem
  benachbarten `PlainSqlSegment`).

**DoD C.2** *(erledigt 2026-06-01, commit `1c09147d`)*

- [x] `ExecutableSegment`-Hierarchie liegt in `:hexagon:ports-execute`
      (kein Re-Export aus `:hexagon:application`).
- [x] Tests decken das Mapping von Plan + AtomicBatch auf Segmentliste ab.
      *(12 Cases in `ExecutableSegmentsTest`.)*
- [x] Plan-Only-/Report-/Rollback-SQL ist bytemäßig unverändert
      (Golden-Master-Vergleich gegen Pre-C.2-Snapshot — C.2 berührt keine
      Renderer-Klasse, die Funktion wird von niemandem vor C.1 aufgerufen).
- [x] Property-Test (Matrix-Test): jedes Plan-Statement aus der internen
      Operation-Liste ist im SQL-String-Output **genau einmal** vertreten
      und in der Segmentliste **genau einmal** in **genau einem** Segment.
- [x] Live-Execute-Segmentliste enthält interne Restore-Follow-ups **nicht**
      als Standalone-Statements (gefiltert via `internalFollowUpIds`).
- [x] Master-grün-Invariante: vor C.1 lieferte Stage keinen `atomicBatch`;
      Segmentliste degenerierte zu einem PlainSqlSegment, Probe-in-Stage-Pfad
      blieb produktiv. Ab C.1 ist der atomare Pfad scharf.

##### C.3 — Execute-Runner: Segment-aware, Connection-Owned

- `JdbcMigrationExecutor` (`:cli/.../JdbcMigrationExecutor.kt`) — oder ein
  neuer Adapter darüber — konsumiert `List<ExecutableSegment>` statt einer
  flachen `List<MigrationExecutionStatement>`.
- `PlainSqlSegment` läuft wie heute (HikariCP-Pool, Connection pro Statement
  oder pro Segment, je nach aktueller Semantik — keine Verhaltensänderung).
- `AtomicPreserveSegment`:
  - holt eine dedizierte Connection aus dem Pool, prüft den Connection-Owner-
    Vertrag aus dem Atomic-Executor-Port (siehe DoD unten) und setzt
    `autoCommit=false` (PG/MySQL) bzw. erfüllt die SQLite-Spezial-
    Anforderung aus Phase B.4 (autocommit=true + explizite
    `BEGIN IMMEDIATE`),
  - übergibt sie an `AtomicSequencePreserveExecutor.execute(...)` mit
    `executeProtectedOperations`-Closure, die `protectedStatements` auf
    derselben Connection ausführt,
  - übersetzt die zurückgelieferte
    `AtomicSequencePreserveResult`-Sealed-Class in `ExecutionTrace`-
    Einträge: `Applied` → Erfolgs-Status pro betroffener Sequenz,
    `NotFound` → Warn-Diagnostic, `LockTimeout` →
    `SEQUENCE_PRESERVE_LOCK_TIMEOUT` Blocker (Driver-spezifisches Mapping:
    PG SQLSTATE `55P03`, MySQL Errorcode `1205`/`ER_LOCK_WAIT_TIMEOUT`,
    SQLite Errorcode `5`/`SQLITE_BUSY` — siehe §4.1/§4.2/§4.3), `Failed`
    → Fehler-Status + Abbruch des restlichen Plans.
- Nach `AtomicPreserveSegment` läuft der restliche Plan (folgende
  `PlainSqlSegment`s) weiter, sofern kein Blocker geworfen wurde.

**Vertragsklarstellung Connection-Owner**: Der Port-KDoc von
`AtomicSequencePreserveExecutor` sagt heute „executor refuses connections
already in an enclosing transaction", ohne die Mechanik festzulegen. C.3
pinnt sie: der Executor prüft `connection.getAutoCommit()` (PG/MySQL) bzw.
liest den `transaction_state` (SQLite) zum Eintritt und wirft eine
`IllegalStateException` mit dem Fehlertext `"AtomicSequencePreserveExecutor
requires an owned, non-enclosed connection"` — der Runner fängt diese
Exception **nicht**; der Fehler ist ein Wiring-Bug, kein Laufzeit-
Diagnostic. Damit landet die Verantwortung im Composition Root (C.4), nicht
im Result-Mapping.

**DoD C.3** *(erledigt 2026-06-01, commit `8c2e0a07`)*

- [x] Runner konsumiert `List<ExecutableSegment>` (über `SegmentAwareMigrationExecutor`);
      `PlainSqlSegment`-Verhalten ist regressionsfrei.
- [x] `AtomicPreserveSegment`-Pfad ruft Atomic-Executor mit einer einzigen,
      über `lock+probe+protected+restore+commit` gehaltenen Connection auf.
- [x] Connection-Owner-Vertrag in `AtomicSequencePreserveExecutor.execute`
      durchsetzt: `AtomicSequencePreserveExecutor.Companion.requireOwnedConnection`
      prüft `connection.autoCommit == true` und wirft `IllegalStateException`
      sonst. Helper-Test im `:hexagon:ports-execute` Port-Contract-Test plus
      ein OwnerCheckTest pro Adapter (PG/MySQL/SQLite).
- [x] Result-Mapping deckt alle vier `AtomicSequencePreserveResult`-Cases
      auf `ExecutionTrace` ab (SegmentAwareMigrationExecutorTest mit
      Fake-Executor).
- [x] `LockTimeout` → `SEQUENCE_PRESERVE_LOCK_TIMEOUT` im executionError
      durchgereicht (Driver-Mapping PG `55P03`, MySQL `1205`, SQLite
      `SQLITE_BUSY` ist Phase-B-Executor-verifiziert). `Failed` → Plan-Abbruch
      mit Rollback (Phase-B-Executor rollt selbst).
- [x] Master-grün-Invariante (zur Zeit von C.3 erfüllt): solange Stage keinen
      `atomicBatch` lieferte (Pre-C.1), wurde der `AtomicPreserveSegment`-
      Branch nie betreten.

##### C.4 — Wiring im Composition Root + Capability-Flag-Flip

- `SchemaMigrateWiring` (`:cli/.../SchemaMigrateWiring.kt`) instantiiert
  pro Dialekt den passenden `AtomicSequencePreserveExecutor` analog zum
  bestehenden `SequenceCurrentValueProbeRunner`-Dispatcher: PG →
  `PostgresAtomicSequencePreserveExecutor`, MySQL →
  `MysqlAtomicSequencePreserveExecutor`, SQLite →
  `SqliteAtomicSequencePreserveExecutor`.
- Der Lock-Timeout (Default 5 s, siehe §4.0) wird hier konfigurierbar
  durchgereicht — kein hard-coded Wert im Runner.
- **Capability-Defaults werden in C.4 gesetzt** (Lesart α, siehe unten):
  - `supportsAtomicPreserve = true` für PG/MySQL/SQLite.
  - `transactionalProtectedSequenceOperations` wird pro Dialekt mit den
    `ProtectedOperationId`-Werten befüllt, die der jeweilige Phase-B-
    Executor als sicher (`d.h. ohne impliziten Commit innerhalb der
    Atomic-Transaktion ausführbar`) deklariert hat. Quelle der IDs ist
    eine pro-Dialekt-Konstante neben dem Executor (z. B.
    `PostgresAtomicSequencePreserveExecutor.SAFE_PROTECTED_OPERATIONS`).
  - `supportsAtomicPreserveAllInPlan` bleibt in C.4 noch `false`; das
    Flag flippt erst in Phase D nach erfolgreicher Cross-Plan-Deadlock-
    Verifikation.
- Aufruf von `AtomicSequencePreserveExecutor.execute` erfolgt nur mit
  Connections, deren Owner-Vertrag aus C.3 erfüllt ist (autocommit-State
  gemäß Dialekt-Vorgabe, keine umschließende Transaktion). C.4 ist die
  einzige Stelle, an der Connections für Atomic-Segments allokiert
  werden.
- Das bestehende Probe-Wiring
  (`sequenceCurrentValueProbe = ...probe`,
  `SequenceCurrentValueProbeRunner`-Dispatcher) bleibt in C.4 **bestehen**
  — bis C.1 den Probe-Aufruf in Stage entfernt, ist dieser Pfad noch
  produktiv aktiv. Das Aufräumen erfolgt erst in C.1 in derselben
  Tranche, in der der Probe-Aufruf wegfällt.

**DoD C.4** *(erledigt 2026-06-01, commit `11d04e57`)*

- [x] `SchemaMigrateWiring` löst pro `DatabaseDialect` den richtigen
      Atomic-Executor auf (via `AtomicSequencePreserveDispatcher`;
      Unit-Test mit allen drei Dialekten + Stateless-Reuse-Pin).
- [x] Lock-Timeout-Setting ist konfigurierbar (`AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS = 5000L`)
      und wird im Atomic-Executor-Aufruf verwendet (Tests verifizieren
      Default + custom Wert via Slot-Capture).
- [x] `SequenceCapabilityDefaults` für PG/MySQL/SQLite hat
      `supportsAtomicPreserve = true` und befüllte
      `transactionalProtectedSequenceOperations`-Sets (CreateSequence /
      AlterSequence / RenameSequence).
- [x] `SequenceCapability.kt`-KDoc auf
      `transactionalProtectedSequenceOperations` referenziert C.4 als
      Source-of-Truth (statt fälschlich „Phase B's per-dialect executors
      populate the set").
- [x] Connection-Allokation für `AtomicPreserveSegment` ausschließlich
      über `AtomicSequencePreserveRunner.defaultAcquireConnection`;
      Owner-Vertrag aus C.3 (autoCommit-Check) wird vom Executor
      durchgesetzt.
- [x] Master-grün-Invariante: Probe-Wiring blieb in C.4 bestehen
      (Capability-Flag-Flip allein änderte die Live-Preserve-IT nicht,
      weil Stage die Capability noch nicht las); Aufräumen erfolgte in
      C.1.

##### C.5 — Live-IT End-to-End pro Dialekt

- Neue Live-Integration-Tests, die den vollen Pfad
  `Stage → Render → Wiring → Runner → AtomicSequencePreserveExecutor →
  Container` fahren — ohne das Stage-Probe-Verhalten zu fakten, ohne
  Renderer-Output zu mocken.
- Pro Dialekt mindestens:
  - Single-Seq Applied (Happy Path mit echtem Plan mit
    `preserveCurrentValue` + sequenzverändernder Hauptop).
  - Multi-Seq Applied (zwei Sequenzen, deterministische Sortierung
    überprüft).
  - LockTimeout-Pfad (zweite Session hält Lock → Blocker
    `SEQUENCE_PRESERVE_LOCK_TIMEOUT`, kein partielles Apply).
  - Failed-Pfad (geschützte Op wirft Exception → Rollback,
    Sequenzzustand unverändert in der DB messbar).
- Tests laufen im jeweiligen `integration-postgresql` / `integration-mysql` /
  `integration-sqlite` Source-Set (Testcontainers bzw. file-backed SQLite,
  wie in Phase B etabliert).

**DoD C.5** *(erledigt 2026-06-01, commits `b4f548b0` + CI-Fixes `39bcaa29` + `d72e572f`)*

- [x] Jeder Dialekt hat eine Live-IT, die den vollen Pfad ohne Fakes fährt
      (`{Postgres,Mysql,Sqlite}SchemaMigrateAtomicPreserveIntegrationTest`).
- [x] Applied/Multi-Seq/LockTimeout/Failed-Pfade verifiziert pro Dialekt
      (12/12 grün in GH-CI `Integration Tests` Job `26756099781`).
      Test-Fixture `executeSegmentsAgainstPool` spiegelt
      `SegmentAwareMigrationExecutor.execute` byte-für-byte in
      `:hexagon:application`-testFixtures, weil die IT-Module nicht auf
      `:adapters:driving:cli`-internals zugreifen können.
- [x] MySQL + SQLite `integration-concurrency`-Race-Tests auf Atomic-Runner
      migriert (positive Beweis: `finalValue >= initial + writerAdvances`
      mit 50 concurrent writers). PG-Race bleibt `knownRace=true`-Reproducer
      (advisory_xact_lock blockt App-`nextval` nicht — Risiko Nr. 8).

##### C.5-Follow-up CI-Fixes (commits `39bcaa29` + `d72e572f`)

Beim ersten Push auf develop schlug die Integration-Test-Suite + ein
Coverage-Gate fehl. Sieben Befunde wurden gefixt:

1. `--execute` braucht `--report` (request validation) — alle 3 ITs
   setzen jetzt tmpDir + report.json.
2. dbLoader-State-Tracking (Post-Compare-Drift sonst): erste call →
   targetSchema, zweite call → sourceSchema.
3. Neue `SchemaMigrateRequest.mysqlNamedSequences` (analog
   `sqliteNamedSequences`) + Plumbing in `SchemaMigrateRenderPipeline`.
   `MysqlDiffSequenceOps.ensureHelperMode` blockte sonst mit
   MANUAL_ACTION_REQUIRED.
4. LockTimeout-Assertion `(exit != 0)` statt `exit shouldBe 8` —
   LockTimeout maps onto executionError (exit 5), nicht
   MIGRATION_BLOCKED (exit 8).
5. PG-Werte-Assertions korrigiert (last_value + new_increment).
6. SQLite `dmg_sequences`-Bootstrap mit allen 11 Spalten aus
   `SqliteSequenceEmulationTemplates.supportTableSql` (vorher fehlte
   `increment_by` + andere → `SQLITE_ERROR no such column`).
7. SQLite LockTimeout via raw DriverManager-Connection statt
   pool.borrow() für die atomic-Transaktion (Hikari-Pool +
   `journal_mode=wal` beobachtete die holder-RESERVED-Sperre nicht;
   Phase-B-Pattern verwendet ebenfalls raw DriverManager).
   xerial-Detail dokumentiert: `setAutoCommit` ist idempotent;
   `Statement.execute("BEGIN IMMEDIATE")` ist Roh-SQL ohne
   Auto-Commit auf `Statement.close()`.

Zusätzlich Coverage-Puffer (`d72e572f`): cli von 92.5% → 93.1%,
application von 90.1% → 90.3%; neue Tests für `AtomicPreserveRestoreSql`
(alle 3 Dialekt-Branches), `AtomicSequencePreserveRunner.defaultAcquireConnection`-
Catch-Pfade, und `SegmentAwareMigrationExecutor::executeWithDefaults`
(neuer 4-Arg-Method-Reference-Entry, ersetzt 7-Zeilen-Inline-Lambda
im Wiring).

#### Designentscheidung: Hard-Replace (Lesart α), 2026-06-01

Im Review-Durchlauf 2026-06-01 wurde die Coexistenz-Frage zwischen heutigem
nicht-atomarem Pfad (Probe-in-Stage → Standalone-Render → Execute) und neuem
Atomic-Runner zugunsten **Lesart α (Hard-Replace)** entschieden.

- Phase C ersetzt den nicht-atomaren Pfad vollständig. Es gibt nach C.5
  keinen Probe-in-Stage-Codepfad mehr im Repo, keinen
  `SequenceCurrentValueProbeRunner`-Dispatcher und keinen Standalone-
  Render von `AlterSequenceCurrentValue` im `--execute`-Pfad.
- Das Capability-Flag `supportsAtomicPreserve` wird in C.4 für alle drei
  Dialekte auf `true` gesetzt. Die `transactionalProtectedSequenceOperations`-
  Sets werden in C.4 mit den vom Phase-B-Executor deklarierten sicheren
  IDs befüllt.
- Phase E reduziert sich damit auf User-Guide + CHANGELOG + KDoc-
  Synchronisation. Keine Flag-Flip-Stopgap-Semantik („wird in Phase E
  scharfgeschaltet") — das wäre genau das Muster, das
  [[feedback_no_carveouts]] verbietet.

Begründung (kompakt):

- Phase B hat alle drei Dialekt-Executoren mit Live-IT-Suite grün
  geliefert. Die Atomik ist nicht spekulativ.
- β würde zwei Codepfade dauerhaft im Repo halten und die Test-Matrix
  verdoppeln — Wartungskosten ohne realen operativen Gegenwert.
- Bugfix-Rückfallebene aus β ist schwach: bei einem akuten Atomic-Bug
  bleibt das dokumentierte Maintenance-Fenster („Schreibverkehr vor
  `--execute` stoppen") aus den 0.9.7-Slices operativ verfügbar.

**DoD C** *(Aggregat — komplett erfüllt durch C.1–C.5; 2026-06-01)*

- [x] Stage markiert Kandidaten und erzeugt Requests ohne DB-Schreibzugriff. *(C.1)*
- [x] Render-Pipeline erzeugt runner-interne `ExecutableSegment`s zusätzlich
      zum heutigen SQL-Artefakt. *(C.2)*
- [x] Execute-Runner ruft Executor atomar auf und führt die geschützten
      Sequenzoperationen zwischen Probe und Restore auf derselben Connection aus. *(C.3, C.4)*
- [x] Augmentierter Plan enthält den Follow-up weiterhin (für Audit /
      Plan-Artefakt), ohne neue Felder am Core-`DiffOperation`-Typ
      (`ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE` Companion-Const statt
      nullable currentValue). *(C.1, C.2)*
- [x] Execution-Engine rendert interne Follow-ups nicht als Standalone-SQL
      (gefiltert via `internalFollowUpIds` in `SegmentAwareMigrationExecutor`). *(C.2)*
- [x] `SequenceCapabilityDefaults.supportsAtomicPreserve = true` und
      befüllte `transactionalProtectedSequenceOperations`-Sets für PG/MySQL/
      SQLite (Lesart α). *(C.4)*
- [x] Nicht-atomarer Pfad ist aus dem Repo entfernt — kein
      `SequenceCurrentValueProbeRunner`, kein Probe-in-Stage-Aufruf, kein
      Standalone-Render von `AlterSequenceCurrentValue` im
      `--execute`-Pfad. *(C.1, C.4)*
- [x] End-to-End-Live-IT pro Dialekt grün (12/12 Tests im CI Integration-
      Tests-Job für PG/MySQL/SQLite). *(C.5)*

**Bekannte Carve-Outs / Folge-Themen aus dem Code-Review 2026-06-01:**

- Probe-Adapter-Interfaces (`SequenceCurrentValueProbe` + 3 Adapter-
  Implementierungen + ihre Tests) bleiben als toter Code — Dead-Code-
  Cleanup ist eigener Folge-Slice (User-Scope-Entscheidung).
- 6 dokumentierte Findings aus `/code-review` (commit-range
  `9d6dcba3..d72e572f`):
  1. *high*: `SchemaMigrateExecutionStage.kt:79` ruft `segmentForExecute`
     außerhalb der try-catch — `IllegalStateException` (contiguity
     violation) propagiert unhandled. Mini-Folge-Slice.
  2. *mittel*: `AlterSequenceCurrentValue`-Sentinel rendert wörtlich
     `setval('seq', 0, true)` in plan-only/report-Output.
  3. *mittel*: `SegmentAwareMigrationExecutor.kt:162` zählt Follow-ups
     in `statementsAttempted` mit (Diagnostic-Überzählung).
  4. *niedrig*: stummer Fallback bei unbekanntem `--mysql/sqlite-named-
     sequences`-Wert (asymmetrisch zur Generate-Validierung).
  5. *mittel*: Race-Test-Assertion `finalValue >= initial + writerAdvances`
     ist schwach — beweist nicht eindeutig, dass der Lock die Race
     geschlossen hat.
  6. *niedrig*: LockTimeout-Decorator hardcodet `lockTimeoutMillis` —
     Test-only Issue.

### Phase D — Cross-Plan-Deadlock + AllInPlan-Flag

**Abgrenzung zu Phase B/C** *(Review 2026-06-01)*: Phase B liefert pro
Dialekt einen Executor, der bereits Multi-Sequence-Batches in einer
Transaktion atomar abwickelt und deterministisch nach
`SequenceObjectRef.name` sortiert (siehe B.2-Multi-Seq-Sort-Test). Phase C
wickelt einen Planlauf in einer einzigen Atomic-Connection ab und blockt
unbekannte Operationstypen via C.1-UNSUPPORTED-Gate. Phase D fügt nur die
beiden Bausteine hinzu, die B/C strukturell **nicht** erschlagen können:

- **Cross-Plan-Deadlock-Beweis**: zwei parallel laufende
  `schema migrate`-Aufrufe gegen dieselben Sequenzen — Stresstest, der
  validiert, dass die sortierte Lock-Reihenfolge auch zwischen Prozessen
  greift und kein Diamant entstehen kann. B/C-Tests fahren immer nur
  einen Plan.
- **`supportsAtomicPreserveAllInPlan`-Flag-Check im Stage**: solange das
  Flag pro Dialekt `false` ist (Default nach C.4), blockt ein Multi-
  Seq-Plan mit `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`. Phase D flippt
  das Flag pro Dialekt nach erfolgreichem Cross-Plan-Stresstest und
  fügt den Stage-seitigen Gate-Check hinzu.

**DoD D**

- [ ] Cross-Plan-Deadlock-Test im `:test:integration-concurrency` pro
      Dialekt: zwei parallele `schema migrate`-Läufe über überlappende
      Sequenzen committen ohne Deadlock; bei künstlich invertierter
      Sortierung schlägt der Test reproduzierbar fehl (negativer Smoke).
- [ ] `SequenceCapabilityDefaults.supportsAtomicPreserveAllInPlan = true`
      pro Dialekt nach grünem Stresstest.
- [ ] Stage emittiert `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`, wenn ein
      Plan ≥ 2 Preserve-Kandidaten enthält und der Dialekt das Flag auf
      `false` hat (Unit-Test mit synthetischer Capability-Override).

### Phase E — Docs-only (KDoc + User-Guide + CHANGELOG)

**Abgrenzung** *(Review 2026-06-01, Lesart α)*: Die Capability-Flag-Felder
selbst (`supportsAtomicPreserve`, `supportsAtomicPreserveAllInPlan`,
`transactionalProtectedSequenceOperations`) wurden in Phase A im
`SequenceCapability`-Datenmodell eingeführt. Phase C.4 flippt
`supportsAtomicPreserve` und befüllt
`transactionalProtectedSequenceOperations`; Phase D flippt
`supportsAtomicPreserveAllInPlan`. Phase E enthält damit **keine**
Capability-Code-Änderungen mehr — sie ist reine Dokumentationsarbeit auf
KDocs, User-Guide und CHANGELOG.

- KDoc-Update auf `SequenceCurrentValueProbe` und `SequencePreserveStage`
  mit Hinweis auf den atomaren Pfad (sofern `SequenceCurrentValueProbe`
  nach C.4 noch existiert; andernfalls Hinweis auf die Entfernung).
- KDoc-Update auf `SequenceCapability.transactionalProtectedSequenceOperations`
  ersetzt den Phase-B-Wortlaut durch den korrekten C.4-Verweis.
- User-Guide-Eintrag: „preserveCurrentValue ist seit 0.X.Y atomar unter
  Lock; keine Maintenance-Fenster mehr nötig (PG-App-`nextval`-Race
  bleibt; siehe §6 Risiko Nr. 8)."
- CHANGELOG-Eintrag mit Breaking-Change-Markierung, falls Probe-Port
  oder Stage-Outcome-API in C.1/C.4 strukturell verändert wurden.

**DoD E**

- [ ] KDoc auf `SequenceCapability.transactionalProtectedSequenceOperations`
      verweist auf C.4 als Source-of-Truth (kein „Phase B populates"
      mehr).
- [ ] User-Guide-Eintrag mit PG-`nextval`-Race-Hinweis.
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
4. **PG-Sequenz-Lock semantik (KORRIGIERT 2026-05-31)**: Die
   ursprüngliche Annahme `LOCK TABLE seq IN ACCESS EXCLUSIVE MODE`
   hat sich beim Phase-B.2-Lauf als nicht-haltbar erwiesen — PG
   lehnt das Statement gegen eine Sequenzrelation hart ab
   („This operation is not supported for sequences."), und
   `nextval` ist in PG by-design lock-free. Phase B.2 nutzt
   `pg_advisory_xact_lock(stable_hash(ref))` als realistische
   Alternative: parallele d-migrate-Läufe werden serialisiert, eine
   app-seitige `nextval`-Race bleibt prinzipiell offen. Risiko Nr. 6
   (neu) erfasst die App-Race explizit.
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
8. **PG-App-`nextval`-Race (NEU 2026-05-31)**: Weil PG-Sequenzen
   lock-free sind, kann eine App-Session zwischen unserem
   Probe-Statement und unserem Restore-`setval` ein `nextval`
   einschieben. Der atomare Executor verkleinert das Race-Fenster
   gegenüber dem Zwei-Transaktionen-Pfad (Probe + Restore liegen
   jetzt in einer Connection-Transaktion ms-eng beieinander), kann
   die Race aber nicht eliminieren. Mitigation: dokumentiert in
   `docs/user/guide.md` als „PG atomic-preserve serialisiert
   parallele d-migrate-Läufe und verkleinert das App-Race-Fenster;
   für Null-Race-Garantie weiterhin Maintenance-Fenster nötig."
   `:test:integration-concurrency` deckt die Race im
   `PostgresSequencePreserveRaceTest` weiterhin als `knownRace=true`-
   Reproducer ab; Phase E könnte einen kontrollierten App-side
   Advisory-Lock-Vertrag dokumentieren.
9. **Nichttransaktionale DDL / implizite Commits**: Falls ein Dialekt die
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
