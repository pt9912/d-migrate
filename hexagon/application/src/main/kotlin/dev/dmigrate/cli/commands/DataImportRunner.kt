package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale

class CliUsageException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class ImportPreflightException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

data class SchemaPreflightResult(
    val input: ImportInput,
    val schema: SchemaDefinition? = null,
)

/**
 * Thin seam over the streaming import, allowing the Runner to be tested
 * without a real [StreamingImporter][dev.dmigrate.streaming.StreamingImporter].
 * The production implementation is wired in the CLI module.
 */
fun interface ImportExecutor {
    fun execute(
        pool: ConnectionPool,
        input: ImportInput,
        format: DataExportFormat,
        options: ImportOptions,
        config: PipelineConfig,
        onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
        progressReporter: ProgressReporter,
        /**
         * 0.9.0 Phase D.3 (`docs/ImpPlan-0.9.0-D.md` §5.3): stabile
         * `operationId` des Laufs. Symmetrisch zum Export-Pfad wird
         * sie vom Runner vorab festgelegt (neuer Lauf: UUID; Resume:
         * aus Manifest) und an `StreamingImporter.import` durchgereicht.
         */
        operationId: String?,
        /**
         * 0.9.0 Phase D.3 §5.3: `true` bei Wiederaufnahme aus einem
         * vorhandenen Manifest, `false` bei neuem Lauf. Floss in
         * `ProgressEvent.RunStarted`, damit Renderer `Resuming run …`
         * anstelle von `Starting run …` anzeigen koennen.
         */
        resuming: Boolean,
        /**
         * 0.9.0 Phase D.3 §4.7: Tabellen, die im Manifest bereits
         * `COMPLETED` sind; der Importer ueberspringt sie komplett.
         */
        skippedTables: Set<String>,
        /**
         * 0.9.0 Phase D.3 §5.3: pro Tabelle optionaler Resume-
         * Zustand. Entspricht einem Eintrag fuer jede Tabelle mit
         * `chunksProcessed > 0` im Manifest.
         */
        resumeStateByTable: Map<String, dev.dmigrate.streaming.ImportTableResumeState>,
        /**
         * 0.9.0 Phase D.3 §5.3: Callback nach jedem erfolgreich
         * committeten Chunk. Der Runner fuellt damit das Manifest
         * fort.
         */
        onChunkCommitted: (dev.dmigrate.streaming.ImportChunkCommit) -> Unit,
        /**
         * 0.9.0 Phase D.3 §5.3: Callback pro abgeschlossenem
         * Tabellen-Import. Wird auch bei Fehler/`failedFinish`
         * gerufen.
         */
        onTableCompleted: (dev.dmigrate.streaming.TableImportSummary) -> Unit,
    ): ImportResult
}

/**
 * Immutable DTO with all CLI inputs for `d-migrate data import`.
 */
data class DataImportRequest(
    val target: String?,
    val source: String,
    val format: String?,
    val schema: Path?,
    val table: String?,
    val tables: List<String>?,
    val onError: String,
    val onConflict: String?,
    val triggerMode: String,
    val truncate: Boolean,
    val disableFkChecks: Boolean,
    val reseedSequences: Boolean,
    val encoding: String?,
    val csvNoHeader: Boolean,
    val csvNullString: String,
    val chunkSize: Int,
    val cliConfigPath: Path?,
    val quiet: Boolean,
    val noProgress: Boolean,
    /**
     * 0.9.0 Phase A (`docs/ImpPlan-0.9.0-A.md` §4.3/§4.4): expliziter
     * Resume-Einstieg fuer `data import`. Nur gueltig fuer file- oder
     * directory-basierte Sources (`source != "-"`). Stdin-Import kann
     * nicht wiederaufgenommen werden und loest Exit 2 aus. Das konkrete
     * Referenzformat und semantische Preflight-Pruefung gegen das Target
     * werden in den Phasen B bis D festgezogen.
     */
    val resume: String? = null,
    /**
     * 0.9.0 Phase A §4.3: optionales Verzeichnis fuer Checkpoints,
     * analog zum Export-Pfad. Gewinnt gegenueber
     * `pipeline.checkpoint.directory` aus der Config.
     */
    val checkpointDir: Path? = null,
)

/**
 * Core logic for `d-migrate data import`. All external collaborators are
 * constructor-injected so every branch (including error paths and exit
 * codes) is unit-testable without a real database or CLI framework.
 *
 * Exit codes (Plan §6.11):
 * - 0 success
 * - 1 unexpected internal error
 * - 2 CLI validation error (incl. `--resume` on stdin-Import, unsupported
 *   `--lang` handled in the root CLI)
 * - 3 pre-flight failure (header/schema mismatch, strict trigger, **und**
 *   — 0.9.0 Phase A §4.5 — semantisch inkompatible Resume-Referenz; die
 *   tatsaechliche Manifest-Pruefung landet in Phase B/C)
 * - 4 connection error
 * - 5 import streaming error (with --on-error abort) or post-chunk finalization
 * - 7 config / URL / registry error (incl. unreadable checkpoint file or
 *   unparseable manifest — 0.9.0 Phase A §4.5)
 */
class DataImportRunner(
    private val targetResolver: (target: String?, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val schemaPreflight: (schemaPath: Path, input: ImportInput, format: DataExportFormat) -> SchemaPreflightResult =
        { _, input, _ -> SchemaPreflightResult(input) },
    private val schemaTargetValidator: (schema: SchemaDefinition, table: String, targetColumns: List<TargetColumn>) -> Unit =
        { _, _, _ -> },
    private val importExecutor: ImportExecutor,
    private val progressReporter: ProgressReporter = NoOpProgressReporter,
    private val stdinProvider: () -> InputStream = { System.`in` },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    /**
     * 0.9.0 Phase D.1 (`docs/ImpPlan-0.9.0-D.md` §5.1): Factory fuer
     * den Checkpoint-Store. Bekommt das effektive Checkpoint-
     * Verzeichnis. Die CLI-Seite wired den dateibasierten Adapter;
     * Tests injizieren einen In-Memory-Store. `null` hier = Resume-
     * Support deaktiviert (Legacy-Tests, die keine Manifest-
     * Interaktion wollen).
     */
    private val checkpointStoreFactory: ((Path) -> CheckpointStore)? = null,
    /**
     * 0.9.0 Phase D.1 (`docs/ImpPlan-0.9.0-D.md` §5.1): liest den
     * `pipeline.checkpoint.*`-Block aus der effektiven
     * `.d-migrate.yaml`. Der Runner mergt ueber
     * [CheckpointConfig.merge] CLI-Override (`--checkpoint-dir`) und
     * Config-Default zusammen — symmetrisch zum Export-Pfad.
     */
    private val checkpointConfigResolver: (Path?) -> CheckpointConfig? = { null },
    /**
     * 0.9.0 Phase D.1: Zeitquelle fuer Manifest-
     * `createdAt`/`updatedAt`. Separat injizierbar fuer
     * deterministische Tests.
     */
    private val clock: () -> Instant = Instant::now,
) {

    fun execute(request: DataImportRequest): Int {
        // ─── 1. CLI-Validierungen ───────────────────────────────

        // --table und --tables sind gegenseitig exklusiv
        if (request.table != null && !request.tables.isNullOrEmpty()) {
            stderr("Error: --table and --tables are mutually exclusive.")
            return 2
        }

        // Identifier-Validierung für --table
        if (request.table != null) {
            val invalid = DataExportHelpers.firstInvalidQualifiedIdentifier(request.table)
            if (invalid != null) {
                stderr(
                    "Error: --table value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }

        // Identifier-Validierung für --tables
        if (!request.tables.isNullOrEmpty()) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(request.tables)
            if (invalid != null) {
                stderr(
                    "Error: --tables value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }

        // --truncate + explicit --on-conflict abort → Exit 2
        if (request.truncate && request.onConflict == "abort") {
            stderr("Error: --truncate with explicit --on-conflict abort is contradictory.")
            return 2
        }

        // 0.9.0 Phase A / Phase D.1: Resume-CLI-Preflight.
        // stdin-Import ist per Definition nicht wieder aufsetzbar — der
        // aufrufende Prozess kann den Stream nicht erneut liefern. Die
        // semantische Preflight-Pruefung gegen das Manifest passiert
        // spaeter in `executeWithPool` (Phase D.1 §5.1), sobald Target
        // und Source aufgeloest sind.
        if (!request.resume.isNullOrBlank() && request.source == "-") {
            stderr(
                "Error: --resume is not supported for stdin import; " +
                    "provide a file or directory source or drop --resume."
            )
            return 2
        }

        // ─── 2. Source-Pfad + Format auflösen ───────────────────
        val isStdin = request.source == "-"
        val sourcePath = if (!isStdin) Path.of(request.source) else null

        // Format bestimmen
        val formatName = request.format
            ?: sourcePath?.let { inferFormatFromExtension(it) }

        if (formatName == null) {
            if (isStdin) {
                stderr("Error: --format is required when reading from stdin (--source -).")
            } else {
                stderr("Error: Cannot detect format from '${request.source}'. Use --format to specify json, yaml, or csv.")
            }
            return 2
        }

        val format = try {
            DataExportFormat.fromCli(formatName)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 2
        }

        // Source-Pfad-Validierung (nicht-stdin)
        if (sourcePath != null && !Files.exists(sourcePath)) {
            stderr("Error: Source path does not exist: $sourcePath")
            return 2
        }

        // ─── 3. ImportInput ableiten ────────────────────────────
        val importInput = try {
            resolveImportInput(request, isStdin, sourcePath)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 2
        }

        // ─── 4. Optionales --schema-Preflight ───────────────────
        val preparedImport = if (request.schema != null) {
            try {
                schemaPreflight(request.schema, importInput, format)
            } catch (e: ImportPreflightException) {
                stderr("Error: ${e.message}")
                return 3
            }
        } else {
            SchemaPreflightResult(importInput)
        }

        // ─── 5. Encoding parsen ─────────────────────────────────
        val charset: Charset? = if (request.encoding != null) {
            try {
                Charset.forName(request.encoding)
            } catch (e: Exception) {
                stderr("Error: Unknown encoding '${request.encoding}': ${e.message}")
                return 2
            }
        } else {
            null // auto-detect
        }

        // ─── 6. Target auflösen → vollständige Connection-URL ───
        val resolvedUrl = try {
            targetResolver(request.target, request.cliConfigPath)
        } catch (e: CliUsageException) {
            stderr("Error: ${e.message}")
            return 2
        } catch (e: Exception) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 7. URL → ConnectionConfig ──────────────────────────
        val connectionConfig = try {
            urlParser(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // --disable-fk-checks auf PG → Exit 2
        if (request.disableFkChecks && connectionConfig.dialect == DatabaseDialect.POSTGRESQL) {
            stderr(
                "Error: --disable-fk-checks is not supported for PostgreSQL. " +
                    "Use DEFERRABLE constraints or --schema-based ordering instead."
            )
            return 2
        }

        // --trigger-mode disable auf MySQL/SQLite → Exit 2
        if (request.triggerMode == "disable" &&
            connectionConfig.dialect in listOf(DatabaseDialect.MYSQL, DatabaseDialect.SQLITE)
        ) {
            stderr(
                "Error: --trigger-mode disable is not supported for dialect ${connectionConfig.dialect}."
            )
            return 2
        }

        // ─── 8. Pool öffnen ────────────────────────────────────
        val pool: ConnectionPool = try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            stderr("Error: Failed to connect to database: ${e.message}")
            return 4
        }

        return try {
            executeWithPool(request, connectionConfig, resolvedUrl, charset, format, preparedImport, pool)
        } finally {
            try { pool.close() } catch (_: Throwable) {}
        }
    }

    private fun executeWithPool(
        request: DataImportRequest,
        connectionConfig: ConnectionConfig,
        resolvedUrl: String,
        charset: Charset?,
        format: DataExportFormat,
        preparedImport: SchemaPreflightResult,
        pool: ConnectionPool,
    ): Int {
        // ─── 8. Writer-Lookup ──────────────────────────────────
        try {
            writerLookup(connectionConfig.dialect)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 9. ImportOptions bauen ────────────────────────────
        val triggerMode = TriggerMode.valueOf(request.triggerMode.uppercase())
        val onError = OnError.valueOf(request.onError.uppercase())
        val onConflict = if (request.onConflict != null) {
            OnConflict.valueOf(request.onConflict.uppercase())
        } else {
            OnConflict.ABORT
        }

        val importOptions = ImportOptions(
            triggerMode = triggerMode,
            csvNoHeader = request.csvNoHeader,
            csvNullString = request.csvNullString,
            encoding = charset,
            reseedSequences = request.reseedSequences,
            disableFkChecks = request.disableFkChecks,
            truncate = request.truncate,
            onConflict = onConflict,
            onError = onError,
        )
        val pipelineConfig = PipelineConfig(chunkSize = request.chunkSize)
        val onTableOpened: (String, List<TargetColumn>) -> Unit =
            preparedImport.schema?.let { schema ->
                { table, targetColumns ->
                    schemaTargetValidator(schema, table, targetColumns)
                }
            } ?: { _, _ -> }

        // ─── 9b. 0.9.0 Phase D.1: Resume-Preflight + Manifest-Lifecycle ──
        // (`docs/ImpPlan-0.9.0-D.md` §5.1)
        //
        // Effektive Tabellenliste fuer Fingerprint und Manifest-
        // Slices. Stdin/SingleFile tragen genau eine Tabelle; fuer
        // Directory-Importe ohne `--tables` bleibt die Liste in D.1
        // leer (D.4 §5.4 fuellt sie spaeter aus dem Directory-Scan).
        val effectiveTables: List<String> = when (val input = preparedImport.input) {
            is ImportInput.Stdin -> listOf(input.table)
            is ImportInput.SingleFile -> listOf(input.table)
            is ImportInput.Directory -> input.tableFilter ?: emptyList()
        }
        val inputTopology: String = when (preparedImport.input) {
            is ImportInput.Stdin -> "stdin"
            is ImportInput.SingleFile -> "single-file"
            is ImportInput.Directory -> "directory"
        }
        val inputPath: String = when (val input = preparedImport.input) {
            is ImportInput.Stdin -> "<stdin>"
            is ImportInput.SingleFile -> input.path.toAbsolutePath().normalize().toString()
            is ImportInput.Directory -> input.path.toAbsolutePath().normalize().toString()
        }
        val fingerprint = ImportOptionsFingerprint.compute(
            ImportOptionsFingerprint.Input(
                format = request.format
                    ?: format.name.lowercase(Locale.US),
                encoding = request.encoding,
                csvNoHeader = request.csvNoHeader,
                csvNullString = request.csvNullString,
                onError = request.onError,
                onConflict = request.onConflict ?: "abort",
                triggerMode = request.triggerMode,
                truncate = request.truncate,
                disableFkChecks = request.disableFkChecks,
                reseedSequences = request.reseedSequences,
                chunkSize = request.chunkSize,
                tables = effectiveTables,
                inputTopology = inputTopology,
                inputPath = inputPath,
                targetDialect = connectionConfig.dialect.name,
                targetUrl = resolvedUrl,
            )
        )

        // 0.9.0 Phase D.1 §5.1: zentraler Merge aus CLI-Override
        // (`--checkpoint-dir`) und Config-Default
        // (`pipeline.checkpoint.*`). `CheckpointConfig.merge` liefert
        // die effektive Auspraegung; CLI sticht Config, Config sticht
        // Runtime-Default.
        val fromConfig: CheckpointConfig? = try {
            checkpointConfigResolver(request.cliConfigPath)
        } catch (e: Throwable) {
            stderr("Error: Failed to resolve pipeline.checkpoint config: ${e.message}")
            return 7
        }
        val mergedCheckpointConfig = CheckpointConfig.merge(
            cliDirectory = request.checkpointDir,
            config = fromConfig,
        )
        val checkpointDir: Path? = mergedCheckpointConfig.directory
        val store: CheckpointStore? = checkpointStoreFactory?.let { factory ->
            checkpointDir?.let { factory(it) }
        }

        // Resume-Kontext: bei `--resume` wird das Manifest geladen,
        // validiert und auf Kompatibilitaet gegen den aktuellen
        // Request geprueft. `skippedTables` bleibt in D.1 leer (das
        // Streaming schreibt noch keine `COMPLETED`-Slices); D.2/D.3
        // fuellen das sinnvoll.
        val resumeCtx: ResumeContext = if (!request.resume.isNullOrBlank()) {
            if (store == null) {
                stderr(
                    "Error: --resume requires a checkpoint directory; set " +
                        "--checkpoint-dir or pipeline.checkpoint.directory."
                )
                return 7
            }
            val resolvedOpId = resolveResumeReference(request.resume, checkpointDir!!)
                ?: run {
                    stderr(
                        "Error: --resume path must be inside the effective " +
                            "checkpoint directory '$checkpointDir'."
                    )
                    return 7
                }
            val manifest: CheckpointManifest? = try {
                store.load(resolvedOpId)
            } catch (e: UnsupportedCheckpointVersionException) {
                stderr("Error: ${e.message}")
                return 7
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to load checkpoint: ${e.message}")
                return 7
            }
            if (manifest == null) {
                stderr("Error: Checkpoint not found: '${request.resume}'")
                return 7
            }
            if (manifest.operationType != CheckpointOperationType.IMPORT) {
                stderr(
                    "Error: Checkpoint type mismatch: expected IMPORT, got " +
                        "${manifest.operationType}."
                )
                return 3
            }
            if (manifest.optionsFingerprint != fingerprint) {
                stderr(
                    "Error: Checkpoint options do not match the current request " +
                        "(fingerprint mismatch); refuse to resume."
                )
                return 3
            }
            val manifestTables = manifest.tableSlices.map { it.table }
            if (manifestTables != effectiveTables) {
                stderr(
                    "Error: Checkpoint table list does not match the current " +
                        "request: manifest=$manifestTables, current=$effectiveTables."
                )
                return 3
            }
            val skipped = manifest.tableSlices
                .filter { it.status == CheckpointSliceStatus.COMPLETED }
                .map { it.table }
                .toSet()
            // 0.9.0 Phase D.3 §5.3: pro Tabelle Resume-State ableiten.
            // Alle Slices, die weder `COMPLETED` sind noch bei 0 Chunks
            // stehen, bekommen einen Eintrag — der Importer skippt dann
            // die bereits bestaetigten Chunks und unterbindet
            // `truncate`.
            val resumeStates = manifest.tableSlices
                .filter { it.status != CheckpointSliceStatus.COMPLETED && it.chunksProcessed > 0L }
                .associate { slice ->
                    slice.table to dev.dmigrate.streaming.ImportTableResumeState(
                        committedChunks = slice.chunksProcessed,
                    )
                }
            ResumeContext(
                operationId = manifest.operationId,
                resuming = true,
                skippedTables = skipped,
                resumeStateByTable = resumeStates,
                initialSlices = manifest.tableSlices.associateBy { it.table },
            )
        } else {
            ResumeContext(
                operationId = java.util.UUID.randomUUID().toString(),
                resuming = false,
                skippedTables = emptySet(),
                resumeStateByTable = emptyMap(),
                initialSlices = effectiveTables.associateWith { table ->
                    CheckpointTableSlice(
                        table = table,
                        status = CheckpointSliceStatus.PENDING,
                    )
                },
            )
        }

        val operationId = resumeCtx.operationId

        // Initial-Manifest-Schreibpfad: bei neuem Lauf anlegen, damit
        // ein spaeterer Abbruch einen gueltigen Resume-Anker hat.
        // `createdAt`/`updatedAt` werden beim Resume-Lauf vom bereits
        // geladenen Manifest uebernommen (`complete()` raeumt am
        // Lauf-Ende auf).
        if (store != null && !resumeCtx.resuming) {
            val created = clock()
            val initial = CheckpointManifest(
                operationId = operationId,
                operationType = CheckpointOperationType.IMPORT,
                createdAt = created,
                updatedAt = created,
                format = request.format ?: format.name.lowercase(Locale.US),
                chunkSize = request.chunkSize,
                tableSlices = effectiveTables.map { table ->
                    CheckpointTableSlice(
                        table = table,
                        status = CheckpointSliceStatus.PENDING,
                    )
                },
                optionsFingerprint = fingerprint,
            )
            try {
                store.save(initial)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to initialize checkpoint: ${e.message}")
                return 7
            }
        }

        // 0.9.0 Phase D.3 §5.3: Manifest-Fortschreibung pro Chunk-
        // Commit und pro Tabellen-Ende. `createdAt` wird beim neuen
        // Lauf jetzt festgelegt, beim Resume aus dem bereits
        // geladenen Manifest uebernommen.
        val tableStates = LinkedHashMap(resumeCtx.initialSlices)
        val createdAt: Instant = if (resumeCtx.resuming && store != null) {
            try {
                store.load(operationId)?.createdAt ?: clock()
            } catch (_: Throwable) { clock() }
        } else {
            clock()
        }
        val onChunkCommitted: (dev.dmigrate.streaming.ImportChunkCommit) -> Unit = { commit ->
            // Manifest-Slice aktualisieren und persistieren.
            val slice = CheckpointTableSlice(
                table = commit.table,
                status = CheckpointSliceStatus.IN_PROGRESS,
                rowsProcessed = commit.rowsProcessedTotal,
                chunksProcessed = commit.chunksCommitted,
            )
            tableStates[commit.table] = slice
            if (store != null) {
                try {
                    store.save(
                        CheckpointManifest(
                            operationId = operationId,
                            operationType = CheckpointOperationType.IMPORT,
                            createdAt = createdAt,
                            updatedAt = clock(),
                            format = request.format ?: format.name.lowercase(Locale.US),
                            chunkSize = request.chunkSize,
                            tableSlices = effectiveTables.map {
                                tableStates[it] ?: CheckpointTableSlice(
                                    table = it,
                                    status = CheckpointSliceStatus.PENDING,
                                )
                            },
                            optionsFingerprint = fingerprint,
                        )
                    )
                } catch (_: CheckpointStoreException) {
                    // Wie im Export-Pfad: ein verlorener Zwischen-Save
                    // darf den Lauf nicht abbrechen; der naechste
                    // Chunk versucht erneut, und `complete()` meldet
                    // am Lauf-Ende ggf. den Fehler.
                }
            }
        }
        val onTableCompleted: (dev.dmigrate.streaming.TableImportSummary) -> Unit = { summary ->
            // §4.7: failedFinish oder error darf die Tabelle nicht
            // als `COMPLETED` markieren — der Manifest-Slice bleibt
            // dann auf `FAILED`, damit ein Resume die Tabelle nicht
            // stumm ueberspringt.
            val status = if (summary.error == null && summary.failedFinish == null) {
                CheckpointSliceStatus.COMPLETED
            } else {
                CheckpointSliceStatus.FAILED
            }
            val slice = CheckpointTableSlice(
                table = summary.table,
                status = status,
                // Bei COMPLETED: totalRows = inserted+updated+skipped+unknown+failed.
                // Der existierende Tabellen-Summary liefert die
                // Einzelzaehler; wir aggregieren fuer die Manifest-
                // Fortschreibung.
                rowsProcessed = summary.rowsInserted + summary.rowsUpdated +
                    summary.rowsSkipped + summary.rowsUnknown + summary.rowsFailed,
                chunksProcessed = tableStates[summary.table]?.chunksProcessed ?: 0L,
            )
            tableStates[summary.table] = slice
            if (store != null) {
                try {
                    store.save(
                        CheckpointManifest(
                            operationId = operationId,
                            operationType = CheckpointOperationType.IMPORT,
                            createdAt = createdAt,
                            updatedAt = clock(),
                            format = request.format ?: format.name.lowercase(Locale.US),
                            chunkSize = request.chunkSize,
                            tableSlices = effectiveTables.map {
                                tableStates[it] ?: CheckpointTableSlice(
                                    table = it,
                                    status = CheckpointSliceStatus.PENDING,
                                )
                            },
                            optionsFingerprint = fingerprint,
                        )
                    )
                } catch (_: CheckpointStoreException) {
                    // s.o.
                }
            }
        }

        // ─── 10. Streaming ─────────────────────────────────────
        val rawResult: ImportResult = try {
            val effectiveReporter = if (request.quiet || request.noProgress)
                NoOpProgressReporter else progressReporter
            importExecutor.execute(
                pool = pool,
                input = preparedImport.input,
                format = format,
                options = importOptions,
                config = pipelineConfig,
                onTableOpened = onTableOpened,
                progressReporter = effectiveReporter,
                operationId = operationId,
                resuming = resumeCtx.resuming,
                skippedTables = resumeCtx.skippedTables,
                resumeStateByTable = resumeCtx.resumeStateByTable,
                onChunkCommitted = onChunkCommitted,
                onTableCompleted = onTableCompleted,
            )
        } catch (e: UnsupportedTriggerModeException) {
            stderr("Error: ${e.message}")
            return 2
        } catch (e: ImportSchemaMismatchException) {
            stderr("Error: ${e.message}")
            return 3
        } catch (e: Throwable) {
            stderr("Error: Import failed: ${e.message}")
            return 5
        }
        val result: ImportResult = rawResult.copy(operationId = operationId)

        // ─── 11. Result auswerten ──────────────────────────────

        // Per-Tabelle-Fehler mit error → Exit 5
        val failedTable = result.tables.firstOrNull { it.error != null }
        if (failedTable != null) {
            stderr("Error: Failed to import table '${failedTable.table}': ${failedTable.error}")
            return 5
        }

        // failedFinish → Exit 5
        val failedFinish = result.tables.firstOrNull { it.failedFinish != null }
        if (failedFinish != null) {
            stderr(
                "Error: Post-import finalization failed for table '${failedFinish.table}': " +
                    "${failedFinish.failedFinish!!.causeMessage}. " +
                    "Data was committed — manual post-import fix may be needed."
            )
            return 5
        }

        // 0.9.0 Phase D.1 §5.1: erfolgreicher Lauf → Manifest
        // entfernen. Fehler beim `complete()` werden als Warning
        // gemeldet, nicht als Exit — der Import war fachlich
        // erfolgreich.
        if (store != null) {
            try {
                store.complete(operationId)
            } catch (e: CheckpointStoreException) {
                stderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
        }

        // ─── 12. ProgressSummary ───────────────────────────────
        val suppressProgress = request.quiet || request.noProgress
        if (!suppressProgress) {
            stderr(formatProgressSummary(result))
            // 0.9.0 Phase B §4.5: operationId in der stderr-Summary
            // referenzierbar — symmetrisch zum Export-Pfad.
            result.operationId?.let { stderr("Run operation id: $it") }
        }

        return 0
    }

    private fun resolveImportInput(
        request: DataImportRequest,
        isStdin: Boolean,
        sourcePath: Path?,
    ): ImportInput {
        if (isStdin) {
            val table = request.table
                ?: throw IllegalArgumentException("--table is required when reading from stdin (--source -).")
            return ImportInput.Stdin(table, stdinProvider())
        }

        requireNotNull(sourcePath)

        if (Files.isDirectory(sourcePath)) {
            if (request.table != null) {
                throw IllegalArgumentException(
                    "--table is only supported for stdin or single-file imports. Use --tables for directory sources."
                )
            }
            return ImportInput.Directory(
                path = sourcePath,
                tableFilter = request.tables,
            )
        }

        // Einzelne Datei
        val table = request.table
            ?: throw IllegalArgumentException(
                "--table is required when importing from a single file."
            )
        return ImportInput.SingleFile(table, sourcePath)
    }

    /**
     * 0.9.0 Phase D.1/D.3 (`docs/ImpPlan-0.9.0-D.md` §5.1 / §5.3):
     * interner Resume-Kontext eines Laufes. Seit D.3 traegt er auch
     * die per-Tabelle-Resume-States, die der Importer zum Skip-Ahead
     * und Truncate-Guard braucht, sowie den initialen Slice-State
     * fuer das Manifest-Fortschreiben nach jedem Chunk.
     */
    private data class ResumeContext(
        val operationId: String,
        val resuming: Boolean,
        val skippedTables: Set<String>,
        val resumeStateByTable: Map<String, dev.dmigrate.streaming.ImportTableResumeState>,
        val initialSlices: Map<String, CheckpointTableSlice>,
    )

    /**
     * 0.9.0 Phase D.1 §5.1: Aufloesung des
     * `--resume <checkpoint-id|path>`-Werts gegen das effektive
     * Checkpoint-Verzeichnis. Symmetrisch zum Export-Pfad:
     *
     * - Pfad-Kandidat (enthaelt `/` oder endet auf
     *   `.checkpoint.yaml`): Der Pfad muss normalized innerhalb des
     *   Checkpoint-Verzeichnisses liegen; sonst `null`. Der
     *   `operationId` wird aus dem Dateinamen abgeleitet (Suffix
     *   `.checkpoint.yaml` entfernt).
     * - Sonst: der Wert ist direkt eine `operationId`.
     */
    private fun resolveResumeReference(resumeValue: String, checkpointDir: Path): String? {
        val looksLikePath = '/' in resumeValue || resumeValue.endsWith(MANIFEST_SUFFIX)
        if (!looksLikePath) {
            return resumeValue
        }
        val candidate = try {
            Path.of(resumeValue).toAbsolutePath().normalize()
        } catch (_: Throwable) {
            return null
        }
        val baseDir = checkpointDir.toAbsolutePath().normalize()
        if (!candidate.startsWith(baseDir)) return null
        val fileName = candidate.fileName.toString()
        if (!fileName.endsWith(MANIFEST_SUFFIX)) return null
        return fileName.removeSuffix(MANIFEST_SUFFIX)
    }

    companion object {
        private const val MANIFEST_SUFFIX = ".checkpoint.yaml"

        private val EXTENSION_FORMAT_MAP = mapOf(
            "json" to "json",
            "yaml" to "yaml",
            "yml" to "yaml",
            "csv" to "csv",
        )

        fun inferFormatFromExtension(path: Path): String? {
            val fileName = path.fileName?.toString() ?: return null
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return EXTENSION_FORMAT_MAP[ext]
        }

        fun formatProgressSummary(result: ImportResult): String {
            val tableCount = result.tables.size
            val totalInserted = result.totalRowsInserted
            val totalUpdated = result.totalRowsUpdated
            val totalFailed = result.totalRowsFailed
            val seqCount = result.tables.flatMap { it.sequenceAdjustments }.size
            val durationSec = result.durationMs / 1000.0

            val parts = mutableListOf<String>()
            parts += "$totalInserted inserted"
            if (totalUpdated > 0) parts += "$totalUpdated updated"
            if (totalFailed > 0) parts += "$totalFailed failed"
            val rowsSummary = parts.joinToString(", ")

            val seqInfo = if (seqCount > 0) "; reseeded $seqCount sequence(s)" else ""
            return "Imported $tableCount table(s) ($rowsSummary) in ${"%.1f".format(Locale.US, durationSec)} s$seqInfo"
        }
    }
}
