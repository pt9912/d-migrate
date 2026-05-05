# Phase E0.6 — Gate-Entscheidung

> **Milestone**: 0.9.6 — Beta: MCP-Server
> **Phase**: E0.6 (`Gate-Entscheidung dokumentieren`)
> **Status**: Final 2026-05-05
> **Hauptplan**: `ImpPlan-0.9.6-E0.md` §7.6, §9, §10
> **Matrix**: `ImpPlan-0.9.6-E0-Side-Effect-Matrix.md`

---

## 1. Verdict

**`Blocked`** — der E0-Spike kann die Schwelle aus Hauptplan §9 für die
monolithischen Driver-Calls nicht nachweisen. Konkret fehlt: *belegtes
Timeout-/Laufzeitfenster und gemessenes E0-Cancel-Reaktionsbudget `<= 30s`
für jeden atomar-nicht-cancelbaren Driver-Call.*

Phase E darf gemäß Hauptplan §10 nicht starten, solange die Pre-Phase-E-
Arbeit aus §3 dieses Dokuments nicht abgeschlossen ist.

Die Wurzel ist eine einzelne, eng abgegrenzte Adapter-Konfigurationslücke,
**nicht** eine Vertrags- oder Semantiklücke. Der vorgeschlagene Pre-Phase-E-
Plan in §3 hebt das Gate auf `Go` ohne neue Cancel-Interpretation und
ohne Port-Vertrag-Wechsel.

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

## 3. Pflicht-Pre-Phase-E-Arbeit (`Blocked`-Auflösung)

Hauptplan §9 fordert für atomar-nicht-cancelbare Calls "ein belegtes
Timeout-/Laufzeitfenster, ein gemessenes E0-Cancel-Reaktionsbudget von
`<=30 s`, keine ungebundenen Retry-/Reconnect-Loops und hinterlassen
nach Timeout keine offenen Ressourcen". Heute setzt **kein** Driver-
Adapter `Statement.setQueryTimeout(...)` oder
`Connection.setNetworkTimeout(...)`. Dadurch sind alle Driver-
monolithic-Calls in Section 6 der Side-Effect-Matrix als **blockierend**
klassifiziert.

### 3.1 Eng abgegrenzter Pre-Phase-E-AP

Vorschlag: **AP E0.7 Driver-Adapter-Timeout-Konfiguration** (separater
Plan-Eintrag oder Phase-E §0). Drei Driver, eine zentrale Stelle pro
Driver:

| Driver | Konfigurations-Punkt | Konkrete Änderung |
| --- | --- | --- |
| postgresql | `adapters/driven/driver-postgresql` Statement-/Pool-Bootstrap | `setQueryTimeout(30)` an gemeinsamer `prepareStatement(...)`-Stelle; `Connection.setNetworkTimeout(executor, 30000)` in HikariCP-Connection-Init-SQL oder per `connection-init-sql`. |
| mysql | `adapters/driven/driver-mysql` analog | wie postgresql |
| sqlite | `adapters/driven/driver-sqlite` analog | `setQueryTimeout(30)` (SQLite JDBC unterstützt `busy_timeout` PRAGMA für Lock-Wait, sonst Statement-Timeout); `setNetworkTimeout` für File-DB nicht relevant |

Konfiguration ist **kein** Port-Vertrag-Wechsel — die Port-API in
`hexagon:ports-read`/`ports-write` bleibt unverändert. Plan §7.6 erlaubt
diese Form von Adapter-Iteration im Rahmen von Phase-E-Nacharbeit, **wenn**
die harte Side-Effect-Stop-Semantik bereits ohne sie nachgewiesen ist —
und das ist sie (siehe §2 oben).

### 3.2 Measurement-Evidence

Pro Driver ein Bench-Test, der:

1. Eine bewusst lange Query (`SELECT pg_sleep(60)` /
   `SELECT SLEEP(60)` / langer Range-Scan in SQLite) startet.
2. Verifiziert, dass der Driver-Adapter die Query nach `<= 30s` mit
   `SQLTimeoutException` beendet, ohne Retry-Loop und ohne offene
   Connection.
3. Konzeptionell: `gemessenes E0-Cancel-Reaktionsbudget` aus Hauptplan
   §4.1.

Diese Tests gehören in `test/integration-postgresql`,
`test/integration-mysql` und `adapters/driven/driver-sqlite` (separater
Test-Spec). Sie sind nicht Teil der 5-Minuten-Default-CI, aber der
`make integration`-Target wäre der natürliche Ort.

### 3.3 Erwartete Matrix-Bewegung

Nach AP E0.7:

- Alle `blockierend`-Zeilen in Section 6 wechseln zu **`atomic-nicht-
  cancelbar`** mit `bound = 30000ms`, `cancel_budget_ms = 30000`,
  `measurement_evidence = <Bench-Test-Name>`, `gate = go`.
- Die ~10 noch `blocked`-Zeilen in den Pipeline-Sektionen (`reader.read`,
  `streamTable`, `openTable`, `finishTable`, `listTables`, `listColumns`,
  `data.*`) wechseln symmetrisch zu `go`.
- Schnellstatistik: `go ~35 / go_followup ~0 / blocked 0 / tentative-go
  4`. Gate-Verdict wechselt von `Blocked` auf `Go`.

---

## 4. Was E0 explizit **nicht** liefert (Phase-E- oder spätere Iteration)

- **`job_cancel`-Tool** als MCP-Adapter-Tool. Phase E §4.1 (Hauptplan
  §8 Phase E).
- **Jobstatus-Transition** zu `cancelled`, **Audit-Event-Emission**
  (`JOB_CANCEL_REQUESTED`/`JOB_CANCEL_OBSERVED`), **Worker-Handle-
  Registry**. Phase E.
- **Connection-Init-SQL für `setNetworkTimeout`** in HikariCP. Pre-Phase-E
  AP E0.7 §3.1.
- **Driver-Adapter-Bench-Tests** für Timeout-Verifikation. Pre-Phase-E
  AP E0.7 §3.2.
- **`abort()`-API** auf Vertragsebene für `TableImportSession`. Heute
  nicht nötig — `session.close()` reicht (siehe §2.4). Eventuell
  notwendig für Phase F (data-import, data-transfer als policy-pflichtige
  MCP-Tools), wenn dort feinere Cleanup-Granularität gefordert wird.
- **In-flight-write Cleanup** für `schemaWriter` und `reportWriter` (also
  `Files.deleteIfExists(output)` bei Cancel mid-write). Heute
  `go_followup` in Reverse-Sektion. Phase F oder eigener AP.
- **Remote-FS Format-Reader** (S3, HTTP). Nicht 0.9.6.

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

## 6. Empfohlene nächste Schritte (Review-Input)

1. **AP E0.7 Driver-Adapter-Timeout-Konfiguration** als separater Plan-
   Eintrag öffnen (oder als Phase-E §0 binden). Geschätzter Aufwand: drei
   Adapter-Edits + drei Bench-Tests + Matrix-Update. ~1–2 Commits.
2. **Side-Effect-Matrix-Wrap nach AP E0.7**: alle `blockierend`-Zeilen
   wechseln zu `go`; Verdict in diesem Dokument von `Blocked` auf `Go`
   re-stempeln.
3. **Phase E starten** sobald Verdict `Go`.

Alternativvorschlag (Project-Management-Entscheidung, nicht
technisch begründet): Override des `Blocked`-Verdicts auf `Go mit
Nacharbeiten` mit AP E0.7 als formaler Phase-E-Pre-Work. Plan §7.6 lässt
das nicht ausdrücklich zu (zitiert "fehlende Timeout-/Laufzeitgrenze" als
unzulässig für `Go mit Nacharbeiten`), aber ist als Projekt-Ausnahme
denkbar, wenn die Bench-Tests in Phase E selbst liegen würden. Diese
Variante hat das Risiko, dass Phase E ohne belegte Timeout-Garantien
beginnt.

Ich empfehle Option A (AP E0.7 vor Phase E).
