package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SqliteAutoincrementReverse
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.cli.commands.verify.TransferVerifier
import dev.dmigrate.cli.commands.verify.VerifySide
import dev.dmigrate.cli.commands.verify.VerifyReport
import dev.dmigrate.verify.ValueCanonicalizer
import java.nio.file.Path

class TransferConfigException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)
class TransferPreflightException(msg: String) : RuntimeException(msg)

data class DataTransferRequest(
    val source: String, val target: String,
    val tables: List<String>? = null, val filter: ParsedFilter? = null,
    val sinceColumn: String? = null, val since: String? = null,
    val onConflict: String = "abort", val triggerMode: String = "fire",
    val truncate: Boolean = false, val chunkSize: Int = 10_000,
    // LN-007/LN-008: max. Nebenläufigkeit für unabhängige Tabellen/Partitionen (Default 1 = sequenziell).
    val parallel: Int = 1,
    // pipeline.parallelism-Slice: war --parallel CLI-explizit? Steuert Hard-Fail vs. Fallback bei --resume/--atomic.
    val parallelFromCli: Boolean = false,
    // Herkunftsbewusster Label für Klemm-/Fallback-Meldungen (--parallel N vs. pipeline.parallelism: auto (= N)).
    val parallelSourceLabel: String = "--parallel",
    // Read-only-Quelle (Default an über CLI-Flag; SQLite → SQLITE_OPEN_READONLY). Ziel bleibt read-write.
    val readOnly: Boolean = false,
    // LN-005: JDBC-Cursor-fetchSize für den Quell-Read (null = Dialekt-Default); auch der --verify-Read-Back nutzt ihn.
    val fetchSize: Int? = null,
    // LN-009: SHA-256 Quelle↔Ziel-Reconciliation nach dem Transfer.
    val verify: Boolean = false,
    // LN-013: atomarer Clean-Load — bei Fehler alle Tabellen auf leer zurück (erfordert truncate).
    val atomic: Boolean = false,
    val cliConfigPath: Path? = null,
    val quiet: Boolean = false, val noProgress: Boolean = false,
    // reverse-preferences: applies to any SQLite read — source OR target (F3).
    val sqliteAutoincrement: SqliteAutoincrementReverse = SqliteAutoincrementReverse.IDENTIFIER,
)

class DataTransferRunner(
    sourceResolver: (String, Path?) -> String,
    targetResolver: (String, Path?) -> String,
    urlParser: (String) -> ConnectionConfig,
    poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val driverLookup: (DatabaseDialect) -> DatabaseDriver,
    urlScrubber: (String) -> String = { it },
    printError: (String, String) -> Unit,
    stderr: (String) -> Unit = { System.err.println(it) },
    private val transferExecutor: TransferExecutor = TransferExecutor(),
    // LN-009: von der CLI-Wiring injiziert (formats-Adapter). Null → --verify meldet Konfigurationsfehler.
    private val valueCanonicalizer: ValueCanonicalizer? = null,
    // LN-049 Stufe 4: Per-Connection-Store-Filler (Identity-Default → MCP/Tests unverändert).
    credentialFiller: (ConnectionConfig, String) -> ConnectionConfig = { config, _ -> config },
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)
    private val connectionResolver = TransferConnectionResolver(
        sourceResolver = sourceResolver,
        targetResolver = targetResolver,
        urlParser = urlParser,
        poolFactory = poolFactory,
        urlScrubber = urlScrubber,
        userFacingErrors = userFacingErrors,
        printError = userFacingPrintError,
        credentialFiller = credentialFiller,
    )
    private val preflightPlanner = TransferPreflightPlanner()

    fun execute(
        request: DataTransferRequest,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): Int {
        return try {
            executeWithCancel(request, cancellationToken)
        } catch (_: OperationCancelledException) {
            // LF-008 / LF-009 / LF-013 — Cancel maps to CLI exit 130, never to the generic
            // 4 (schema read) or 5 (transfer error) paths.
            CANCELLED_EXIT_CODE
        }
    }

    private fun executeWithCancel(
        request: DataTransferRequest,
        cancellationToken: CancellationToken,
    ): Int {
        val safeSrc = userFacingErrors.scrubRef(request.source)

        val err = validateFlags(request)
        if (err != null) { userFacingPrintError(err, safeSrc); return 2 }

        cancellationToken.throwIfCancellationRequested()
        val connections = when (val result = connectionResolver.resolve(request)) {
            is TransferConnectionResult.Ok -> result.connections
            is TransferConnectionResult.Exit -> return result.code
        }

        try {
            return executeWithConnections(request, connections, cancellationToken)
        } finally { connections.close() }
    }

    /** Layer-/Tabellenplan des Preflights (leere `layers` = sequenzieller Pfad). */
    private class TransferPlan(val tables: List<String>, val layers: List<List<String>>)

    private fun planTransfer(
        request: DataTransferRequest,
        srcSchema: SchemaDefinition,
        tgtSchema: SchemaDefinition,
        tgtDrv: DatabaseDriver,
        tgtCfg: ConnectionConfig,
        degree: Int,
    ): TransferPlan {
        val compat = tgtDrv.transferCompatibility()
        val tgtCaps = DialectCapabilities.forDialect(tgtCfg.dialect)
        if (degree > 1) {
            val layers = preflightPlanner.planLayers(request, srcSchema, tgtSchema, compat, tgtCaps)
            return TransferPlan(layers.flatten(), layers)
        }
        return TransferPlan(preflightPlanner.planTables(request, srcSchema, tgtSchema, compat, tgtCaps), emptyList())
    }

    private fun executeWithConnections(
        request: DataTransferRequest,
        connections: TransferConnections,
        cancellationToken: CancellationToken,
    ): Int {
        val srcCfg = connections.source.config
        val tgtCfg = connections.target.config
        val srcPool = connections.source.pool
        val tgtPool = connections.target.pool
        val srcRef = connections.source.ref
        val tgtRef = connections.target.ref
        val srcDrv = driverLookup(srcCfg.dialect); val tgtDrv = driverLookup(tgtCfg.dialect)
        val readOpts = SchemaReadOptions(includeViews = false, includeProcedures = false,
            includeFunctions = false, includeTriggers = false,
            sqliteAutoincrement = request.sqliteAutoincrement)
        val srcSchema: SchemaDefinition; val tgtSchema: SchemaDefinition
        cancellationToken.throwIfCancellationRequested()
        try {
            srcSchema = srcDrv.schemaReader().read(srcPool, readOpts).schema
            cancellationToken.throwIfCancellationRequested()
            tgtSchema = tgtDrv.schemaReader().read(tgtPool, readOpts).schema
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) { userFacingPrintError("Schema read: ${e.message}", srcRef); return 4 }

        val sqliteInvolved = srcCfg.dialect == DatabaseDialect.SQLITE || tgtCfg.dialect == DatabaseDialect.SQLITE
        val degree = effectiveTransferParallelism(request, sqliteInvolved, userFacingStderr)

        // Sequential default keeps the linear FK order (byte-/order-identical); the parallel
        // path uses FK-safe layers. tables (flat) drives --verify/--atomic/progress either way.
        val layers: List<List<String>>
        val tables: List<String>
        try {
            val plan = planTransfer(request, srcSchema, tgtSchema, tgtDrv, tgtCfg, degree)
            layers = plan.layers
            tables = plan.tables
        } catch (e: TransferPreflightException) { userFacingPrintError("Preflight: ${e.message}", srcRef); return 3 }

        // LN-008: fan a partitioned parent out per child only when BOTH dialects address
        // partition children as standalone relations (PostgreSQL); else transparent parent.
        val partitionChildren = if (
            degree > 1 &&
            DialectCapabilities.forDialect(srcCfg.dialect).partitionChildrenAreTables &&
            DialectCapabilities.forDialect(tgtCfg.dialect).partitionChildrenAreTables
        ) {
            PartitionTransferExpansion.plan(srcSchema, tgtSchema, tables)
        } else {
            emptyMap()
        }

        val caps = DialectCapabilities.forDialect(tgtCfg.dialect)
        val triggerMode = TriggerMode.valueOf(request.triggerMode.uppercase())
        if (triggerMode == TriggerMode.DISABLE && !caps.supportsTriggerDisable) {
            userFacingPrintError("--trigger-mode disable is not supported for dialect ${tgtCfg.dialect}", tgtRef); return 2
        }
        if (triggerMode == TriggerMode.STRICT && !caps.supportsTriggerStrict) {
            userFacingPrintError("--trigger-mode strict is not supported for dialect ${tgtCfg.dialect}", tgtRef); return 2
        }

        val opts = ImportOptions(triggerMode = triggerMode,
            truncate = request.truncate, onConflict = OnConflict.valueOf(request.onConflict.uppercase()))
        val filter = DataExportHelpers.resolveFilter(
            parsedFilter = request.filter,
            dialect = srcCfg.dialect,
            sinceColumn = request.sinceColumn,
            since = request.since,
        )
        val reader = srcDrv.dataReader(request.fetchSize); val writer = tgtDrv.dataWriter()

        cancellationToken.throwIfCancellationRequested()
        try {
            transferExecutor.execute(
                TransferExecutionContext(
                    reader = reader,
                    writer = writer,
                    sourcePool = srcPool,
                    targetPool = tgtPool,
                    tables = tables,
                    filter = filter,
                    chunkSize = request.chunkSize,
                    importOptions = opts,
                    cancellationToken = cancellationToken,
                    layers = layers,
                    partitionChildren = partitionChildren,
                    parallelism = degree,
                )
            ) { table ->
                if (!request.quiet && !request.noProgress) userFacingStderr("  Transferred: $table")
            }
        } catch (e: OperationCancelledException) {
            // LF-008 / LF-009 / LF-013: Cancel must travel through this catch-all boundary
            // unmodified so the runner can map it to exit 130 instead of the
            // generic 5 path.
            throw e
        } catch (e: ImportSchemaMismatchException) {
            userFacingPrintError("Schema mismatch: ${e.message}", tgtRef); return 3
        } catch (e: UnsupportedTriggerModeException) {
            userFacingPrintError("Trigger mode: ${e.message}", tgtRef); return 2
        } catch (e: Exception) {
            userFacingPrintError("Transfer error: ${e.message}", srcRef)
            // LN-013: bei --atomic den vollen Zieltabellensatz auf leer zurücksetzen.
            if (request.atomic) AtomicCompensation.rollback(writer, tgtPool, tables, userFacingStderr)
            return 5
        }

        if (!request.quiet && !request.noProgress)
            userFacingStderr("Transfer complete: ${tables.size} table(s) $srcRef -> $tgtRef")

        if (!request.verify) return 0
        return verifyTransfer(
            request = request,
            tables = tables,
            source = VerifySide(reader, srcPool, srcSchema),
            target = VerifySide(tgtDrv.dataReader(request.fetchSize), tgtPool, tgtSchema),
            filter = filter,
            cancellationToken = cancellationToken,
            refs = TransferRefs(srcRef, tgtRef),
        )
    }

    /**
     * pipeline.parallelism-Slice: effektive Transfer-Parallelität — config-basiertes `parallel > 1`
     * + `--atomic` fällt auf 1 zurück (CLI-explizit fängt [validate] hart ab), danach die
     * SQLite-Klemme. Extrahiert, damit `executeWithConnections` unter der detekt-LongMethod-Grenze bleibt.
     */
    private fun effectiveTransferParallelism(
        request: DataTransferRequest,
        sqliteInvolved: Boolean,
        onNote: (String) -> Unit,
    ): Int {
        val requested = ParallelismClamp.fallbackIfIncompatible(
            request.parallel, request.parallelFromCli, request.parallelSourceLabel,
            incompatibleFlag = if (request.atomic) "--atomic" else null,
            onNote = onNote,
        )
        return ParallelismClamp.effective(requested, sqliteInvolved, request.parallelSourceLabel, onNote)
    }

    /** Quell- und Ziel-Bezeichner fuer Meldungen; sie treten nur gemeinsam auf. */
    private data class TransferRefs(val source: String, val target: String)

    private fun verifyTransfer(
        request: DataTransferRequest,
        tables: List<String>,
        source: VerifySide,
        target: VerifySide,
        filter: DataFilter?,
        cancellationToken: CancellationToken,
        refs: TransferRefs,
    ): Int {
        val srcRef = refs.source
        val tgtRef = refs.target
        val canonicalizer = valueCanonicalizer ?: run {
            userFacingPrintError("--verify requires a value canonicalizer (internal wiring error)", srcRef)
            return 7
        }
        val report = try {
            TransferVerifier(canonicalizer).verify(
                tables = tables,
                source = source,
                target = target,
                filter = filter,
                chunkSize = request.chunkSize,
                cancellationToken = cancellationToken,
            )
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: Exception) {
            userFacingPrintError("Verify error: ${e.message}", srcRef)
            return 5
        }
        return reportVerify(request, report, srcRef, tgtRef)
    }

    internal fun reportVerify(request: DataTransferRequest, report: VerifyReport, srcRef: String, tgtRef: String): Int {
        for (exclusion in report.exclusions) {
            userFacingStderr("W: verify excluded ${exclusion.table}.${exclusion.column}: ${exclusion.reason}")
        }
        if (report.allMatch) {
            if (!request.quiet && !request.noProgress) {
                val rows = report.tables.sumOf { it.sourceRows }
                userFacingStderr("Verify OK: ${report.tables.size} table(s), $rows row(s) match $srcRef <-> $tgtRef")
            }
            return 0
        }
        for (result in report.tables.filter { !it.match }) {
            val detail = when {
                result.error != null -> "inconclusive (${result.error})"
                result.sourceRows != result.targetRows -> "row count ${result.sourceRows} != ${result.targetRows}"
                else -> "checksum mismatch"
            }
            userFacingPrintError("Verify divergence: table '${result.table}': $detail", tgtRef)
        }
        return 3
    }

    companion object {
        /** CLI exit code for cooperative cancellation per `spec/job-contract.md`. */
        const val CANCELLED_EXIT_CODE = 130
    }

    private fun validateFlags(r: DataTransferRequest): String? {
        if (!r.sinceColumn.isNullOrBlank() && r.since.isNullOrBlank()) return "--since-column requires --since"
        if (!r.since.isNullOrBlank() && r.sinceColumn.isNullOrBlank()) return "--since requires --since-column"
        if (!r.sinceColumn.isNullOrBlank() && DataExportHelpers.firstInvalidTableIdentifier(listOf(r.sinceColumn)) != null) {
            return "--since-column '${r.sinceColumn}' is not a valid identifier"
        }
        // LN-013: --atomic ist destruktiv (Kompensation truncatet bei Fehler) → --truncate
        // muss explizit gesetzt sein, damit die Clean-Load-Zerstörung am Call-Site sichtbar ist.
        if (r.atomic && !r.truncate) return "--atomic requires --truncate"
        // LN-007/LN-008: degree of parallelism must be positive.
        if (r.parallel < 1) return "--parallel must be >= 1, got ${r.parallel}"
        // LN-013 × LN-007/LN-008: parallel workers can still be committing when the
        // atomic compensation truncates → the all-or-nothing guarantee would be racy.
        // pipeline.parallelism-Slice: harter Fehler nur bei CLI-explizitem --parallel; kommt der Wert
        // aus der Config, fällt der Lauf am Clamp auf 1 zurück (s. requestedParallel weiter oben).
        if (r.atomic && r.parallel > 1 && r.parallelFromCli) return "--atomic is incompatible with --parallel > 1"
        // No --filter validation needed: filter is already parsed into
        // ParsedFilter by the CLI layer before constructing DataTransferRequest.
        try { TriggerMode.valueOf(r.triggerMode.uppercase()) } catch (_: Exception) { return "Unknown --trigger-mode: ${r.triggerMode}" }
        try { OnConflict.valueOf(r.onConflict.uppercase()) } catch (_: Exception) { return "Unknown --on-conflict: ${r.onConflict}" }
        return null
    }

}
