# Phase E0.2 — Cancel-Checkpoint- und Side-Effect-Matrix

> **Milestone**: 0.9.6 — Beta: MCP-Server
> **Phase**: E0.2 (Side-Effect-Inventar)
> **Status**: Initial-Snapshot 2026-05-05
> **Hauptplan**: `ImpPlan-0.9.6-E0.md` §7.2
> **Pflichtschema**: `ImpPlan-0.9.6-E0.md` §7.2 (Tabelle direkt nach AP E0.2)

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

## 6. Streaming-/Reader-/Writer-Pfade quer (E0.5-Final-Klassifikation)

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

| Port-Grenze | Heutiger Vertrag | E0.5-Klassifikation | Begründung | Phase-E-Nacharbeit |
| --- | --- | --- | --- | --- |
| `dev.dmigrate.driver.SchemaReader.read(pool, options)` | synchron, kein Cancel-Token, kein per-Statement-Timeout | **blockierend** | Reverse-Schema-Lesen kann beliebig viele DDL-Queries triggern (Tabellen-/Spalten-/Index-Introspection). Heute setzt kein Adapter `Statement.setQueryTimeout(...)`. Ohne belegtes Timeout-Fenster ist Plan §4.1-Schwelle `<= 30s` nicht erfüllt. | Adapter postgresql/mysql/sqlite konfigurieren `setQueryTimeout(30)` an der gemeinsamen `prepareStatement(...)`-Stelle; Bench-Test pro Driver. Kein Port-Vertrag-Wechsel. |
| `dev.dmigrate.driver.DataReader.streamTable(pool, table, filter, chunkSize)` | `ChunkSequence` (Sequence + AutoCloseable), kein Cancel-Token, kein Statement-Timeout | **blockierend** | Single-`SELECT`-Stream über JDBC-`ResultSet`; lange Source-Tabellen können beliebig blockieren. Das `use { }`-Pattern schließt sauber, aber Cancel mid-stream landet erst beim nächsten Chunk-Read der Adapter-Schicht. | (a) Statement-Timeout für den initiierenden `SELECT` setzen — gibt obere Schranke für Stream-Open-Latenz. (b) Optional: Token-Param am Reader-Iterator-Rand für inter-Chunk-Cancel. (a) reicht für E0-Gate. |
| `dev.dmigrate.driver.DataWriter.openTable(pool, table, options)` | synchron, kein Cancel-Token, keine `abort`-API; setzt evtl. Trigger-/FK-/Sequence-State um | **blockierend** | DDL-/Trigger-Manipulation ist driver-spezifisch und kann mehrere DDL-Statements emittieren. Trigger-Disable in MySQL/PostgreSQL ist mehrstufig. | `setQueryTimeout(30)` pro Driver-Adapter setzen; Cleanup-Pfad `session.close()` (existiert bereits) decken Cancel-Mid-Open ab. |
| `TableImportSession.write(chunk)` | synchron, INSERT/UPSERT-Batch, kein Cancel-Token | **blockierend** (heute), realistisch **atomic-nicht-cancelbar** mit Statement-Timeout | Chunk-Größe ist über `PipelineConfig.chunkSize` (Default 10 000 Rows) gebunden — Batch-Insert hat eine fachlich plausible obere Schranke pro Call. Aber ohne explizites `setQueryTimeout` ist das Fenster nicht belegt. | Statement-Timeout `30 s` pro Adapter; Phase-E-Bench-Test mit großem Chunk gegen langsame DB belegt `<=30 s`. |
| `TableImportSession.commitChunk()` | JDBC-Transaktion-Commit, kein Cancel-Token | **atomic-nicht-cancelbar** (Annahme) | Commits an JDBC-Connection sind in der Praxis sub-second, blockieren nur durch Lock-Wait. Plan §4.1 erlaubt `atomic-nicht-cancelbar`, sofern Timeout belegt. Bei Lock-Wait kann Commit unbounded blockieren — daher heute ohne `Connection.setNetworkTimeout(...)` formal **blockierend**. | `Connection.setNetworkTimeout(executor, 30000)` in Pool-Bootstrap; gilt auch für `Statement.executeQuery`. |
| `TableImportSession.finishTable()` | mehrstufig: Trigger-Re-Enable, Sequence-Anpassung, FK-Re-Enable, Foreign-Statement, dialekt-spezifisch | **blockierend** | Mehrere DDL-Statements pro Aufruf, jedes ohne Timeout. Plan §4.1 fordert "kein interner Retry-Loop ohne hartes Timeout" — derzeit nicht belegt. | Pro DDL-Statement im Adapter `setQueryTimeout(30)`; Phase-E ergänzt `failedFinish`-Pfad-Coverage für Cancel-Mid-Finish-Cleanup. |
| `dev.dmigrate.format.data.DataChunkReader.nextChunk()` (Format-Reader) | synchron, lokales Filesystem (JSONL/CSV/YAML); kein Netzwerk, kein Cancel-Token | **atomic-nicht-cancelbar** (lokale FS-Reads) | Format-Reader liest lokale Files mit chunk-bounded Buffer. Plan §4.1 `bound = local-FS read time` ist akzeptiert. Remote-FS-Inputs (S3, HTTP, NFS) sind heute nicht Teil von 0.9.6. | keine; falls 0.9.7+ remote inputs ergänzt, Token-Param am Format-Reader. |
| `CheckpointStore.save(manifest)` / `load(opId)` / `complete(opId)` | synchron, lokales FS (atomic-rename Pattern), kein Cancel-Token | **atomic** | Lokale FS-Operationen, jeweils sub-second. Cancel zwischen Save-Calls über Runner-Checkpoint abgefangen. | keine. |
| `ProgressReporter.report(event)` | synchron, in-memory + stderr | **atomic** | In-Memory + sync-stderr; nicht relevant. | keine. |
| `connectionResolver.resolve(...)` (Transfer) | öffnet Source- + Target-Pool sequenziell | **blockierend** (in Bezug auf Pool-Open-Latenz) | Pool-Open kann Connection-Init-Latenz haben (Server-RTT, SSL-Handshake). Ohne `Connection.setNetworkTimeout` unbounded. | `Connection.setNetworkTimeout(executor, 30000)` in `HikariConnectionPoolFactory`. |

**Konsequenz für das E0-Gate (siehe `ImpPlan-0.9.6-E0-Gate-Decision.md`):**

Alle als **blockierend** klassifizierten Pfade haben dieselbe Wurzel: kein
Driver-Adapter setzt heute ein `Statement.setQueryTimeout(...)` oder
`Connection.setNetworkTimeout(...)`. Das ist eine **Adapter-Konfigurations-
änderung**, kein Port-Vertrag-Wechsel — Plan §7.6 erlaubt das als
`Go mit Nacharbeiten`-Followup, sofern die harte Side-Effect-Stop-Semantik
**zwischen** den Driver-Calls bereits nachgewiesen ist (was sie ist —
E0.4-Tests + E0.5-Tests decken alle Inter-Call-Grenzen). Die Phase-E-
Nacharbeit ist konkret, abgegrenzt und ohne Cancel-Interpretation.

Das Gate ist daher **`Go mit Nacharbeiten`**, mit der oben gelisteten
queryTimeout-/networkTimeout-Konfiguration als Pflicht-Pre-Phase-E-Arbeit.
Alle Details in `ImpPlan-0.9.6-E0-Gate-Decision.md`.

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

E0.6-Gate-Entscheidung (siehe `ImpPlan-0.9.6-E0-Gate-Decision.md`):

- **Inter-Call-Cancel ist vollständig nachgewiesen** für Reverse, Profile,
  Import und Transfer (E0.4 + E0.5-Tests).
- **During-Driver-Call-Cancel** ist nicht nachgewiesen: kein Adapter setzt
  heute `Statement.setQueryTimeout` oder `Connection.setNetworkTimeout`.
  Das ist die einzige offene Bedingung gegen Plan §4.1
  "atomar-nicht-cancelbar mit `<=30 s`-Budget".
- Das ist eine **Adapter-Konfigurationsänderung**, kein Port-Vertrag.
  Plan §7.6 erlaubt diese als Phase-E-Nacharbeit, sofern die harte Side-
  Effect-Stop-Semantik zwischen den Calls bereits nachgewiesen ist
  (sie ist).
- Verdict: **`Go mit Nacharbeiten`** mit Pflicht-Pre-Phase-E-Arbeit
  "queryTimeout-/networkTimeout-Konfiguration in Driver-Adaptern".

---

## 8. Aenderungs-Protokoll dieser Matrix

| Datum | AP | Aenderung |
| --- | --- | --- |
| 2026-05-05 | E0.2 | Initial-Snapshot — alle externen Side-Effect-Zeilen `blocked` (`tests = missing`); 4 In-Memory-Zeilen `tentative-go`. |
| 2026-05-05 | E0.4 | Reverse-Pipeline (3 Checkpoints + Exit-130-Mapping) + Profile-Pipeline (Checkpoints in `ProfileDatabaseService`/`ProfileTableService`/`profileColumn` + Exit-130 in `DataProfileRunner`). Reverse: 4 Zeilen `go`, 2 Zeilen `go_followup`, 1 Zeile bleibt `blocked` (`reader.read` E0.5-Driver-Vertrag). Profile: 2 Zeilen `go`, 9 Zeilen `go_followup` (in-flight Cancel benötigt Driver-Statement-Cancel — E0.5), 1 Zeile bleibt `go_followup` (Pre-Pool-Checkpoint optional, Cleanup über `finally { pool.close() }`). Tests: `SchemaReverseRunnerCancelCheckpointTest`, `ProfileServiceCancelCheckpointTest`, `DataProfileRunnerCancelCheckpointTest`. |
| 2026-05-05 | E0.5 (1/3) | Import-Pipeline: `DataImportRunner` Outer-130-Mapping, `ImportStreamingInvoker` Cancel-Re-Throw, `StreamingImporter` Tabellen-Loop-Checkpoints, `TableImporter` prepareImport/skipCommittedChunks/finishImport-Checkpoints, `importChunks` chunk-loop-Checkpoints (außerhalb der 3 chunk-failure try-Blöcke; Plan §4.5). Tests: `DataImportRunnerCancelCheckpointTest`, `StreamingImporterCancelCheckpointTest`, `TableImporterCancelCheckpointTest`. Inter-Call-Zeilen wechseln zu `go`. |
| 2026-05-05 | E0.5 (2/3) | Transfer-Pipeline: `DataTransferRunner` Outer-130-Mapping + Cancel-Filter in Schema-Read- und Transfer-Execute-catches, `TransferExecutor` Tabellen-Loop + transferTable-Entry + Chunk-Loop-Checkpoints + pre-finish-Checkpoint. Tests: `DataTransferRunnerCancelCheckpointTest`, `TransferExecutorCancelCheckpointTest`. Inter-Call-Zeilen wechseln zu `go`. |
| 2026-05-05 | E0.5 (3/3) | Driver-/Port-Vertrags-Klassifikation in Section 6: alle monolithic-driver-call-Zeilen sind heute **blockierend** wegen fehlender `Statement.setQueryTimeout`/`Connection.setNetworkTimeout`-Konfiguration in Driver-Adaptern. Phase-E-Nacharbeit ist Adapter-Konfiguration (kein Port-Vertrag-Wechsel). Format-Reader (`DataChunkReader.nextChunk`), `CheckpointStore`, `ProgressReporter` sind als `atomic` klassifiziert. |
