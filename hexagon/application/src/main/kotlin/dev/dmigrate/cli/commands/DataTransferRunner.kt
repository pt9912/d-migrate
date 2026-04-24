package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.core.data.ImportSchemaMismatchException
import java.nio.file.Path

class TransferConfigException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)
class TransferPreflightException(msg: String) : RuntimeException(msg)

data class DataTransferRequest(
    val source: String, val target: String,
    val tables: List<String>? = null, val filter: ParsedFilter? = null,
    val sinceColumn: String? = null, val since: String? = null,
    val onConflict: String = "abort", val triggerMode: String = "fire",
    val truncate: Boolean = false, val chunkSize: Int = 10_000,
    val cliConfigPath: Path? = null,
    val quiet: Boolean = false, val noProgress: Boolean = false,
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
    )
    private val preflightPlanner = TransferPreflightPlanner()
    private val transferExecutor = TransferExecutor()

    fun execute(request: DataTransferRequest): Int {
        val safeSrc = userFacingErrors.scrubRef(request.source)

        val err = validateFlags(request)
        if (err != null) { userFacingPrintError(err, safeSrc); return 2 }

        val connections = when (val result = connectionResolver.resolve(request)) {
            is TransferConnectionResult.Ok -> result.connections
            is TransferConnectionResult.Exit -> return result.code
        }

        try {
            return executeWithConnections(request, connections)
        } finally { connections.close() }
    }

    private fun executeWithConnections(request: DataTransferRequest, connections: TransferConnections): Int {
        val srcCfg = connections.source.config
        val tgtCfg = connections.target.config
        val srcPool = connections.source.pool
        val tgtPool = connections.target.pool
        val srcRef = connections.source.ref
        val tgtRef = connections.target.ref
        val srcDrv = driverLookup(srcCfg.dialect); val tgtDrv = driverLookup(tgtCfg.dialect)
        val readOpts = SchemaReadOptions(includeViews = false, includeProcedures = false,
            includeFunctions = false, includeTriggers = false)
        val srcSchema: SchemaDefinition; val tgtSchema: SchemaDefinition
        try {
            srcSchema = srcDrv.schemaReader().read(srcPool, readOpts).schema
            tgtSchema = tgtDrv.schemaReader().read(tgtPool, readOpts).schema
        } catch (e: Exception) { userFacingPrintError("Schema read: ${e.message}", srcRef); return 4 }

        val tables: List<String>
        try { tables = preflightPlanner.planTables(request, srcSchema, tgtSchema) }
        catch (e: TransferPreflightException) { userFacingPrintError("Preflight: ${e.message}", srcRef); return 3 }

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
        val reader = srcDrv.dataReader(); val writer = tgtDrv.dataWriter()

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
                )
            ) { table ->
                if (!request.quiet && !request.noProgress) userFacingStderr("  Transferred: $table")
            }
        } catch (e: ImportSchemaMismatchException) {
            userFacingPrintError("Schema mismatch: ${e.message}", tgtRef); return 3
        } catch (e: UnsupportedTriggerModeException) {
            userFacingPrintError("Trigger mode: ${e.message}", tgtRef); return 2
        } catch (e: Exception) {
            userFacingPrintError("Transfer error: ${e.message}", srcRef); return 5
        }

        if (!request.quiet && !request.noProgress)
            userFacingStderr("Transfer complete: ${tables.size} table(s) $srcRef -> $tgtRef")
        return 0
    }

    private fun validateFlags(r: DataTransferRequest): String? {
        if (!r.sinceColumn.isNullOrBlank() && r.since.isNullOrBlank()) return "--since-column requires --since"
        if (!r.since.isNullOrBlank() && r.sinceColumn.isNullOrBlank()) return "--since requires --since-column"
        if (!r.sinceColumn.isNullOrBlank() && DataExportHelpers.firstInvalidTableIdentifier(listOf(r.sinceColumn)) != null) {
            return "--since-column '${r.sinceColumn}' is not a valid identifier"
        }
        // No --filter validation needed: filter is already parsed into
        // ParsedFilter by the CLI layer before constructing DataTransferRequest.
        try { TriggerMode.valueOf(r.triggerMode.uppercase()) } catch (_: Exception) { return "Unknown --trigger-mode: ${r.triggerMode}" }
        try { OnConflict.valueOf(r.onConflict.uppercase()) } catch (_: Exception) { return "Unknown --on-conflict: ${r.onConflict}" }
        return null
    }

}
