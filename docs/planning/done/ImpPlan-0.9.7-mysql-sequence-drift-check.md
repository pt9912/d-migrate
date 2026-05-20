# Implementierungsplan: 0.9.7 — MySQL Sequence Live-DB-Drift-Check

> **Milestone**: 0.9.7 (Folge-Slice der MySQL Sequence Diff-Migration)
> **Workstream**: E.3 Folge-Slice (Live-DB-Validation für helper-table-
>                  Support-Objekte und `dmg_sequences`-Zeilen)
> **Status**: Done (2026-05-20). Sub-Slices A–F implementiert,
>             `make docker-check` grün, Plan-Doc unter `done/`.
> **Vorbedingung**: MySQL Sequence Diff-Migration ✅
>                  (`docs/planning/done/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`,
>                  Sub-Slices A–H);
>                  F.5 E.3 `CheckPreflightProbe`-Adapter-Pattern ✅
>                  als architektonisches Vorbild.
> **Referenz**: `docs/planning/done/ImpPlan-0.9.7-mysql-sequence-diff-migration.md`
>             §3.1 (ursprünglicher Scope mit Drift-Check),
>             §10.1 (Schicht-1-Idempotenz vs. Schicht-2-Drift),
>             `docs/planning/done/ImpPlan-0.9.7-F.5-check-exclude-vollscheibe.md`
>             §E (Live-DB-Probe-Adapter-Pattern).

---

## 1. Auslöser

Die MySQL Sequence Diff-Migration (Sub-Slices A–H, abgeschlossen
2026-05-20) liefert den diff-basierten Sequence-Renderer mit
**Bootstrap-Idempotenz** (`CREATE TABLE IF NOT EXISTS dmg_sequences` +
`DROP FUNCTION IF EXISTS … ; CREATE FUNCTION`). Damit kollidiert eine
Migration gegen eine DB mit vorhandenen Helper-Objekten nicht beim
Bootstrap-Schritt — sehr wohl aber beim `INSERT INTO dmg_sequences`
oder einem Trigger-CREATE, falls die vorhandenen Objekte fachlich von
den erwarteten abweichen.

Beispielszenarien, in denen die heutige Schicht-1-Idempotenz nicht
ausreicht:

1. **Drift in `dmg_sequences`-Spaltenschema**: Eine ältere Version
   hatte `cache_size INT NOT NULL` statt heute `cache_size INT NULL`.
   `CREATE TABLE IF NOT EXISTS` lässt die alte Definition stehen;
   die spätere `INSERT INTO dmg_sequences ... VALUES (NULL, …)`
   schlägt mit Constraint-Verletzung fehl, ohne klare Diagnose.

2. **Drift in `dmg_nextval` / `dmg_setval` Routine-Body**: Operator
   hat die Routinen früher von Hand modifiziert (z.B. zusätzliches
   Logging, anderes Increment-Verhalten). `DROP FUNCTION IF EXISTS;
   CREATE FUNCTION` überschreibt die Operator-Anpassung stillschweigend
   — die Sequenz-Semantik ändert sich, der Operator sieht es nicht
   im Migrationsreport.

3. **Drift in `dmg_sequences`-Zeile vs. Plan**: Sequence `order_seq`
   existiert in der DB mit `increment_by = 10`, der Plan emittiert
   `CreateSequence(order_seq)` mit `increment = 1`. Heute schlägt
   das `INSERT` mit Primary-Key-Verletzung fehl. Sollte stattdessen
   einen klaren `E124`-Blocker mit Drift-Report ausgeben.

4. **Trigger-Body-Drift**: Ein `dmg_seq_<table>_<col>_<hash>_bi`-
   Trigger existiert mit anderem Body als kanonisch (z.B.
   `dmg_nextval('andere_sequence')` weil der Operator manuell
   verschoben hat). Heute wird `DROP TRIGGER IF EXISTS + CREATE
   TRIGGER` ohne Drift-Check ausgeführt — Operator-Anpassung geht
   stillschweigend verloren.

---

## 2. Warum jetzt?

Die Schicht-1-Idempotenz aus Sub-Slice F adressiert den häufigsten
Fall (wiederholte Migration auf derselben DB). Für produktive Pfade
mit gemeinsam genutzten Helper-Objekten oder operator-modifizierten
Routinen reicht das nicht — die Drift bleibt unentdeckt bis ein
Constraint zur Laufzeit bricht.

Das Adapter-Pattern für solche Live-DB-Probes ist seit F.5 E.3 etabliert
(`CheckPreflightProbe` + `CheckPreflightGate`). Diese Slice spiegelt
das auf den MySQL-Sequence-Kontext.

---

## 3. Scope

### 3.1 In-Scope

- Neuer Port `MysqlSequenceCanonicityProbe` (`hexagon/ports-read`)
  mit Methoden:
  - `probeSupportTable(): SupportTableStatus` — verifiziert
    `dmg_sequences` Spaltensignatur (Names, Typen, NULL-Markierungen,
    PK).
  - `probeRoutine(name: String): RoutineStatus` — verifiziert
    `dmg_nextval` / `dmg_setval` Body-Marker
    (`/* d-migrate:mysql-sequence-v1 object=nextval */`).
  - `probeSequenceRow(name: String): SequenceRowStatus` — liest die
    Zeile und meldet Drift gegen Plan-Werte (`increment`,
    `minValue`, `maxValue`, `cycle`, `cache`).
  - `probeSupportTrigger(triggerName: String): TriggerStatus` —
    verifiziert Trigger-Body-Marker.
- Adapter-Impl `MysqlSequenceCanonicityProbeAdapter`
  (`adapters/driven/driver-mysql`) — Queries gegen
  `INFORMATION_SCHEMA` + `SHOW CREATE FUNCTION` / `SHOW CREATE TRIGGER`.
- Neue CLI-Stage `MysqlSequenceCanonicityStage`
  (`adapters/driving/cli`) — pro `--execute`-Lauf einmal die Probe-
  Endpoints aufrufen, Ergebnisse pro Op an
  `MysqlSequenceCanonicityGate` weiterreichen.
- `MysqlSequenceCanonicityGate` (`hexagon/ports-read`) entscheidet:
  - `Canonical` → Proceed.
  - `Drift(field, expected, actual)` → Block mit `E124` +
    `MANUAL_ACTION_REQUIRED`. Reason-Text dokumentiert die
    Abweichung.
  - `Missing` → bei `INSERT`-Path: weiter mit `CREATE`-Pfad
    (kanonisch nicht da, normale Bootstrap-Logik); bei
    `UPDATE`/`DELETE`-Path: Block mit `E124` (Sequence existiert
    nicht).
- Renderer-Integration in `MysqlDiffSequenceOps`:
  - `renderCreateSequence` UP fragt das Gate vor dem `INSERT`.
  - `renderAlterSequence` UP/DOWN fragt das Gate vor dem `UPDATE`.
  - `renderDropSequence` UP fragt das Gate vor dem `DELETE`.
  - Datei-zu-Datei-Modus überspringt die Probe (keine DB-Verbindung
    verfügbar); Renderer-Output ist unverändert.
- Diagnostic-Code `E124_MYSQL_SEQUENCE_DRIFT` mit strukturiertem
  Drift-Report (Feld, erwartet, gefunden).
- Tests:
  - Probe-Adapter gegen testcontainers MySQL (Integrationstest).
  - Gate-Logik (unit) mit allen drei Status-Returns.
  - Renderer-Integration mit gemockten Probe-Ergebnissen.

### 3.2 Out-of-Scope

- Auto-Migration / Auto-Fix vorhandener Drift (z.B. ALTER
  `dmg_sequences` zur Spaltenanpassung). Operator entscheidet.
- Operator-Override `--allow-sequence-drift` o.ä. — Folge-Slice falls
  Operator-Bedarf da ist.
- Cross-Dialect-Sequence-Drift (PostgreSQL ↔ MySQL). Eigener Plan.
- Drift-Repair-Modus mit interaktivem Operator-Prompt.

---

## 4. Vorbedingungen

- MySQL Sequence Diff-Migration A–H ✅ (2026-05-20).
- F.5 E.3 `CheckPreflightProbe`/`-Gate`-Pattern ✅ als Vorbild.
- Live-MySQL-Connection-Pfad ✅ seit 0.9.x (`MysqlConnection`,
  `JdbcMigrationExecutor`).

---

## 5. Architektur

Spiegelt F.5 E.3 / Sub-Slice E.3:

```
                                        ┌──────────────────────────┐
DiffPlanner ─→ Plan with Sequence-Ops ─→│ MysqlSequenceCanonicity- │
                                        │   Stage (CLI)            │
                                        └──────────┬───────────────┘
                                                   │ pro Op probe
                                                   ▼
                                        ┌──────────────────────────┐
                                        │ MysqlSequenceCanonicity- │
                                        │   ProbeAdapter (driver)  │
                                        └──────────┬───────────────┘
                                                   │ queries INFORMATION_SCHEMA,
                                                   │ SHOW CREATE FUNCTION, etc.
                                                   ▼
                                        ┌──────────────────────────┐
                                        │ MysqlDiffRenderContext   │
                                        │   .canonicityDeclarations│
                                        └──────────┬───────────────┘
                                                   │ pro renderCreate/Alter/Drop
                                                   ▼
                                        ┌──────────────────────────┐
                                        │ MysqlSequenceCanonicity- │
                                        │   Gate                   │
                                        └──────────────────────────┘
                                                   │
                                                   ▼ Canonical → Proceed
                                                       Drift     → E124-Block
                                                       Missing   → context-abhängig
```

---

## 6. Sub-Slice-Status

- **A** — Port + Adapter ✅ (Commit `84b46a59`).
  `MysqlSequenceCanonicityProbe` Interface +
  `MysqlSequenceCanonicityProbeAdapter` mit `INFORMATION_SCHEMA`-
  Queries für SUPPORT_TABLE, NEXTVAL_ROUTINE, SETVAL_ROUTINE,
  SEQUENCE_ROW und SUPPORT_TRIGGER. MySQL-Fehlercode 1305
  (SP_DOES_NOT_EXIST) und 1360 (TRG_DOES_NOT_EXIST) werden zu
  `MISSING` mapped statt PROBE_RUNTIME_ERROR.
- **B** — Gate + Diagnostic-Codes ✅ (Commit `3d4a0db1`).
  `MysqlSequenceCanonicityGate.decide(declaration, intent)` mit
  drei `Decision`-Varianten (Proceed / Info / Block) und sechs
  Drift-spezifischen Codes (`E124_MYSQL_SEQUENCE_DRIFT_TABLE`,
  `…_ROUTINE`, `…_ROW`, `…_TRIGGER`, `…_MISSING_FOR_ALTER`,
  `…_PROBE_FAILED`). `PlannerBlockerClassifier` mapped alle sechs
  Codes auf `MANUAL_ACTION_REQUIRED`.
- **C** — Stage + Pipeline-Integration ✅ (Commit `583564a3`).
  `MysqlSequenceCanonicityStage` (Outcome Succeeded/Failed/NotRun)
  mit Skip-Pfaden für !execute, file-target, non-MySQL und
  fehlenden Probe-Wires; Exception-Pfad stempelt jede Sequence-Op
  als `PROBE_RUNTIME_ERROR`. Neue Felder
  `DdlGenerationOptions.mysqlSequenceCanonicity` und
  `MigrationDdlResult.mysqlSequenceCanonicity`.
- **D** — Renderer-Integration ✅ (Commit `66b9b963`).
  `MysqlDiffSequenceOps.canonicityBlocks(op, intent, ctx)` ruft das
  Gate pro op-id-gefiltertem Declaration auf; First-Block-Wins,
  Info-Decisions emittieren INFO-Diagnostics. Per-Op-Intent:
  Create→CREATE, Alter→ALTER, Drop→DROP, Rename→ALTER. Drift-Tests
  in eigener `MysqlDiffSequenceOpsDriftGateTest.kt` (Detekt
  LargeClass split).
- **E** — Datei-zu-Datei-Modus + Report-Felder ✅
  (Commit `3db54122`). `MigrationPreflightPlanner` emittiert
  SEQUENCE_ROW `NOT_RUN_FILE_TARGET` / `NOT_RUN_POLICY`
  Declarations pro Sequence-Op; `SchemaMigrateRenderPipeline`
  threadet den Probe optional, fällt sonst auf Pre-Planning zurück.
  `SchemaMigrateReport` carriert `mysqlSequenceCanonicity` als
  neues View-DTO.
- **F** — Closing ✅ (dieser Commit). Plan-Doc nach `done/`,
  CHANGELOG-Eintrag unter "Added".

---

## 7. Akzeptanzkriterien (final)

- [x] `MysqlSequenceCanonicityProbe`-Port + Adapter implementiert.
- [x] Drift in `dmg_sequences`-Zeile → `E124_MYSQL_SEQUENCE_DRIFT_ROW`
      mit strukturiertem Feld-Diff (`driftField` / `expected` /
      `actual`).
- [x] Drift in `dmg_nextval` / `dmg_setval` Routine-Body →
      `E124_MYSQL_SEQUENCE_DRIFT_ROUTINE` mit Marker-Hinweis
      (`driftField = "body_marker"`).
- [x] Drift in Trigger-Body → `E124_MYSQL_SEQUENCE_DRIFT_TRIGGER`
      mit Body-Marker-Hinweis.
- [x] Datei-zu-Datei-Mode unverändert (kein Probe-Aufruf;
      Pre-Planner emittiert `NOT_RUN_FILE_TARGET` Declarations).
- [x] Unit-Tests für Port-Adapter, Gate, Stage, Renderer-Gate,
      Pre-Planner.
- [x] `make docker-check` grün.

### Carve-out

- **testcontainers-Integration**: Ein End-to-End-Test gegen einen
  realen MySQL-Server (testcontainers oder gleichwertig) ist in
  diesem Slice **nicht** enthalten. Die Unit-Tests verwenden MockK
  für JDBC-Primitive (Connection / Statement / ResultSet), wodurch
  alle Probe-Pfade isoliert pinning sind. Eine echte Live-DB-
  Validation des Drift-Checks ist ein eigener Folge-Slice, sobald
  d-migrate generell eine testcontainers-MySQL-Suite hat — das
  trifft nicht nur diesen Drift-Check, sondern jeden MySQL-
  Adapter im Projekt gleichermaßen.

---

## 8. Hinweise für Reviewer

- Adapter-Pattern strikt nach F.5 E.3.
- Drift-Report im strukturierten JSON-Format
  (`migration-plan.v1`-kompatibel) über das neue Feld
  `report.mysqlSequenceCanonicity[]`.
- Op-by-op Probe ist OK; in der Praxis sind Sequence-Ops selten so
  zahlreich, dass eine Batch-Probe nötig wäre.
