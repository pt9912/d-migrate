# Phase E0.6 — Gate-Entscheidung

> **Milestone**: 0.9.6 — Beta: MCP-Server
> **Phase**: E0.6 (`Gate-Entscheidung dokumentieren`)
> **Status**: Initial 2026-05-05 `Blocked` → Re-Stempel 2026-05-05 nach
> E0.7.5 zu **`Go`**.
> **Hauptplan**: `../done/ImpPlan-0.9.6-E0.md` §7.6, §9, §10
> **Matrix**: `ImpPlan-0.9.6-E0-Side-Effect-Matrix.md`

---

## 1. Verdict

**`Go`** — die Pre-Phase-E-Arbeit aus AP E0.7
([`ImpPlan-0.9.6-E0.7.md`](./ImpPlan-0.9.6-E0.7.md)) ist abgeschlossen
und Hauptplan §9 ist erfüllt: jeder atomar-nicht-cancelbare
Driver-Call hat ein belegtes Timeout-/Laufzeitfenster und ein
gemessenes E0-Cancel-Reaktionsbudget `<= 30s`.

Phase E darf gemäß Hauptplan §10 starten.

### 1.1 Was sich gegenüber dem Initial-Verdict geändert hat

Initial `Blocked` (2026-05-05): kein Driver-Adapter setzte
`Statement.setQueryTimeout(...)` oder `Connection.setNetworkTimeout(...)`,
Plan §9 "belegtes Timeout-Fenster" nicht erfüllt.

Re-Stempel `Go` nach E0.7-Abschluss:

- AP E0.7.1 (Commit `72b8a9f`): `PoolSettings.statementTimeoutMs` und
  `networkTimeoutMs` mit Default `30_000` + `init {}`-Validation.
- AP E0.7.2 (Commit `c5a70e6`): driver-spezifischer `connectionInitSql`
  pro Dialekt — PostgreSQL `SET statement_timeout`, MySQL
  `SET SESSION MAX_EXECUTION_TIME`, SQLite `PRAGMA busy_timeout`.
- AP E0.7.3 (Commit `15f3e45`): common `TimeoutDecoratedConnection`
  in `driver-common`. Wraps jede `pool.borrow()`-Connection und setzt
  `setQueryTimeout(ceil(ms/1000))` auf jedem `createStatement`/
  `prepareStatement`/`prepareCall`-Overload. `setNetworkTimeout(...)`
  bindet zusätzlich `DatabaseMetaData.getPrimaryKeys`-Pfade in
  PostgreSQL/MySQL-Writern.
- AP E0.7.4 (Commit `3fe0508`): Bench-Tests pro Driver +
  `JdbcMetadataSessionTimeoutTest` (default-CI) belegen empirisch
  + via Capturing-Connection, dass jeder Driver-Call innerhalb des
  Budgets abbricht.
- AP E0.7.5: Side-Effect-Matrix Section 6 final-klassifiziert
  (`blocked = 0`, `go = ~74`); diese Verdict-Aktualisierung.

Die Wurzel-Diagnose des Initial-Verdicts hat sich bestätigt: die
Auflösung war eine **Adapter-Konfigurationsänderung**, kein
Port-Vertrag-Wechsel. Plan §7.6 wurde respektiert; keine neue
Cancel-Interpretation, keine Port-Vertrags-Erweiterung.

---

## 2. Was E0 nachgewiesen hat (`go`-Pfade)

### 2.1 Adapterneutraler Cancel-Vertrag

- `dev.dmigrate.core.cancel.{CancellationToken, CancellationTokenSource,
  OperationCancelledException}` in `hexagon:core` (zero deps, AP E0.1).
- `TestCancellationTokenSource` Fixture mit `cancelAfterCheckpoints(n)` für
  deterministische Tests ohne Wall-Clock-Sleep.
- Thread-/task-sichere Sichtbarkeit über `AtomicReference<State>`-CAS;
  idempotentes `cancel(...)`, erster Grund gewinnt; cross-thread
  Visibility-Test belegt.
- Kanonischer typisierter Carrier ist `OperationCancelledException`;
  keine Result-Variante in den vier Runnern.

### 2.2 Token-Propagation durch alle vier langlaufenden Runner

`SchemaReverseRunner`, `DataProfileRunner`, `DataImportRunner`,
`DataTransferRunner` akzeptieren `cancellationToken: CancellationToken =
CancellationToken.none()`-Param mit Bestands-CLI-Default-Backward-
Kompatibilität (AP E0.3).

Token erreicht jede tiefste relevante Loop-/Port-Grenze (Plan §7.3):

- Profile: `ProfileDatabaseService.profile` → `ProfileTableService.profile`
  → `profileColumn` (Identitäts-Test in
  `ProfileDatabaseServiceCancelPropagationTest`).
- Import: `DataImportRunner.execute` →
  `ImportStreamingInvoker.execute` → `ImportExecutor`-Lambda über
  `ImportExecutionContext.cancellationToken` →
  `StreamingImporter.import` → `TableImportParams.cancellationToken`
  (Identitäts-Test in 3 Tests).
- Transfer: `DataTransferRunner.execute` →
  `TransferExecutionContext.cancellationToken` → `TransferExecutor`
  (Identitäts-Test in `DataTransferRunnerCancelPropagationTest`).

CLI-`DataImportCommand`-Lambda zieht `ctx.cancellationToken` und reicht
an `streamingImporter.import` weiter — keine MCP-Abhängigkeit in den
Runnern.

### 2.3 Inter-Call-Cancel-Stop in allen vier Pipelines

**Reverse** (AP E0.4 — `SchemaReverseRunnerCancelCheckpointTest`):

- Cancel vor Introspection → keine Pool-Allokation, keine Reader-Read,
  kein Schema-Artefakt, kein Report.
- Cancel zwischen Introspection und Schema-Publish → keine
  Schema-Datei.
- Cancel zwischen Schema-Publish und Report-Publish → kein Report.
- `OperationCancelledException` → Exit 130 (Plan §4.5), niemals als
  Connection-/Metadata-Fehler über generischen `catch (e: Exception)`-
  Pfad mit Exit 4.

**Profile** (AP E0.4 — `ProfileServiceCancelCheckpointTest`,
`DataProfileRunnerCancelCheckpointTest`):

- Cancel vor `listTables` halt vor Introspection.
- Cancel zwischen Tabellen-Iterationen startet keine weitere
  Tabellen-Profilierung.
- Cancel zwischen `listColumns` und `rowCount`, zwischen Spalten,
  zwischen `columnMetrics` und `topValues` startet jeweils keinen
  weiteren Side Effect.
- Cancel vor `reportWriter` schreibt keinen Profile-Report.
- `OperationCancelledException` wird VOR `catch (ProfilingException)`
  und `catch (Exception)` gefangen → Exit 130, niemals 5.

**Import** (AP E0.5 (1/3) — `DataImportRunnerCancelCheckpointTest`,
`StreamingImporterCancelCheckpointTest`,
`TableImporterCancelCheckpointTest`):

- `DataImportRunner` Pre-Pool-Checkpoint + outer-130-Mapping.
- `ImportStreamingInvoker.execute` re-throws `OperationCancelledException`
  vor `catch (Throwable)` — kein Exit 5 für Cancel.
- `StreamingImporter` checkpoints vor `RunStarted`-Progress, jeder
  Tabellen-Iteration und vor `onTableCompleted`-Callback.
- `TableImporter.prepareImport` checkpoints vor `readerFactory.create`,
  `writer.openTable`, Reporter-`ImportTableStarted`, erster
  `nextChunk()`.
- `TableImporter.skipCommittedChunks` (Resume-Skip) checkpoints vor
  jedem skip-`nextChunk()` (Plan §4.6: kein neuer Fortschritts-
  Checkpoint nach Cancel).
- `TableImporter` pre-finish-Checkpoint vor `session.finishTable()`.
- `importChunks` chunk-loop-Checkpoints **außerhalb** der drei
  chunk-failure try/catch-Blöcke (Plan §4.5: Cancel darf nicht in
  `handleChunkFailure` enden). `commitAndAccount` ist als private
  Helper-Funktion extrahiert, damit der Checkpoint vor
  `runCatching { onChunkCommitted(...) }` außerhalb jedes try liegt.

**Transfer** (AP E0.5 (2/3) — `DataTransferRunnerCancelCheckpointTest`,
`TransferExecutorCancelCheckpointTest`):

- `DataTransferRunner` outer-130-Mapping + Cancel-Filter in Schema-Read-
  und Transfer-Execute-catches (kein Exit 4 oder 5 für Cancel).
- `TransferExecutor` Tabellen-Loop-Checkpoint und pre-onTableTransferred-
  Checkpoint.
- `transferTable` checkpoints vor `reader.streamTable`, vor
  `writer.openTable`, im Chunk-Loop vor Normalisierung, vor
  `session.write`, vor `session.commitChunk`, vor
  `session.finishTable`.

### 2.4 Cleanup-Vertrag

- `pool.use { }` und `session.use { }` (`AutoCloseable.use` Pattern) in
  Reverse, Profile und Transfer schließen Pools/Sessions sauber, auch
  wenn der innere Block `OperationCancelledException` wirft.
- `DataImportRunner.execute` `try { ... } finally { runCatching {
  pool.close() } }` schließt den Pool nach Cancel.
- `TableImporter.import` `try { ... } catch (Throwable) { primaryFailure
  = throwable; throw throwable } finally { closeAndCollect(reader, ...);
  closeAndCollect(session, ...) }` — Cleanup-Failures werden als
  suppressed exceptions an `OperationCancelledException` angehangen,
  ohne diese zu überschreiben (Plan §4.5).
- `DataProfileRunner` `finally { pool.close() }` läuft auch nach
  Cancel.
- `TableImportSession.close()` führt JDBC-Auto-Rollback aus (PostgreSQL/
  MySQL) bzw. expliziten `ROLLBACK` (SQLite-WAL); kein dediziertes
  `abort()`-API auf Vertragsebene notwendig (E0.5-Spike-Verifikation).

### 2.5 Anschluss an Phase E (typisiertes Cancel-Outcome)

Phase E kann das `OperationCancelledException` an jeder Runner-Grenze
unmittelbar in:

- Jobstatus-Transition `running → cancelled` (`spec/job-contract.md`)
- Audit-Event `JOB_CANCEL_OBSERVED` mit `reason` aus
  `OperationCancelledException.reason`
- Worker-Handle-Cleanup über die in `executeWithCancel` etablierten
  `finally`-Blöcke

übersetzen — ohne weitere fachliche Interpretation. Hauptplan §4.4
ist erfüllt: das typisierte Cancel-Outcome ist eindeutig, der
Anschluss-Punkt ist die jeweilige `execute(...)`-Methode.

---

## 3. Pflicht-Pre-Phase-E-Arbeit (`Blocked`-Auflösung) — abgeschlossen

Hauptplan §9 fordert für atomar-nicht-cancelbare Calls "ein belegtes
Timeout-/Laufzeitfenster, ein gemessenes E0-Cancel-Reaktionsbudget von
`<=30 s`, keine ungebundenen Retry-/Reconnect-Loops und hinterlassen
nach Timeout keine offenen Ressourcen". Diese Bedingung war zum
Initial-Stempel `Blocked` (2026-05-05) nicht erfüllt — kein Driver-
Adapter setzte `Statement.setQueryTimeout(...)` oder
`Connection.setNetworkTimeout(...)`.

Die Auflösung erfolgte in
[`ImpPlan-0.9.6-E0.7.md`](./ImpPlan-0.9.6-E0.7.md) ohne
Port-Vertrags-Wechsel und ohne neue Cancel-Interpretation:

### 3.1 AP E0.7 Status: ✅ abgeschlossen

| AP | Status | Commit | Was |
| --- | --- | --- | --- |
| E0.7.1 | ✅ | `72b8a9f` | `PoolSettings.statementTimeoutMs` + `networkTimeoutMs` Felder mit Default `30_000` und `init {}`-Validation; `connection-config-spec.md` §2.2. |
| E0.7.2 | ✅ | `c5a70e6` | `connectionInitSqlFor(dialect, ms)` in `HikariConnectionPoolFactory`. PostgreSQL `SET statement_timeout`, MySQL `SET SESSION MAX_EXECUTION_TIME`, SQLite `PRAGMA busy_timeout`. |
| E0.7.3 | ✅ | `15f3e45` | Common `TimeoutDecoratedConnection` (13 Statement-Overload-Decorators) + `Connection.setNetworkTimeout(...)` mit `SQLFeatureNotSupportedException`-Resilienz; `timeoutSecondsOf` rundet sub-second auf. Decoder ist transparent für alle Adapter-Module. |
| E0.7.4 | ✅ | `3fe0508` | Bench-Tests pro Driver (`E07PostgresTimeoutBench`, `E07MysqlTimeoutBench`, `E07SqliteTimeoutBench` mit `@Tag("integration")`); `JdbcMetadataSessionTimeoutTest` (default-CI) belegt Profiling-/Schema-Reader-Coverage über den common Layer. |
| E0.7.5 | ✅ | (dieser Commit) | Side-Effect-Matrix Section 6 final-klassifiziert (`blocked = 0`, `go ~74`); diese Verdict-Aktualisierung von `Blocked` zu `Go`. |
| E0.7.6 | ⏳ pending | — | Move E0.7 + Side-Effect-Matrix + Gate-Decision nach `done/`; Phase-E-Plan in `in-progress/` öffnen. |

### 3.2 Measurement-Evidence

Pro Driver ein Bench-Test, der die `<= 30s`-Schwelle empirisch
verifiziert:

| Driver | Test | Mechanismus | Erwartung |
| --- | --- | --- | --- |
| PostgreSQL | `E07PostgresTimeoutBench` (`test/integration-postgresql`) | Testcontainer `postgres:16-alpine` + `SELECT pg_sleep(60)` + `statementTimeoutMs = 5000` | `PSQLException` SQLState `57014` in `< 6s`; `pool.activeConnections() <= 1` nach Cancel; healthy `SELECT 1` läuft danach |
| MySQL | `E07MysqlTimeoutBench` (`test/integration-mysql`) | Testcontainer `mysql:8.0` + `SELECT SLEEP(60)` + `MAX_EXECUTION_TIME = 5000` | `SQLException` (entweder `MySQLTimeoutException` für `MAX_EXECUTION_TIME` oder Statement-Level für Writes) in `< 6s`; analog Cleanup |
| SQLite | `E07SqliteTimeoutBench` (`adapters/driven/driver-sqlite`) | In-memory + 100M recursive CTE mit `MAX(n)`-Aggregation + `statementTimeoutMs = 2000` | `SQLException` via `sqlite3_interrupt(...)` in `< 4s`; analog Cleanup |

Default-Token-Regressionsguard pro Driver belegt: `statementTimeoutMs =
30000` (Default) lässt fast queries (`SELECT 1`) unbeeinflusst durch.

Profiling-/Schema-Reader-Coverage (`JdbcMetadataSessionTimeoutTest` in
`driver-common`, default-CI): 6 Tests via `CapturingConnection`-by-
delegate belegen, dass `queryList`/`querySingle`/`execute`/`executeBatch`
durch den common Layer laufen — alle erzeugten Statements tragen
`queryTimeout = ceil(ms/1000)`.

Bench-Tests laufen in `make integration` (Docker-Container mit JDK 21
+ Testcontainers); Default-5min-CI bleibt unbelastet
(`kotest.tags = !integration & !perf`).

### 3.3 Erwartete Matrix-Bewegung

### 3.3 Erwartete Matrix-Bewegung — eingetreten

Nach AP E0.7.5:

- Alle `blockierend`-Zeilen in Section 6 wechseln zu `atomic-nicht-
  cancelbar` mit `bound = 30000ms`, `cancel_budget_ms = 30000`,
  `measurement_evidence = <Bench-Test-Name>`, `gate = go`.
- Schnellstatistik: `go = ~74`, `blocked = 0`,
  `tentative-go = 0`, `go_followup = 0`. Gate-Verdict wechselt von
  `Blocked` auf `Go`.

---

## 4. Was E0 explizit **nicht** liefert (Phase-E- oder spätere Iteration)

- **`job_cancel`-Tool** als MCP-Adapter-Tool. Phase E §4.1 (Hauptplan
  §8 Phase E).
- **Jobstatus-Transition** zu `cancelled`, **Audit-Event-Emission**
  (`JOB_CANCEL_REQUESTED`/`JOB_CANCEL_OBSERVED`), **Worker-Handle-
  Registry**. Phase E.
- **`abort()`-API** auf Vertragsebene für `TableImportSession`. Heute
  nicht nötig — `session.close()` reicht (siehe §2.4). Eventuell
  notwendig für Phase F (data-import, data-transfer als policy-pflichtige
  MCP-Tools), wenn dort feinere Cleanup-Granularität gefordert wird.
- **In-flight-write Cleanup** für `schemaWriter` und `reportWriter` (also
  `Files.deleteIfExists(output)` bei Cancel mid-write). Heute
  `go_followup` in Reverse-Sektion. Phase F oder eigener AP.
- **Remote-FS Format-Reader** (S3, HTTP). Nicht 0.9.6.
- **Token-Param am Reader-Iterator-Rand** für inter-Chunk-Cancel ohne
  Timeout-Abhängigkeit. 0.9.7+ (E0.7-§9 Folgearbeiten).

---

## 5. Anschluss an Phase E

Phase E (Hauptplan §8 Phase E) implementiert:

- `job_cancel`-Tool mit Berechtigungspruefung
- `cancelled`-Status-Transition im Jobkern
- Worker-Handle-Registry / Job-Orchestrierung
- Audit-Events für Cancel-Annahme und Cancel-Outcome
- Einbindung der hier nachgewiesenen Runner-Checkpoints in produktive
  Job-Worker

Mapping-Tabelle für Phase E:

| Runner-Cancel-Outcome | Phase-E-Wirkung |
| --- | --- |
| `OperationCancelledException` von `execute(...)` mit `reason` | Status-Transition `running → cancelled`; Audit-Event `JOB_CANCEL_OBSERVED` mit `reason` aus Exception; Worker-Handle gibt Token-Source frei. |
| Exit-Code 130 von `execute(...): Int` | CLI-direkt-Aufruf-Pfad: kein Job-Store-Update (CLI-only); Audit-Event optional. |
| `OperationCancelledException` mit `cause` (rare; aus suppressed cleanup-Failure) | Status `cancelled` mit `cleanupRisk` Flag im Audit-Event; Job-Store-Eintrag bleibt korrekt. |

Phase E **muss kein neues Cancel-Vertrag-Element interpretieren** — alles
typisiert.

---

## 6. Phase-E-Start-Freigabe

### 6.1 Phase E darf starten

Mit Verdict `Go` ist Hauptplan §10 erfüllt: "Phase E darf erst auf E0
aufbauen, wenn das Gate mindestens `Go mit Nacharbeiten` erreicht und
die harte Semantik aus Abschnitt 9 nicht verletzt ist." E0.7 hat die
harte Semantik nachgewiesen.

Empfohlene nächste Schritte (Project-Management):

1. **AP E0.7.6 Move-Operation**: `ImpPlan-0.9.6-E0.7.md`,
   `ImpPlan-0.9.6-E0-Side-Effect-Matrix.md` und dieses Dokument nach
   `done/` verschieben; Cross-Refs in offenen E/F/G-Plänen anpassen.
2. **Phase-E-Plan eröffnen**: `in-progress/ImpPlan-0.9.6-E.md` (heute
   in `open/`) wird zum aktiven Plan. Hauptthemen aus
   `implementation-plan-0.9.6.md` §8 Phase E:
   - `job_cancel`-Tool als MCP-Adapter-Tool
   - Jobstatus-Transition `running → cancelled`
   - Worker-Handle-Registry
   - Audit-Events `JOB_CANCEL_REQUESTED` / `JOB_CANCEL_OBSERVED`
   - Einbindung der hier nachgewiesenen Runner-Checkpoints in
     produktive Job-Worker
3. **Phase-E-Bench in CI aktivieren** (optional): `.github/workflows/
   integration.yml` läuft `make integration` und exerziert die
   E0.7.4-Bench-Tests bei jedem PR — empfehlenswert um Driver-Updates
   gegen die `<= 30s`-Schwelle zu schützen.
