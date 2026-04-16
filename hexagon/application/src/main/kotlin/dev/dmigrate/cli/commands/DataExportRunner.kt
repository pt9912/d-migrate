package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.streaming.ExportOutput
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointResumePosition
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import java.nio.charset.Charset
import java.nio.file.Path
import java.time.Instant

/**
 * Thin seam over the streaming export, allowing the Runner to be tested
 * without a real [StreamingExporter][dev.dmigrate.streaming.StreamingExporter].
 * The production implementation is wired in the CLI module.
 */
fun interface ExportExecutor {
    fun execute(
        pool: ConnectionPool,
        reader: DataReader,
        lister: TableLister,
        factory: DataChunkWriterFactory,
        tables: List<String>,
        output: ExportOutput,
        format: DataExportFormat,
        options: ExportOptions,
        config: PipelineConfig,
        filter: DataFilter?,
        progressReporter: ProgressReporter,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §4.3 / §5.3):
         * stabile `operationId` des Laufs. Wird vom Runner gesetzt
         * (UUID oder aus Manifest), vom Executor an
         * `StreamingExporter.export` durchgereicht und landet so im
         * `ProgressEvent.RunStarted`.
         */
        operationId: String?,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §3.1 / §5.3):
         * `true`, wenn der Lauf eine Wiederaufnahme aus einem
         * vorhandenen Manifest ist; `false` fuer einen neuen Lauf. Der
         * `ProgressRenderer` differenziert anhand dieser Flagge das
         * „Starting run …"- vs. „Resuming run …"-Label.
         */
        resuming: Boolean,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.4): Menge der
         * bereits im Manifest als `COMPLETED` markierten Tabellen. Wird
         * vom Runner aus dem geladenen Manifest gefuellt; bei einem
         * neuen Lauf leer.
         */
        skippedTables: Set<String>,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.3 / §5.4):
         * Callback pro abgeschlossener Tabelle. Der Runner nutzt ihn,
         * um das Checkpoint-Manifest fortzuschreiben.
         */
        onTableCompleted: (dev.dmigrate.streaming.TableExportSummary) -> Unit,
        /**
         * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §5.2 / §5.3):
         * Pro Tabelle optionaler [ResumeMarker] — fehlt der Eintrag,
         * laeuft die Tabelle im Legacy-Pfad ohne Marker-Sortierung. Mit
         * Eintrag aktiviert der Exporter Mid-Table-Resume
         * (Fresh-Track oder Resume-From-Position abhaengig von
         * `ResumeMarker.position`).
         */
        resumeMarkers: Map<String, ResumeMarker>,
        /**
         * 0.9.0 Phase C.2 §5.2: Chunk-granularer Fortschritts-Callback.
         * Der Runner aktualisiert das Manifest mit jedem Aufruf — nur
         * Tabellen mit aktivem [ResumeMarker] loesen diesen Callback
         * aus.
         */
        onChunkProcessed: (dev.dmigrate.streaming.TableChunkProgress) -> Unit,
    ): ExportResult
}

/**
 * Immutable DTO with all CLI inputs for `d-migrate data export`.
 */
data class DataExportRequest(
    val source: String,
    val format: String,
    val output: Path?,
    val tables: List<String>?,
    val filter: String?,
    val sinceColumn: String?,
    val since: String?,
    val encoding: String,
    val chunkSize: Int,
    val splitFiles: Boolean,
    val csvDelimiter: String,
    val csvBom: Boolean,
    val csvNoHeader: Boolean,
    val nullString: String,
    val cliConfigPath: Path?,
    val quiet: Boolean,
    val noProgress: Boolean,
    /**
     * 0.9.0 Phase A (`docs/ImpPlan-0.9.0-A.md` §4.3/§4.4): expliziter
     * Resume-Einstieg. Wert ist eine Checkpoint-ID oder ein Pfad zu
     * einem Manifest; der konkrete Referenzformat-Vertrag wird in
     * Phase B/C des Milestones festgezogen. In Phase A ist der Flag
     * Teil des CLI-Vertrags; der Runner akzeptiert ihn, lehnt
     * inkompatible Kombinationen (stdout-Export, etc.) ab und
     * signalisiert andernfalls sichtbar, dass die Resume-Runtime noch
     * folgt.
     */
    val resume: String? = null,
    /**
     * 0.9.0 Phase A §4.3: optionales Verzeichnis fuer Checkpoints. Ist
     * Config-Default in `pipeline.checkpoint.directory` vorhanden,
     * gewinnt dieser CLI-Wert (Phase A §3.1 letzter Punkt).
     */
    val checkpointDir: Path? = null,
)

/**
 * Core logic for `d-migrate data export`. All external collaborators are
 * constructor-injected so every branch (including error paths and exit
 * codes) is unit-testable without a real database or CLI framework.
 *
 * Exit codes (Plan §6.10):
 * - 0 success
 * - 2 CLI validation error (incl. `--resume` on stdout-Export, unsupported
 *   `--lang` handled in the root CLI)
 * - 3 resume preflight failure — semantically incompatible checkpoint
 *   reference (e.g. table list changed, schema divergent). 0.9.0 Phase A
 *   (`docs/ImpPlan-0.9.0-A.md` §4.5): mapping wird hier explizit
 *   festgezogen und ist symmetrisch zum `DataImportRunner`-Preflight. Die
 *   tatsaechliche Manifest-Pruefung liefert Phase B bis D.
 * - 4 connection / table-lister error
 * - 5 export streaming error
 * - 7 config / URL / registry error (incl. unreadable checkpoint file or
 *   unparseable manifest — 0.9.0 Phase A §4.5)
 */
class DataExportRunner(
    private val sourceResolver: (source: String, configPath: Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val readerLookup: (DatabaseDialect) -> DataReader,
    private val listerLookup: (DatabaseDialect) -> TableLister,
    private val writerFactoryBuilder: () -> DataChunkWriterFactory,
    private val collectWarnings: () -> List<String>,
    private val exportExecutor: ExportExecutor,
    private val progressReporter: ProgressReporter = NoOpProgressReporter,
    private val stderr: (String) -> Unit = { System.err.println(it) },
    /**
     * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.2): Factory fuer
     * den Checkpoint-Store. Bekommt das effektive Checkpoint-
     * Verzeichnis. Die CLI-Seite wired den dateibasierten Adapter;
     * Tests injizieren einen In-Memory-Store. `null` hier = Resume-
     * Support deaktiviert (fuer Tests, die keine Manifest-Interaktion
     * wollen).
     */
    private val checkpointStoreFactory: ((Path) -> CheckpointStore)? = null,
    /**
     * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.2 / §4.4 Ueberplan):
     * liest den `pipeline.checkpoint.*`-Block aus der effektiven
     * `.d-migrate.yaml` (CLI-Config-Pfad-Resolution ist dieselbe wie
     * fuer andere Abschnitte: `--config` > `D_MIGRATE_CONFIG` > Default).
     * Der Runner mergt ueber [CheckpointConfig.merge] CLI-Override
     * (`--checkpoint-dir`) und Config-Default zusammen.
     *
     * Default `{ null }` haelt Tests quellkompatibel — Runner ohne
     * Config-Resolver verwenden nur den CLI-Wert.
     */
    private val checkpointConfigResolver: (Path?) -> CheckpointConfig? = { null },
    /**
     * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.2): Zeitquelle
     * fuer Manifest-`createdAt`/`updatedAt`. Separat injizierbar fuer
     * deterministische Tests.
     */
    private val clock: () -> Instant = Instant::now,
    /**
     * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §4.1 / §5.3):
     * Lookup fuer die Primaerschluessel-Spalten einer Tabelle. Wird
     * nur fuer den Mid-Table-Resume-Pfad benoetigt — Laeufe ohne
     * `--since-column` rufen den Lookup nicht auf.
     *
     * Produktive Wiring injiziert einen memoisierten SchemaReader-
     * Aufruf (siehe `DataExportCommand`); Tests injizieren die
     * benoetigte Mapping-Tabelle direkt. Default `{ emptyList() }`
     * simuliert "kein PK gefunden" — Fall 2 (konservativer
     * C.1-Fallback).
     */
    private val primaryKeyLookup: (ConnectionPool, DatabaseDialect, String) -> List<String> =
        { _, _, _ -> emptyList() },
) {

    fun execute(request: DataExportRequest): Int {
        // ─── 1. Source auflösen → vollständige Connection-URL ───
        val resolvedUrl = try {
            sourceResolver(request.source, request.cliConfigPath)
        } catch (e: Exception) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 2. URL → ConnectionConfig ──────────────────────────
        val connectionConfig = try {
            urlParser(resolvedUrl)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 2b. Incremental-export CLI-Preflight (LF-013 / M-R5) ──
        val hasSinceColumn = !request.sinceColumn.isNullOrBlank()
        val hasSinceValue = !request.since.isNullOrBlank()
        if (hasSinceColumn != hasSinceValue) {
            stderr("Error: --since-column and --since must be used together.")
            return 2
        }
        if (hasSinceColumn) {
            val invalidSinceColumn = DataExportHelpers.firstInvalidQualifiedIdentifier(request.sinceColumn!!)
            if (invalidSinceColumn != null) {
                stderr(
                    "Error: --since-column value '$invalidSinceColumn' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
            if (DataExportHelpers.containsLiteralQuestionMark(request.filter)) {
                stderr(
                    "Error: --filter must not contain literal '?' when combined with --since " +
                        "(parameterized query); use a rewritten predicate or escape the literal differently"
                )
                return 2
            }
        }

        // ─── 2c. 0.9.0 Phase A §4.4 / Phase C.1: Resume-CLI-Preflight ──
        // stdout-Export bleibt als Resume-Ziel ausgeschlossen (Phase A
        // §4.4); der Lauf kann seinen Stream nicht wiedereroeffnen.
        if (!request.resume.isNullOrBlank() && request.output == null) {
            stderr(
                "Error: --resume is not supported for stdout export; " +
                    "set --output <file-or-dir> or drop --resume."
            )
            return 2
        }

        // ─── 3. Encoding parsen ─────────────────────────────────
        val charset = try {
            Charset.forName(request.encoding)
        } catch (e: Exception) {
            stderr("Error: Unknown encoding '${request.encoding}': ${e.message}")
            return 2
        }

        // ─── 4. Pool öffnen ─────────────────────────────────────
        val pool: ConnectionPool = try {
            poolFactory(connectionConfig)
        } catch (e: Throwable) {
            stderr("Error: Failed to connect to database: ${e.message}")
            return 4
        }

        return try {
            executeWithPool(request, connectionConfig, charset, pool)
        } finally {
            try { pool.close() } catch (_: Throwable) {}
        }
    }

    private fun executeWithPool(
        request: DataExportRequest,
        connectionConfig: ConnectionConfig,
        charset: Charset,
        pool: ConnectionPool,
    ): Int {
        // ─── 5. Reader + Lister aus Registry ───────────────────
        val reader = try {
            readerLookup(connectionConfig.dialect)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }
        val tableLister = try {
            listerLookup(connectionConfig.dialect)
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 7
        }

        // ─── 6. Tabellen ermitteln (--tables oder Auto-Discovery) ───
        val explicitTables = request.tables?.takeIf { it.isNotEmpty() }
        if (explicitTables != null) {
            val invalid = DataExportHelpers.firstInvalidTableIdentifier(explicitTables)
            if (invalid != null) {
                stderr(
                    "Error: --tables value '$invalid' is not a valid identifier. " +
                        "Expected '<name>' or '<schema>.<name>' matching " +
                        DataExportHelpers.TABLE_IDENTIFIER_PATTERN + "."
                )
                return 2
            }
        }
        val effectiveTables = explicitTables ?: try {
            tableLister.listTables(pool)
        } catch (e: Throwable) {
            stderr("Error: Failed to list tables: ${e.message}")
            return 4
        }
        if (effectiveTables.isEmpty()) {
            stderr("Error: No tables to export.")
            return 2
        }

        // ─── 7. ExportOutput auflösen ─────────────────────────
        val exportOutput = try {
            ExportOutput.resolve(
                outputPath = request.output,
                splitFiles = request.splitFiles,
                tableCount = effectiveTables.size,
            )
        } catch (e: IllegalArgumentException) {
            stderr("Error: ${e.message}")
            return 2
        }

        // ─── 8. ExportOptions aus den CLI-Flags bauen ─────────
        val delimiterChar = DataExportHelpers.parseCsvDelimiter(request.csvDelimiter)
            ?: run {
                stderr("Error: --csv-delimiter must be a single character, got '${request.csvDelimiter}'")
                return 2
            }
        val exportOptions = ExportOptions(
            encoding = charset,
            csvHeader = !request.csvNoHeader,
            csvDelimiter = delimiterChar,
            csvBom = request.csvBom,
            csvNullString = request.nullString,
        )

        val factory = writerFactoryBuilder()
        val effectiveFilter = DataExportHelpers.resolveFilter(
            rawFilter = request.filter,
            dialect = connectionConfig.dialect,
            sinceColumn = request.sinceColumn,
            since = request.since,
        )

        // ─── 8b. 0.9.0 Phase C.1/C.2: Resume-Preflight + Manifest-Lifecycle ──
        // 0.9.0 Phase C.2 §4.1: PK-Signatur wird nur dann eingelesen,
        // wenn `--since-column` gesetzt ist — ohne Marker-Kontrakt
        // gibt es kein Mid-Table-Resume und damit auch keinen Grund,
        // den Schema-Reader anzufassen. Das haelt den Phase-C.1-Fingerprint
        // bytegleich und den PG/MySQL/SQLite-Schemaload fuer den
        // klassischen Exportpfad leer.
        val primaryKeysByTable: Map<String, List<String>> =
            if (!request.sinceColumn.isNullOrBlank()) {
                resolvePrimaryKeys(pool, connectionConfig.dialect, effectiveTables)
            } else {
                emptyMap()
            }
        val fingerprint = ExportOptionsFingerprint.compute(
            ExportOptionsFingerprint.Input(
                format = request.format,
                encoding = request.encoding,
                csvDelimiter = request.csvDelimiter,
                csvBom = request.csvBom,
                csvNoHeader = request.csvNoHeader,
                csvNullString = request.nullString,
                filter = request.filter,
                sinceColumn = request.sinceColumn,
                since = request.since,
                tables = effectiveTables,
                outputMode = canonicalOutputMode(exportOutput),
                outputPath = canonicalOutputPath(exportOutput),
                primaryKeysByTable = primaryKeysByTable,
            )
        )

        // 0.9.0 Phase C.1 §4.4 / §5.2: zentraler Merge aus
        // CLI-Override (`--checkpoint-dir`) und Config-Default
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

        // Resume-Pfad vorbereiten
        data class ResumeContext(
            val operationId: String,
            val resuming: Boolean,
            val skippedTables: Set<String>,
            val initialSlices: Map<String, CheckpointTableSlice>,
        )

        val resume: ResumeContext = if (!request.resume.isNullOrBlank()) {
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
            if (manifest.operationType != CheckpointOperationType.EXPORT) {
                stderr(
                    "Error: Checkpoint type mismatch: expected EXPORT, got " +
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
            // 0.9.0 Phase C.1 §5.5: Single-File-Resume verlangt genau
            // eine pending Tabelle. Wenn die eine Tabelle bereits
            // COMPLETED ist, ist der Lauf fertig — Exit 3 statt
            // silent success, damit Automatisierung das eindeutig
            // erkennt.
            if (exportOutput is ExportOutput.SingleFile &&
                effectiveTables.isNotEmpty() &&
                effectiveTables.all { it in skipped }
            ) {
                stderr(
                    "Error: single-file resume has no pending table; the " +
                        "previous run is already completed. Remove --resume " +
                        "or choose a different output."
                )
                return 3
            }
            ResumeContext(
                operationId = manifest.operationId,
                resuming = true,
                skippedTables = skipped,
                initialSlices = manifest.tableSlices.associateBy { it.table },
            )
        } else {
            ResumeContext(
                operationId = java.util.UUID.randomUUID().toString(),
                resuming = false,
                skippedTables = emptySet(),
                initialSlices = effectiveTables.associateWith { table ->
                    CheckpointTableSlice(
                        table = table,
                        status = CheckpointSliceStatus.PENDING,
                    )
                },
            )
        }

        val operationId = resume.operationId
        val tableStates = LinkedHashMap(resume.initialSlices)
        val now = { clock() }

        // Initialer Manifest-Schreibpfad: bei neuem Lauf anlegen,
        // damit ein spaeterer Abbruch einen gueltigen Resume-Anker hat.
        if (store != null && !resume.resuming) {
            val created = now()
            val initial = CheckpointManifest(
                operationId = operationId,
                operationType = CheckpointOperationType.EXPORT,
                createdAt = created,
                updatedAt = created,
                format = request.format,
                chunkSize = request.chunkSize,
                tableSlices = effectiveTables.map { tableStates.getValue(it) },
                optionsFingerprint = fingerprint,
            )
            try {
                store.save(initial)
            } catch (e: CheckpointStoreException) {
                stderr("Error: Failed to initialize checkpoint: ${e.message}")
                return 7
            }
        }

        // Per-Tabelle-Update-Callback (§5.3 / §5.4): `createdAt` wird
        // beim neuen Lauf jetzt festgelegt, beim Resume aus dem bereits
        // geladenen Manifest uebernommen.
        val createdAt: Instant = if (resume.resuming && store != null) {
            try {
                store.load(operationId)?.createdAt ?: now()
            } catch (_: Throwable) { now() }
        } else {
            now()
        }

        val onTableCompleted: (dev.dmigrate.streaming.TableExportSummary) -> Unit = { summary ->
            val slice = CheckpointTableSlice(
                table = summary.table,
                status = if (summary.error == null)
                    CheckpointSliceStatus.COMPLETED else CheckpointSliceStatus.FAILED,
                rowsProcessed = summary.rows,
                chunksProcessed = summary.chunks,
                // 0.9.0 Phase C.2 §5.2: bei COMPLETED wird die
                // Resume-Position bewusst nicht mehr mitgeschrieben —
                // die Tabelle ist fertig, und ein erneuter Resume
                // ueberspringt sie im C.1-Pfad komplett.
                resumePosition = null,
            )
            tableStates[summary.table] = slice
            if (store != null) {
                try {
                    store.save(
                        CheckpointManifest(
                            operationId = operationId,
                            operationType = CheckpointOperationType.EXPORT,
                            createdAt = createdAt,
                            updatedAt = now(),
                            format = request.format,
                            chunkSize = request.chunkSize,
                            tableSlices = effectiveTables.map { tableStates.getValue(it) },
                            optionsFingerprint = fingerprint,
                        )
                    )
                } catch (_: CheckpointStoreException) {
                    // Fortsetzung darf an einem fehlgeschlagenen
                    // Zwischen-Save nicht abbrechen; der finale Lauf
                    // meldet dann ggf. den Fehler. Stderr-Hinweis
                    // wuerde den Progress-Output zerreissen — wir
                    // lassen den Write stillschweigend fallen und
                    // fangen ihn am Lauf-Ende nochmal (complete()).
                }
            }
        }

        // 0.9.0 Phase C.2 §4.1 / §5.2: per-Tabelle-ResumeMarker auf
        // Basis der 3 Fallunterscheidungen. Der Durchlauf kann mit
        // Exit 3 abbrechen (Fall 3). Ein leeres Ergebnis (alle
        // Tabellen ohne Marker) haelt den bisherigen C.1-Pfad vor.
        val resumeMarkers: Map<String, ResumeMarker> = try {
            effectiveTables.associateWith { table ->
                resolveTableResumeMarker(
                    request = request,
                    table = table,
                    existingPosition = resume.initialSlices[table]?.resumePosition,
                    primaryKey = primaryKeysByTable[table].orEmpty(),
                )
            }.filterValues { it != null }.mapValues { it.value!! }
        } catch (e: TableResumeMismatchException) {
            stderr("Error: ${e.message}")
            return 3
        }

        // 0.9.0 Phase C.2 §5.2: onChunkProcessed persistiert den
        // per-Chunk-Fortschritt. Die Tabelle wechselt in
        // `IN_PROGRESS`, sodass ein Wieder-Lauf die Position findet.
        val onChunkProcessed: (dev.dmigrate.streaming.TableChunkProgress) -> Unit = { progress ->
            val marker = resumeMarkers[progress.table]
            if (marker != null) {
                val slice = CheckpointTableSlice(
                    table = progress.table,
                    status = CheckpointSliceStatus.IN_PROGRESS,
                    rowsProcessed = progress.rowsProcessed,
                    chunksProcessed = progress.chunksProcessed,
                    resumePosition = MarkerCodec.toPersisted(marker, progress.position),
                )
                tableStates[progress.table] = slice
                if (store != null) {
                    try {
                        store.save(
                            CheckpointManifest(
                                operationId = operationId,
                                operationType = CheckpointOperationType.EXPORT,
                                createdAt = createdAt,
                                updatedAt = now(),
                                format = request.format,
                                chunkSize = request.chunkSize,
                                tableSlices = effectiveTables.map { tableStates.getValue(it) },
                                optionsFingerprint = fingerprint,
                            )
                        )
                    } catch (_: CheckpointStoreException) {
                        // Gleiches Prinzip wie in `onTableCompleted`: ein
                        // verlorener Zwischen-Save darf den Lauf nicht
                        // abbrechen; beim naechsten Chunk versucht der
                        // Runner es erneut.
                    }
                }
            }
        }

        // ─── 9. Streaming ─────────────────────────────────────
        val rawResult: ExportResult = try {
            val effectiveReporter = if (request.quiet || request.noProgress)
                NoOpProgressReporter else progressReporter
            exportExecutor.execute(
                pool = pool,
                reader = reader,
                lister = tableLister,
                factory = factory,
                tables = effectiveTables,
                output = exportOutput,
                format = DataExportFormat.fromCli(request.format),
                options = exportOptions,
                config = PipelineConfig(chunkSize = request.chunkSize),
                filter = effectiveFilter,
                progressReporter = effectiveReporter,
                operationId = operationId,
                resuming = resume.resuming,
                skippedTables = resume.skippedTables,
                onTableCompleted = onTableCompleted,
                resumeMarkers = resumeMarkers,
                onChunkProcessed = onChunkProcessed,
            )
        } catch (e: Throwable) {
            stderr("Error: Export failed: ${e.message}")
            return 5
        }
        val result: ExportResult = rawResult.copy(operationId = operationId)

        // ─── 10. Pro-Tabelle-Fehler → Exit 5 ──────────────────
        val failed = result.tables.firstOrNull { it.error != null }
        if (failed != null) {
            stderr("Error: Failed to export table '${failed.table}': ${failed.error}")
            return 5
        }

        // 0.9.0 Phase C.1 §5.2: erfolgreicher Lauf → Manifest entfernen.
        // Fehler beim complete() werden als Warning gemeldet, nicht als
        // Exit — der Export war fachlich erfolgreich.
        if (store != null) {
            try {
                store.complete(operationId)
            } catch (e: CheckpointStoreException) {
                stderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
        }

        // ─── 11. Warnings auf stderr (unterdrückt mit --quiet) ──
        if (!request.quiet) {
            for (line in collectWarnings()) {
                stderr(line)
            }
        }

        // ─── 12. ProgressSummary (unterdrückt mit --quiet/--no-progress) ──
        val suppressProgress = request.quiet || request.noProgress
        if (!suppressProgress) {
            stderr(DataExportHelpers.formatProgressSummary(result))
            // 0.9.0 Phase B §4.5: operationId ist in der stderr-Summary
            // referenzierbar (nicht nur als Logging-Dekoration) — so
            // koennen Operator, Logs und spaeteres Resume-Manifest auf
            // denselben Lauf verweisen.
            result.operationId?.let { stderr("Run operation id: $it") }
        }

        return 0
    }

    // ──────────────────────────────────────────────────────────────
    // 0.9.0 Phase C.1 §5.2: Hilfsfunktionen fuer Resume-Referenz und
    // Output-Mode-Kanonisierung.
    // ──────────────────────────────────────────────────────────────

    /**
     * Aufloesung des `--resume <checkpoint-id|path>`-Werts gegen das
     * effektive Checkpoint-Verzeichnis (Plan §4.3 / §5.2):
     *
     * - Pfad-Kandidat (enthaelt `/` oder endet auf `.checkpoint.yaml`):
     *   Der Pfad muss normalized innerhalb des Checkpoint-Verzeichnisses
     *   liegen; sonst `null`. Der `operationId` wird aus dem Dateinamen
     *   abgeleitet (Suffix `.checkpoint.yaml` entfernt).
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

    /**
     * 0.9.0 Phase C.2 §4.1 / §5.3: memoisiert den PK-Lookup ueber die
     * gegebenen Tabellen. Fehlschlaege pro Tabelle landen in einer
     * leeren Liste (Fall 2 → C.1-Fallback), damit ein kaputter
     * Schema-Reader den gesamten Lauf nicht lahmlegt.
     */
    private fun resolvePrimaryKeys(
        pool: ConnectionPool,
        dialect: DatabaseDialect,
        tables: List<String>,
    ): Map<String, List<String>> = tables.associateWith { table ->
        try {
            primaryKeyLookup(pool, dialect, table)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 0.9.0 Phase C.2 §4.1: Drei Fallunterscheidungen beim Bestimmen
     * der Per-Tabelle-Resume-Strategie.
     *
     * - Fall 1 (kein `--since-column`, kein Manifest-`resumePosition`)
     *   → `null` (C.1-Fallback, kein stderr-Hinweis).
     * - Fall 2 (`--since-column` gesetzt, Tabelle ohne PK) → `null`
     *   mit sichtbarem stderr-Hinweis.
     * - Fall 3 (Manifest hat `resumePosition`, aber aktueller Request
     *   hat kein `--since-column`) → `Throw TableResumeMismatchException`
     *   — der Aufrufer uebersetzt das in Exit 3.
     *
     * Fresh-Track (mit PK, ohne bereits vorhandene Position) liefert
     * einen [ResumeMarker] ohne Position; Resume-From-Position liefert
     * einen [ResumeMarker] inkl. Position.
     */
    private fun resolveTableResumeMarker(
        request: DataExportRequest,
        table: String,
        existingPosition: CheckpointResumePosition?,
        primaryKey: List<String>,
    ): ResumeMarker? {
        val sinceColumn = request.sinceColumn?.takeIf { it.isNotBlank() }
        // Fall 3: Manifest traegt Position, aber Request hat kein since-column
        if (existingPosition != null && sinceColumn == null) {
            throw TableResumeMismatchException(table, existingPosition.markerColumn)
        }
        // Fall 1: ohne since-column kein Mid-Table-Resume — C.1-Fallback
        if (sinceColumn == null) return null
        // Fall 2: PK fehlt -> C.1-Fallback mit stderr-Hinweis
        if (primaryKey.isEmpty()) {
            stderr(
                "Warning: mid-table resume disabled for table '$table': " +
                    "no primary key found for tie-breaker; re-exporting from scratch."
            )
            return null
        }
        // Fresh-Track oder Resume-From-Position
        val position = existingPosition?.let { persisted ->
            MarkerCodec.toRuntimePosition(persisted)
        }
        return ResumeMarker(
            markerColumn = sinceColumn,
            tieBreakerColumns = primaryKey,
            position = position,
        )
    }

    private fun canonicalOutputMode(output: ExportOutput): String = when (output) {
        is ExportOutput.Stdout -> "stdout"
        is ExportOutput.SingleFile -> "single-file"
        is ExportOutput.FilePerTable -> "file-per-table"
    }

    private fun canonicalOutputPath(output: ExportOutput): String = when (output) {
        is ExportOutput.Stdout -> "<stdout>"
        is ExportOutput.SingleFile -> output.path.toAbsolutePath().normalize().toString()
        is ExportOutput.FilePerTable -> output.directory.toAbsolutePath().normalize().toString()
    }

    companion object {
        private const val MANIFEST_SUFFIX = ".checkpoint.yaml"
    }
}

/**
 * 0.9.0 Phase C.2 §4.1 Fall 3: Manifest traegt eine mid-table
 * `resumePosition`, aber der aktuelle Request hat kein `--since-column`.
 * Die Laufvertrage sind inkompatibel; der Runner uebersetzt diese
 * Exception in Exit 3.
 */
internal class TableResumeMismatchException(
    val table: String,
    val markerColumn: String,
) : RuntimeException(
    "Checkpoint for table '$table' carries a mid-table marker on column " +
        "'$markerColumn', but the current request has no --since-column; refuse to resume."
)
