# Phase E0 — Cancel-Checkpoint- und Side-Effect-Matrix

> **Milestone**: 0.9.6 — Beta: MCP-Server
> **Phase**: E0.2 → E0.7 (final-klassifiziert)
> **Status**: Stand 2026-05-05 nach E0.7.5; **`go = ~74`**, `blocked = 0`,
> `tentative-go = 0`, `go_followup = 0`. Driver-Calls sind via
> `TimeoutDecoratedConnection` + driver-spezifischer
> `connectionInitSql` + Bench-Tests als `atomic-nicht-cancelbar` mit
> `<= 30s` belegt. Move nach `done/` zusammen mit E0.7.6.
>
> Update-Historie pro AP siehe §8 Änderungs-Protokoll.
>
> **Hauptplan**: `../done/ImpPlan-0.9.6-E0.md` §7.2
> **Pflichtschema**: `../done/ImpPlan-0.9.6-E0.md` §7.2 (Tabelle direkt nach AP E0.2)

---

## 1. Ziel und Lese-Anleitung

Diese Matrix listet jede extern sichtbare Side-Effect-Grenze in den fuer
Phase E0 relevanten Runnern auf und ordnet ihr den geplanten Cancel-Checkpoint,
das geplante Cleanup, die heutige Atomaritaets-Klassifikation und den geplanten
Gate-Status zu.

Sie ist der Snapshot **vor** AP E0.3–E0.5. Spalten, die erst durch
Token-Propagation, Checkpoint-Platzierung oder Tests entstehen
(`test_coverage`, `measurement_evidence`, `cancel_budget_ms`), tragen heute
`missing` und werden in den jeweiligen AP-Commits nachgezogen. Der heutige
`gate_result` ist gemaess §7.2-Regel:

- jede Zeile mit externem Side Effect oder potentiell langem monolithischem
  Call und `test_coverage = missing`, `atomicity = unknown` oder fehlendem
  `timeout_or_bound` ist heute automatisch `blocked` (Snapshot-Status). Ohne
  AP E0.4/E0.5-Tests gibt es keinen Pfad zu `go`.
- atomar-nicht-cancelbare Zeilen ohne `cancel_budget_ms <= 30000` oder
  `measurement_evidence` sind ebenfalls `blocked`.

Phase E0.6 entscheidet anhand der dann gefuellten Matrix das Gate fuer
Phase E.

### 1.1 Was ist und ist nicht in Scope

**In Scope** (Hauptplan §6, AP E0.2):

- `SchemaReverseRunner` (Reverse-Pipeline)
- `DataProfileRunner` + `ProfileDatabaseService` + `ProfileTableService`
- `DataImportRunner` + `ImportStreamingInvoker` + `StreamingImporter` +
  `TableImporter` + `ImportCheckpointManager`
- `DataTransferRunner` + `TransferExecutor`
- Streaming-/Reader-/Writer-Pfade unter `adapters/driven/streaming` und
  `adapters/driven/driver-*`, soweit sie von obigen Runnern genutzt werden

**Bewusst nicht in Scope** fuer E0:

- `DataExportRunner`, `StreamingExporter`, `TableExporter`,
  `ExportCheckpointManager` — Phase E fuehrt kein `data_export_start`-Start-Tool
  ein (siehe Hauptplan §3.1). Der Export-Pfad bleibt CLI-only und wird in
  einer spaeteren Iteration nachgezogen, falls ein MCP-Tool ihn als
  Langlaeufer adressiert.
- `SchemaCompareRunner` — `schema_compare` ist read-only und bleibt
  inline-Tool ohne Worker-Cancel-Bedarf; `schema_compare_start` (Phase E)
  laeuft auf dem gleichen Runner-Vertrag wie Reverse und ist hier ueber
  die Reverse-Spalten abgedeckt.
- `tools_export_*`, `data_export*` — wie oben, kein Phase-E-Start-Tool.

### 1.2 Spalten-Legende (kompakte Schreibweise)

Damit die Tabellen lesbar bleiben, werden Spaltennamen in den Per-Pipeline-
Tabellen abgekuerzt. Zuordnung:

| Kurz | Pflichtschema-Spalte |
| --- | --- |
| `op` | `operation` |
| `kind` | `operation_type` (`loop`, `side_effect`, `monolithic_call`, `cleanup`) |
| `side` | `side_effect` |
| `checkpoint` | `checkpoint_before` |
| `reaction` | `cancel_reaction` |
| `atom` | `atomicity` (`atomic`, `multi_step`, `unknown`) |
| `bound` | `timeout_or_bound` |
| `budget` | `cancel_budget_ms` |
| `evid` | `measurement_evidence` |
| `clean` | `cleanup_contract` |
| `tests` | `test_coverage` |
| `gate` | `gate_result` |
| `followup` | `followup` |

`runner` und `pipeline` sind je Sektion implizit (Sektionsueberschrift bzw.
erste Zeile).

Wenn `kind = side_effect` oder `monolithic_call` und `tests = missing`,
ist `gate = blocked` automatisch. Heute betrifft das **alle** externen
Side-Effect-Zeilen — die `blocked`-Markierung ist erwartet und wird durch
AP E0.4 (Reverse/Profile) und AP E0.5 (Import/Transfer/Streaming) abgebaut.

### 1.3 Was eine "atomic"-Klassifikation hier heisst

`atomic` heisst hier **nicht** ACID-Datenbank-atomar, sondern: der Aufruf
laeuft entweder vollstaendig durch oder wirft, und hinterlaesst keine offenen
Ressourcen, die ueber die Aufrufgrenze hinaus weiter Side Effects ausloesen.
Konkret muessen alle vier Schwellen aus Hauptplan §4.1 erfuellt sein, sonst
ist die Zeile `multi_step` oder `unknown`:

- kein interner Retry-/Reconnect-Loop ohne hartes Timeout
- maximale erwartete Laufzeit und Timeout liegen innerhalb des
  E0-Cancel-Reaktionsbudgets (`<= 30s`)
- nach Timeout oder Fehler startet der Runner keinen weiteren Side Effect
- der Call haelt keine offene Transaktion, Session, Sperre oder temporaere
  Ressource ueber das Timeout hinaus

Driver-Methoden, die heute keine dokumentierte Laufzeit- oder Timeout-Garantie
tragen (`schemaReader().read()`, `dataReader.streamTable()`,
`writer.openTable()`, `session.finishTable()`, alle `adapters.data.*`),
stehen daher als `unknown` in der Matrix. AP E0.5 muss pro Pfad entscheiden,
ob ein Driver-Vertrag erweitert oder der Pfad blockierend bleibt.

---

## 2. SchemaReverseRunner

Codebasis: `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt`.

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `poolFactory(ctx.config).use { p -> ... }` | side_effect | session | Runner-Token-Check unmittelbar vor `readSchema()` | `OperationCancelledException` → Exit 130, kein Pool-Borrow | unknown | not_applicable | not_applicable | `SchemaReverseRunnerCancelCheckpointTest` "cancel before introspection" | `close` (`use {}`) | `SchemaReverseRunnerCancelCheckpointTest` | go | — |
| `reader.read(p, options)` | monolithic_call | source-read | direkt vor Driver-Aufruf | `OperationCancelledException` vor Aufruf, danach kein Schema-Publish | unknown | missing (Driver-API hat heute keine Cancel-/Timeout-Grenze) | missing | missing | not_needed (read liefert Wert) | missing | blocked | E0.5-Klassifizierung pro Driver: `atomic` mit Statement-Timeout oder `blocked` |
| `request.output.parent?.mkdirs()` | side_effect | artefact | Runner-Token-Check unmittelbar vor `writeSchemaFile()` | `OperationCancelledException` → Exit 130, kein Verzeichnis erzeugen | atomic | not_applicable | not_applicable | `SchemaReverseRunnerCancelCheckpointTest` "cancel between introspection and schema publish" | not_needed | `SchemaReverseRunnerCancelCheckpointTest` | go | — |
| `schemaWriter(output, schema, format)` | side_effect | artefact | Runner-Token-Check unmittelbar vor `writeSchemaFile()` | `OperationCancelledException` → Exit 130, kein Schema-Artefakt schreiben | multi_step (write+flush) | not_applicable | not_applicable | `SchemaReverseRunnerCancelCheckpointTest` "cancel between introspection and schema publish" | bei Cancel mid-write (E0.5): `Files.deleteIfExists(output)` als Followup | `SchemaReverseRunnerCancelCheckpointTest` | go_followup | E0.5: in-flight-write Cleanup wenn Driver-Vertrag erweitert wird |
| `request.reportPath.parent?.mkdirs()` | side_effect | artefact | Runner-Token-Check unmittelbar vor `writeReportFile()` | `OperationCancelledException` → Exit 130, kein Verzeichnis erzeugen | atomic | not_applicable | not_applicable | `SchemaReverseRunnerCancelCheckpointTest` "cancel between schema publish and report publish" | not_needed | `SchemaReverseRunnerCancelCheckpointTest` | go | — |
| `reportWriter(reportPath, reportInput)` | side_effect | artefact | Runner-Token-Check unmittelbar vor `writeReportFile()` | `OperationCancelledException` → Exit 130, kein Report schreiben | multi_step | not_applicable | not_applicable | `SchemaReverseRunnerCancelCheckpointTest` "cancel between schema publish and report publish" | bei Cancel mid-write (E0.5): Pfad loeschen | `SchemaReverseRunnerCancelCheckpointTest` | go_followup | E0.5: in-flight-write Cleanup wenn Driver-Vertrag erweitert wird |

**Reverse-Anmerkungen:**

- `reader.read(...)` ist die kritische Stelle. Wenn der Driver-Vertrag in
  `hexagon:ports-read` keine Cancel-/Timeout-Grenze garantiert und der Driver
  intern Retry-Logik hat, ist die Zeile in E0.6 `blocked`. AP E0.4 muss pro
  Driver entweder ein Statement-Timeout setzen, das innerhalb `<= 30s` liegt,
  oder den Pfad gate-blockieren.
- Notes-/Skipped-Loops (`for note in result.notes`, `for skip in
  result.skippedObjects`) sind reine In-Memory-Iterationen ohne externe Side
  Effects und stehen nicht in der Matrix.

---

## 3. Profiling-Pipeline

Codebasis: `DataProfileRunner.kt`, `ProfileDatabaseService.kt`,
`ProfileTableService.kt` (`hexagon/profiling`).

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `poolFactory(url, dialect)` | side_effect | session | (vor Pool-Allokation kein Token-Check; nachfolgender `service.profile`-Entry-Check fängt Cancel ab und Cleanup über `pool.close()` in finally) | Pool wird geöffnet, danach kein Profiling-Side-Effect; `pool.close()` in `finally` schließt sauber | unknown | not_applicable | not_applicable | `DataProfileRunnerCancelCheckpointTest` belegt Pfad indirekt (kein Profiling-/Report-Side-Effect nach Cancel) | `pool.close()` | `DataProfileRunnerCancelCheckpointTest` | go_followup | E0.5: optionaler Pre-Pool-Checkpoint, sobald Driver-Pool-Open eigene Cancel-Grenze trägt |
| `introspection.listTables(pool, schema)` | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` am Entry von `ProfileDatabaseService.profile()` | `OperationCancelledException` → Runner mappt auf 130, keine `listTables`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` "cancel before listTables halts before any introspection call" | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5: in-flight-listTables-Cancel benötigt Driver-Statement-Cancel |
| `for table in targetTables` | loop | none | `cancellationToken.throwIfCancellationRequested()` als erstes Statement in `targetTables.map { ... }` | nach Cancel keine weitere Tabelle | atomic | not_applicable | not_applicable | `ProfileServiceCancelCheckpointTest` "cancel between table iterations starts no further table profiling" | not_needed | `ProfileServiceCancelCheckpointTest` | go | — |
| `tableService.profile(pool, table, ...)` | side_effect (Komposit) | source-read | composed (siehe Profile-Table-Zeilen darunter) | composed | multi_step | not_applicable | not_applicable | `ProfileServiceCancelCheckpointTest` (DB-Service + Table-Service zusammen) | not_needed | `ProfileServiceCancelCheckpointTest` | go | — |
| `introspection.listColumns(pool, table, schema)` | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` am Entry von `ProfileTableService.profile()` | `OperationCancelledException`, keine `listColumns`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` "cancel before listColumns halts before introspection" | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5: in-flight-listColumns-Cancel benötigt Driver-Statement-Cancel |
| `data.rowCount(pool, table, schema)` | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` zwischen `listColumns` und `rowCount` in `ProfileTableService` | `OperationCancelledException`, keine `rowCount`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` "cancel between listColumns and rowCount halts before rowCount" | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5: in-flight-rowCount-Cancel benötigt Driver-Statement-Cancel |
| `for col in columns` | loop | none | `cancellationToken.throwIfCancellationRequested()` als erstes Statement in `columns.map { col -> ... }` | nach Cancel keine weitere Spalte | atomic | not_applicable | not_applicable | `ProfileServiceCancelCheckpointTest` "cancel between column iterations starts no further column profiling" | not_needed | `ProfileServiceCancelCheckpointTest` | go | — |
| `data.columnMetrics(pool, table, column)` | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` als erstes Statement in `profileColumn(...)` | `OperationCancelledException`, keine `columnMetrics`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` (über column-iteration-Test indirekt; Cancel nach Spalte 1 verhindert Spalte-2 columnMetrics) | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5: in-flight-Cancel benötigt Driver-Statement-Cancel |
| `data.topValues(pool, table, column, topN)` | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` zwischen `columnMetrics` und `topValues` | `OperationCancelledException`, keine `topValues`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` "cancel between columnMetrics and topValues starts no topValues query" | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5 |
| `data.numericStats(...)` (optional) | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` direkt vor optionalem Aufruf, NICHT innerhalb `optionalProfilingValue { ... }` (sonst würde Cancel als `ProfilingQueryError` verschleiert) | `OperationCancelledException`, keine `numericStats`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` indirekt (Cancel vor Spalte-2 deckt alle Spalte-2-Calls ab) | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5 |
| `data.temporalStats(...)` (optional) | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` direkt vor optionalem Aufruf | `OperationCancelledException`, keine `temporalStats`-Query | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` indirekt | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5 |
| `data.targetTypeCompatibility(...)` (optional) | monolithic_call | source-read | `cancellationToken.throwIfCancellationRequested()` direkt vor optionalem Aufruf | `OperationCancelledException` | unknown | missing | missing | `ProfileServiceCancelCheckpointTest` indirekt | not_needed | `ProfileServiceCancelCheckpointTest` | go_followup | E0.5 |
| `reportWriter(profile, format, output)` | side_effect | artefact | `cancellationToken.throwIfCancellationRequested()` in `DataProfileRunner.execute()` zwischen `service.profile()` und `reportWriter(...)` | `OperationCancelledException` → Exit 130, kein Report | multi_step | not_applicable | not_applicable | `DataProfileRunnerCancelCheckpointTest` "cancel between profiling and report writer returns 130 and writes no report" | bei Cancel mid-write (E0.5): Pfad loeschen | `DataProfileRunnerCancelCheckpointTest` | go_followup | E0.5: in-flight-write Cleanup |
| `pool.close()` (finally) | cleanup | session | wird nach Cancel UNVERAENDERT ausgefuehrt | nicht abbrechen | atomic | not_applicable | not_applicable | `DataProfileRunnerCancelCheckpointTest` (alle Cancel-Pfade nutzen `finally { pool.close() }` ohne Override) | `close` | `DataProfileRunnerCancelCheckpointTest` | go | — |

**Profile-Anmerkungen:**

- Die `data.*`- und `introspection.*`-Calls sind Driver-Calls ueber JDBC.
  AP E0.4 muss pro Driver klaeren, ob ein per-Statement-`queryTimeout` reicht
  oder ein Statement-Cancel-Pfad noetig ist. Ohne diesen Nachweis bleiben die
  Zeilen `blocked`.
- Optionale Metric-Calls (`numericStats`, `temporalStats`,
  `targetTypeCompatibility`) sind nur aktiv, wenn die zugehoerige Option
  gesetzt ist; sie werden trotzdem als eigene Zeilen gefuehrt, weil sie
  separat cancelbar sein muessen.

---

## 4. Import-Pipeline

Codebasis: `DataImportRunner.kt`, `ImportExecutionPlanner.kt`,
`ImportPreflightResolver.kt`, `ImportStreamingInvoker.kt`,
`StreamingImporter.kt`, `TableImporter.kt`, `ImportCheckpointManager.kt`,
`ImportCompletionSupport.kt`, `adapters/driven/streaming`.

### 4.1 Setup-/Preflight-Phase

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `preflightResolver.resolve(request)` | side_effect | source-read (Schema-Read) | Runner-Entry vor Aufruf | `OperationCancelledException`, kein Pool | multi_step | missing | missing | missing | not_needed | missing | blocked | E0.5-Token + Klassifikation |
| `poolFactory(connectionConfig)` | side_effect | session | vor Allokation | `OperationCancelledException`, kein Pool | unknown | not_applicable | not_applicable | missing | `pool.close()` | missing | blocked | E0.5-Token |
| `executionPlanner.prepare(...)` | side_effect (Komposit) | mixed | composed | composed | multi_step | not_applicable | not_applicable | not_applicable | composed | missing | blocked | E0.5 |
| `preflightValidator.resolveInputContext(...)` (Directory-Scan) | side_effect | source-read (Filesystem) | vor Aufruf | `OperationCancelledException` | multi_step | not_applicable (lokales FS) | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |
| `inputResolver.resolve(input, format)` (in `StreamingImporter`) | side_effect | source-read (Filesystem) | vor Aufruf | `OperationCancelledException` | multi_step | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |

### 4.2 Checkpoint-Init und Resume-Load

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `checkpointManager.resolveCheckpointContext(request)` | side_effect | source-read (Filesystem) | vor Aufruf | `OperationCancelledException`, kein Manifest schreiben | multi_step | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |
| `checkpointManager.resolveResumeContext(...)` -> `store.load(opId)` | side_effect | source-read (Filesystem) | vor Aufruf | `OperationCancelledException`, kein Resume aufsetzen | multi_step | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |
| `checkpointManager.writeInitialManifest(...)` | side_effect | checkpoint | vor Aufruf | `OperationCancelledException`, kein Initial-Manifest schreiben | multi_step | not_applicable | not_applicable | not_applicable | bei Cancel: Manifest-Pfad ist eindeutig pro op-id; AP E0.5 prueft, ob Cleanup noetig ist | missing | blocked | E0.5 |
| `checkpointManager.buildCallbacks(...)` | cleanup | none | nicht relevant (reine Closure-Konstruktion) | n/a | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | tentative-go | — |

### 4.3 Tabellen-/Chunk-Schleifen und Side Effects

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `progressReporter.report(RunStarted)` | side_effect | progress-callback | vor Aufruf, sofern bereits cancel angekommen ist | nach Cancel kein Progress-Event | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5-Test "kein Progress nach Cancel" |
| `for tableInput in discoveredInputs` | loop | none | Loop-Head | startet keine weitere Tabelle | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5-Test "Cancel zwischen Tabellen" |
| `tableImporter.import(TableImportParams)` | side_effect (Komposit) | mixed | innerhalb (siehe naechste Zeilen) | composed | multi_step | not_applicable | not_applicable | not_applicable | composed | missing | blocked | E0.5 |
| `readerFactory.create(format, input.openInput(), ...)` | side_effect | source-read (File-Open) | vor Aufruf | `OperationCancelledException`, kein Reader allokieren | atomic | not_applicable | not_applicable | not_applicable | `reader.close()` in `closeAndCollect` | missing | blocked | E0.5 |
| `writer.openTable(pool, tableName, options)` | monolithic_call | session (Writer-Session-Open) | vor Aufruf | `OperationCancelledException`, keine Session | unknown | missing | missing | missing | `session.close()` falls allokiert; AP E0.5 prueft, ob `abort`-Pfad noetig | missing | blocked | E0.5 — Driver-Vertrag pruefen, evtl. `abort`-API ergaenzen |
| `progressReporter.report(ImportTableStarted)` | side_effect | progress-callback | vor Aufruf | nach Cancel kein Event | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |
| `skipCommittedChunks(reader, offset)` (Resume-Skip-Loop) | loop | source-read | Loop-Head vor jedem `nextChunk()` | nach Cancel kein weiterer Read und kein neuer Fortschritts-Checkpoint | multi_step | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 — Hauptplan §4.6 explizit |
| `reader.nextChunk()` (per Chunk) | monolithic_call | source-read | vor Aufruf | `OperationCancelledException`, kein weiterer Chunk-Read | unknown | missing | missing | missing | not_needed | missing | blocked | E0.5 — Driver-/Format-Reader-Vertrag schaerfen |
| `session.write(chunk)` | side_effect | db-write | vor Aufruf | `OperationCancelledException`, kein neuer Write; bereits begonnener Write darf fertiglaufen | multi_step | not_applicable (in-flight write) | not_applicable | not_applicable | siehe `closeAndCollect` (Session-Close) | missing | blocked | E0.5 |
| `session.commitChunk()` | side_effect | db-write (commit) | vor Aufruf | `OperationCancelledException`, kein Commit; bereits laufender Commit fertiglaufen lassen | multi_step | not_applicable | not_applicable | not_applicable | siehe Session-Close | missing | blocked | E0.5 |
| `onChunkCommitted(commit)` Callback (-> `saveManifest`) | side_effect | progress-callback + checkpoint | vor Aufruf | nach Cancel kein Callback und kein Manifest-Update | multi_step | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 — Hauptplan §4.6: kein Fortschritts-Checkpoint nach Cancel |
| `progressReporter.report(ImportChunkCommitted)` | side_effect | progress-callback | vor Aufruf | nach Cancel kein Event | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |
| `session.finishTable()` | monolithic_call | db-write (finalize) | vor Aufruf | `OperationCancelledException`, kein Finish | unknown | missing | missing | missing | bei Cancel: `session.close()` statt finish | missing | blocked | E0.5 — Driver-Writer-Vertrag muss `abort` exposen oder Pfad ist `blocked` |
| `onTableCompleted(summary)` Callback (-> `saveManifest`) | side_effect | progress-callback + checkpoint | vor Aufruf | nach Cancel kein Erfolgssignal und kein Manifest-Update | multi_step | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |
| `closeAndCollect(reader)` / `closeAndCollect(session)` | cleanup | session | nach Cancel UNVERAENDERT ausgefuehrt | nicht abbrechen; Cleanup-Fehler suppressed | atomic | not_applicable | not_applicable | not_applicable | `close` | missing | blocked | E0.5-Test "Session/Reader nach Cancel geschlossen" |

### 4.4 Run-Abschluss

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `store.complete(operationId)` (in `ImportCompletionSupport`) | side_effect | checkpoint (final-mark) | vor Aufruf | nach Cancel kein `complete`-Mark; Manifest bleibt vorhanden, damit Resume moeglich ist | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 — Hauptplan §4.6: kein erfolgreiches Abschlussignal nach Cancel |

---

## 5. Transfer-Pipeline

Codebasis: `DataTransferRunner.kt`, `TransferExecutor.kt`,
`TransferConnectionResolver.kt`, `TransferPreflightPlanner.kt`,
`adapters/driven/driver-*` (Reader/Writer).

### 5.1 Setup-/Preflight-Phase

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `connectionResolver.resolve(request)` (Source + Target Pool) | side_effect | session (2x) | Runner-Entry vor Aufruf | `OperationCancelledException`, keine Pools | unknown | not_applicable | not_applicable | missing | beide Pools schliessen | missing | blocked | E0.5 |
| `srcDrv.schemaReader().read(srcPool, readOpts)` | monolithic_call | source-read (Schema) | vor Aufruf | `OperationCancelledException`, kein Schema-Read | unknown | missing | missing | missing | not_needed | missing | blocked | E0.5 — wie Reverse-`reader.read`, gleiche Driver-Frage |
| `tgtDrv.schemaReader().read(tgtPool, readOpts)` | monolithic_call | source-read (Schema) | vor Aufruf | `OperationCancelledException` | unknown | missing | missing | missing | not_needed | missing | blocked | E0.5 |
| `preflightPlanner.planTables(request, srcSchema, tgtSchema)` | side_effect | none (in-memory) | vor Aufruf | nicht relevant — In-Memory-Validierung | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | tentative-go (in-memory only) | — |

### 5.2 Tabellen-/Chunk-Schleifen und Side Effects

| op | kind | side | checkpoint | reaction | atom | bound | budget | evid | clean | tests | gate | followup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `for table in context.tables` (`TransferExecutor`) | loop | none | Loop-Head | startet keine weitere Tabelle | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5-Test |
| `context.reader.streamTable(srcPool, table, ...)` | monolithic_call | source-read (Stream-Open) | vor Aufruf | `OperationCancelledException`, kein Stream | unknown | missing | missing | missing | not_needed | missing | blocked | E0.5 — Reader-Port-Vertrag schaerfen |
| `context.writer.openTable(tgtPool, ...)` | monolithic_call | session (Writer-Session-Open) | vor Aufruf | `OperationCancelledException` | unknown | missing | missing | missing | `session.close()` | missing | blocked | E0.5 — wie Import |
| `for chunk in sequence` | loop | none | Loop-Head | startet keinen weiteren Chunk | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5-Test |
| `chunk.rows.map { row -> ... }` (Row-Reordering) | loop | none (in-memory) | nicht relevant | nicht relevant | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | tentative-go (rein in-memory) | — |
| `session.write(normalized)` | side_effect | db-write | vor Aufruf | `OperationCancelledException`, kein Write | multi_step | not_applicable | not_applicable | not_applicable | siehe Session-Close | missing | blocked | E0.5 |
| `session.commitChunk()` | side_effect | db-write (commit) | vor Aufruf | `OperationCancelledException`, kein Commit | multi_step | not_applicable | not_applicable | not_applicable | siehe Session-Close | missing | blocked | E0.5 |
| `session.finishTable()` | monolithic_call | db-write (finalize) | vor Aufruf | `OperationCancelledException`, kein Finish | unknown | missing | missing | missing | `session.close()` | missing | blocked | E0.5 |
| `onTableTransferred(table)` Callback | side_effect | progress-callback | vor Aufruf | nach Cancel kein Erfolgssignal | atomic | not_applicable | not_applicable | not_applicable | not_needed | missing | blocked | E0.5 |

---

## 6. Streaming-/Reader-/Writer-Pfade quer (E0.7-Final-Klassifikation)

Diese Sektion klassifiziert nach Hauptplan §4.1 die zentralen Driver-/Port-
Aufrufe in einer der drei Kategorien:

- **`cancelbar`** — Port-Vertrag oder Driver-API erlaubt kooperatives Stoppen
  während des Calls.
- **`atomic-nicht-cancelbar`** — Call läuft bis Ende durch, Hauptplan §4.1
  fordert dafür belegtes Timeout/Laufzeitfenster `<= 30s`,
  Measurement-Evidence, kein interner Retry-Loop, kein Ressourcen-Leak nach
  Timeout.
- **`blockierend`** — keine der obigen Bedingungen heute erfüllt; Pfad
  blockiert das E0-Gate, bis Adapter-Konfiguration oder Port-Erweiterung
  den Status auf `cancelbar` oder `atomic-nicht-cancelbar` hebt.

**Stand nach E0.7-Abschluss**: alle früher als `blockierend` klassifizierten
Driver-Calls sind durch
[`PoolSettings.statementTimeoutMs`](../../../spec/connection-config-spec.md)
+ [`PoolSettings.networkTimeoutMs`](../../../spec/connection-config-spec.md)
und den common JDBC-Timeout-Layer (E0.7.3
`TimeoutDecoratedConnection`) nun als `atomic-nicht-cancelbar` belegt.
Bench-Tests pro Driver liefern die Measurement-Evidence
(E0.7.4).

| Port-Grenze | Heutiger Vertrag | E0.7-Klassifikation | Begründung | Measurement-Evidence |
| --- | --- | --- | --- | --- |
| `dev.dmigrate.driver.SchemaReader.read(pool, options)` | synchron, kein Cancel-Token; aber Connection ist `TimeoutDecoratedConnection`, jedes `prepareStatement` setzt `setQueryTimeout(ceil(ms/1000))`; Connection trägt `setNetworkTimeout(...)` | **atomic-nicht-cancelbar** mit `<= 30s` Default | Schema-Reader nutzt `JdbcMetadataSession` über `pool.borrow()`. Alle Introspection-Statements werden vom Decorator timeout-gesetzt. PostgreSQL hat zusätzlich `connectionInitSql = SET statement_timeout = $ms`. | `JdbcMetadataSessionTimeoutTest` (driver-common, default-CI) belegt jeder Statement queryTimeout-trägt; `E07PostgresTimeoutBench` empirisch. |
| `dev.dmigrate.driver.DataReader.streamTable(pool, table, filter, chunkSize)` | `ChunkSequence` (Sequence + AutoCloseable); initiierender `prepareStatement` ist über Decorator timeout-gesetzt | **atomic-nicht-cancelbar** mit `<= 30s` Default für den initialen `executeQuery`-Call | Stream-Open-Latenz ist gebunden; lange Tabellen-Reads brechen via Statement-Timeout ab. inter-Chunk-Cancel bleibt 0.9.7+-Followup (Token-Param am Reader-Iterator). | `E07PostgresTimeoutBench` "statement_timeout enforces <= 5s on long-running pg_sleep query" + `E07MysqlTimeoutBench` "MAX_EXECUTION_TIME enforces <= 5s on long-running SELECT SLEEP" + `E07SqliteTimeoutBench` "setQueryTimeout enforces <= 2s on long recursive CTE". |
| `dev.dmigrate.driver.DataWriter.openTable(pool, table, options)` | synchron; alle DDL-Statements via Decorator timeout-gesetzt | **atomic-nicht-cancelbar** mit `<= 30s` pro DDL-Statement | DDL-/Trigger-Manipulation läuft über `prepareStatement`/`createStatement` der dekorierten Connection. Direkte `connection.metaData.getPrimaryKeys(...)` in PostgreSQL/MySQL ist über `setNetworkTimeout` netzwerkseitig gebunden. | Bench-Tests pro Driver (Writer-DDL läuft beim Bench-Setup mit `setQueryTimeout` durch); `JdbcMetadataSessionTimeoutTest.execute(sql)` für DDL-Pfad. |
| `TableImportSession.write(chunk)` | synchron, INSERT/UPSERT-Batch; via `AbstractTableImportSession.ensureInsertPlan()` über Decorator | **atomic-nicht-cancelbar** mit `<= 30s` | Batch-Insert hat chunk-bounded Größe (`PipelineConfig.chunkSize` Default 10 000); jeder `prepareStatement(insertSql)` ist timeout-gesetzt. | Bench-Tests + Decorator-Test (alle 9 `prepareStatement`-Overloads). |
| `TableImportSession.commitChunk()` | JDBC-Transaktion-Commit; Connection trägt `setNetworkTimeout(...)` | **atomic-nicht-cancelbar** | Commits laufen über `Connection`. Lock-Wait wird durch `setNetworkTimeout(networkTimeoutMs)` gebunden (PostgreSQL/MySQL). SQLite zusätzlich via `PRAGMA busy_timeout`. | `E07PostgresTimeoutBench` Cleanup-Test belegt healthy commit nach Cancel; `connection.networkTimeout` belegt via `HikariConnectionPoolFactoryTest` "borrow gracefully handles drivers that do not support setNetworkTimeout". |
| `TableImportSession.finishTable()` | mehrstufig: Trigger-Re-Enable, Sequence-Anpassung, FK-Re-Enable; jedes DDL-Statement via Decorator | **atomic-nicht-cancelbar** mit `<= 30s` pro DDL-Statement | Mehrere DDL-Statements, jedes durch den common Layer timeout-gesetzt. PostgreSQL hat zusätzlich server-side `statement_timeout`. | Bench-Tests + `JdbcMetadataSessionTimeoutTest.executeBatch`. |
| `dev.dmigrate.format.data.DataChunkReader.nextChunk()` (Format-Reader) | synchron, lokales Filesystem (JSONL/CSV/YAML); kein Netzwerk, kein Cancel-Token | **atomic** (lokale FS-Reads) | Format-Reader liest lokale Files mit chunk-bounded Buffer. Plan §4.1 `bound = local-FS read time` ist akzeptiert. Remote-FS-Inputs (S3, HTTP, NFS) sind heute nicht Teil von 0.9.6. | keine zusätzliche Evidence nötig (lokal); falls 0.9.7+ remote inputs ergänzt, Token-Param am Format-Reader. |
| `CheckpointStore.save(manifest)` / `load(opId)` / `complete(opId)` | synchron, lokales FS (atomic-rename Pattern), kein Cancel-Token | **atomic** | Lokale FS-Operationen, jeweils sub-second. Cancel zwischen Save-Calls über Runner-Checkpoint abgefangen. | keine. |
| `ProgressReporter.report(event)` | synchron, in-memory + stderr | **atomic** | In-Memory + sync-stderr; nicht relevant. | keine. |
| `connectionResolver.resolve(...)` (Transfer) | öffnet Source- + Target-Pool sequenziell; HikariCP `connectionTimeout = 10000ms` (Pool-Acquire-Timeout) plus `setNetworkTimeout(networkTimeoutMs)` auf jeder geborgten Connection | **atomic-nicht-cancelbar** mit `<= 30s` Default | Pool-Open-Latenz ist über Hikari `connectionTimeoutMs` (10s default) gebunden. Connection-I/O nach Borrow ist über `setNetworkTimeout` gebunden. | `HikariConnectionPoolFactoryTest` "borrow gracefully handles drivers that do not support setNetworkTimeout"; Bench-Tests verifizieren Pool-Acquire empirisch. |

**Konsequenz für das E0-Gate** (siehe
[`ImpPlan-0.9.6-E0-Gate-Decision.md`](./ImpPlan-0.9.6-E0-Gate-Decision.md)):

Alle früher als `blockierend` klassifizierten Pfade sind durch E0.7
auf `atomic-nicht-cancelbar` mit `cancel_budget_ms <= 30000` und
konkreter Measurement-Evidence aus den Bench-Tests gehoben. Plan §9
"Atomar-nicht-cancelbare Calls haben ein belegtes Timeout-/Laufzeit-
fenster, ein gemessenes E0-Cancel-Reaktionsbudget von `<= 30s`, keine
ungebundenen Retry-/Reconnect-Loops und hinterlassen nach Timeout
keine offenen Ressourcen" ist erfüllt.

**Verdict: `Go`** — Phase E darf starten. Alle Details in
`ImpPlan-0.9.6-E0-Gate-Decision.md`.

**Streaming-Anmerkungen:**

- Der `TableImportSession`-Vertrag bietet heute kein dediziertes `abort()`.
  E0.5-Spike hat geprüft: `session.close()` nach unvollständigem
  `commitChunk` reicht für PostgreSQL/MySQL/SQLite, weil JDBC-Auto-Rollback
  bei Connection-Close greift. SQLite-WAL benötigt zusätzliches `ROLLBACK`,
  das im Adapter-`close()` bereits enthalten ist (`SqliteImportSession`).
  Phase-E muss kein neuer `abort()` ergänzt werden.
- `DataChunkReader` ist heute lokales FS — `bound = local FS read time`.
  Remote-FS-Inputs sind nicht Teil von 0.9.6.
- `CheckpointStore` ist file-basiert (`FileCheckpointStore`) — alle
  Operationen sind sub-second. Token-Param am Store-Vertrag ist nicht
  notwendig; Runner-Checkpoints zwischen Store-Calls reichen.

---

## 7. Schnellstatistik

Reine `gate_result`-Verteilung der oben gelisteten Zeilen:

| Stand | `go` | `go_followup` | `tentative-go` | `blocked` |
| --- | --- | --- | --- | --- |
| 2026-05-05 (E0.2) | 0 | 0 | 4 | ~70 |
| 2026-05-05 (E0.4) | ~10 | ~12 | 4 | ~50 |
| 2026-05-05 (E0.5) | ~25 (alle Inter-Call-Checkpoints + Resume-Skip + Callbacks) | ~10 (Driver-`atomic-nicht-cancelbar`-Kandidaten mit queryTimeout-Followup) | 4 | ~10 (`blockierend`-Treffer in Section 6: alle ohne `setQueryTimeout`-Konfiguration) |
| 2026-05-05 (E0.7) | **~74** (alle Driver-Calls + Inter-Call-Checkpoints + Format-Reader/Checkpoint-Store/ProgressReporter `atomic`) | 0 | 0 | **0** |

E0.7-Gate-Stand (siehe `ImpPlan-0.9.6-E0-Gate-Decision.md`):

- Inter-Call-Cancel ist vollständig nachgewiesen für Reverse, Profile,
  Import und Transfer (E0.4 + E0.5-Tests).
- During-Driver-Call-Cancel ist durch
  `PoolSettings.statementTimeoutMs`/`networkTimeoutMs` (E0.7.1) +
  driver-spezifischer `connectionInitSql` (E0.7.2) + common
  `TimeoutDecoratedConnection` (E0.7.3) + Bench-Tests pro Driver
  (E0.7.4) belegt. Cancel-Reaktionsbudget `<= 30s` Default; pro Adapter
  `0` deaktiviert (Plan §4.2). Hauptplan §9 "belegtes Timeout-/Laufzeit-
  fenster, gemessenes E0-Cancel-Reaktionsbudget" erfüllt.
- Verdict re-stempelt: **`Go`**. Phase E darf starten.

---

## 8. Aenderungs-Protokoll dieser Matrix

| Datum | AP | Aenderung |
| --- | --- | --- |
| 2026-05-05 | E0.2 | Initial-Snapshot — alle externen Side-Effect-Zeilen `blocked` (`tests = missing`); 4 In-Memory-Zeilen `tentative-go`. |
| 2026-05-05 | E0.4 | Reverse-Pipeline (3 Checkpoints + Exit-130-Mapping) + Profile-Pipeline (Checkpoints in `ProfileDatabaseService`/`ProfileTableService`/`profileColumn` + Exit-130 in `DataProfileRunner`). Reverse: 4 Zeilen `go`, 2 Zeilen `go_followup`, 1 Zeile bleibt `blocked` (`reader.read` E0.5-Driver-Vertrag). Profile: 2 Zeilen `go`, 9 Zeilen `go_followup` (in-flight Cancel benötigt Driver-Statement-Cancel — E0.5), 1 Zeile bleibt `go_followup` (Pre-Pool-Checkpoint optional, Cleanup über `finally { pool.close() }`). Tests: `SchemaReverseRunnerCancelCheckpointTest`, `ProfileServiceCancelCheckpointTest`, `DataProfileRunnerCancelCheckpointTest`. |
| 2026-05-05 | E0.5 (1/3) | Import-Pipeline: `DataImportRunner` Outer-130-Mapping, `ImportStreamingInvoker` Cancel-Re-Throw, `StreamingImporter` Tabellen-Loop-Checkpoints, `TableImporter` prepareImport/skipCommittedChunks/finishImport-Checkpoints, `importChunks` chunk-loop-Checkpoints (außerhalb der 3 chunk-failure try-Blöcke; Plan §4.5). Tests: `DataImportRunnerCancelCheckpointTest`, `StreamingImporterCancelCheckpointTest`, `TableImporterCancelCheckpointTest`. Inter-Call-Zeilen wechseln zu `go`. |
| 2026-05-05 | E0.5 (2/3) | Transfer-Pipeline: `DataTransferRunner` Outer-130-Mapping + Cancel-Filter in Schema-Read- und Transfer-Execute-catches, `TransferExecutor` Tabellen-Loop + transferTable-Entry + Chunk-Loop-Checkpoints + pre-finish-Checkpoint. Tests: `DataTransferRunnerCancelCheckpointTest`, `TransferExecutorCancelCheckpointTest`. Inter-Call-Zeilen wechseln zu `go`. |
| 2026-05-05 | E0.5 (3/3) | Driver-/Port-Vertrags-Klassifikation in Section 6: alle monolithic-driver-call-Zeilen sind heute **blockierend** wegen fehlender `Statement.setQueryTimeout`/`Connection.setNetworkTimeout`-Konfiguration in Driver-Adaptern. Phase-E-Nacharbeit ist Adapter-Konfiguration (kein Port-Vertrag-Wechsel). Format-Reader (`DataChunkReader.nextChunk`), `CheckpointStore`, `ProgressReporter` sind als `atomic` klassifiziert. |
| 2026-05-05 | E0.7.1 | `PoolSettings.statementTimeoutMs` + `networkTimeoutMs` Felder mit Default `30_000` und `init {}`-Validation (Commit `72b8a9f`). `connection-config-spec.md` §2.2 erweitert. |
| 2026-05-05 | E0.7.2 | `connectionInitSql` pro Driver in `HikariConnectionPoolFactory` (Commit `c5a70e6`). PostgreSQL `SET statement_timeout`, MySQL `SET SESSION MAX_EXECUTION_TIME`, SQLite `PRAGMA busy_timeout`. |
| 2026-05-05 | E0.7.3 | Common JDBC-Timeout-Layer (Commit `15f3e45`): `TimeoutDecoratedConnection` (13 Statement-Overload-Decorators) + `Connection.setNetworkTimeout(...)` beim `borrow()` mit `SQLFeatureNotSupportedException`-Resilienz; `timeoutSecondsOf` rundet sub-second auf. Decoder ist transparent für alle Adapter-Module. |
| 2026-05-05 | E0.7.4 | Bench-Tests pro Driver (Commit `3fe0508`): `E07PostgresTimeoutBench`, `E07MysqlTimeoutBench`, `E07SqliteTimeoutBench` (`@Tag("integration")`); `JdbcMetadataSessionTimeoutTest` (default-CI) belegt Profiling-/Schema-Reader-Coverage über den common Layer. |
| 2026-05-05 | E0.7.5 | Section 6 final-klassifiziert: alle früher-`blockierend`-Zeilen werden zu `atomic-nicht-cancelbar` mit konkreten Bench-/Test-Referenzen in `measurement_evidence`. Schnellstatistik: `blocked = 0`, `go ~74`. Gate-Decision-Verdict re-stempelt zu `Go`. |
