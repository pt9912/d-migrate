package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
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
    private val sourceResolver: (String, Path?) -> String,
    private val targetResolver: (String, Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val driverLookup: (DatabaseDialect) -> DatabaseDriver,
    private val urlScrubber: (String) -> String = { it },
    private val printError: (String, String) -> Unit,
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    private data class TransferTableContext(
        val reader: dev.dmigrate.driver.data.DataReader,
        val writer: dev.dmigrate.driver.data.DataWriter,
        val srcPool: ConnectionPool,
        val tgtPool: ConnectionPool,
        val table: String,
        val filter: DataFilter?,
        val chunkSize: Int,
        val opts: ImportOptions,
    )

    fun execute(request: DataTransferRequest): Int {
        val safeSrc = userFacingErrors.scrubRef(request.source)
        val safeTgt = userFacingErrors.scrubRef(request.target)

        val err = validateFlags(request)
        if (err != null) { userFacingPrintError(err, safeSrc); return 2 }

        val srcUrl: String; val tgtUrl: String; val srcRef: String; val tgtRef: String
        try {
            srcUrl = sourceResolver(request.source, request.cliConfigPath)
            srcRef = if (request.source.contains("://")) urlScrubber(srcUrl) else request.source
        } catch (e: Exception) { userFacingPrintError("Source config: ${e.message}", safeSrc); return 7 }
        try {
            tgtUrl = targetResolver(request.target, request.cliConfigPath)
            tgtRef = if (request.target.contains("://")) urlScrubber(tgtUrl) else request.target
        } catch (e: Exception) { userFacingPrintError("Target config: ${e.message}", safeTgt); return 7 }

        val srcCfg: ConnectionConfig; val tgtCfg: ConnectionConfig
        try { srcCfg = urlParser(srcUrl); tgtCfg = urlParser(tgtUrl) }
        catch (e: Exception) { userFacingPrintError("URL parse: ${e.message}", srcRef); return 7 }

        val srcPool: ConnectionPool
        try { srcPool = poolFactory(srcCfg) }
        catch (e: Exception) { userFacingPrintError("Source connection: ${e.message}", srcRef); return 4 }
        val tgtPool: ConnectionPool
        try { tgtPool = poolFactory(tgtCfg) }
        catch (e: Exception) { srcPool.close(); userFacingPrintError("Target connection: ${e.message}", tgtRef); return 4 }

        try {
            return executeWithPools(request, srcCfg, tgtCfg, srcPool, tgtPool, srcRef, tgtRef)
        } finally { tgtPool.close(); srcPool.close() }
    }

    private fun executeWithPools(
        request: DataTransferRequest, srcCfg: ConnectionConfig, tgtCfg: ConnectionConfig,
        srcPool: ConnectionPool, tgtPool: ConnectionPool, srcRef: String, tgtRef: String,
    ): Int {
        val srcDrv = driverLookup(srcCfg.dialect); val tgtDrv = driverLookup(tgtCfg.dialect)
        val readOpts = SchemaReadOptions(includeViews = false, includeProcedures = false,
            includeFunctions = false, includeTriggers = false)
        val srcSchema: SchemaDefinition; val tgtSchema: SchemaDefinition
        try {
            srcSchema = srcDrv.schemaReader().read(srcPool, readOpts).schema
            tgtSchema = tgtDrv.schemaReader().read(tgtPool, readOpts).schema
        } catch (e: Exception) { userFacingPrintError("Schema read: ${e.message}", srcRef); return 4 }

        val tables: List<String>
        try { tables = preflight(request, srcSchema, tgtSchema) }
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
            for (table in tables) {
                transferTable(
                    TransferTableContext(
                        reader = reader,
                        writer = writer,
                        srcPool = srcPool,
                        tgtPool = tgtPool,
                        table = table,
                        filter = filter,
                        chunkSize = request.chunkSize,
                        opts = opts,
                    )
                )
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

    private fun transferTable(ctx: TransferTableContext) {
        ctx.reader.streamTable(
            ctx.srcPool,
            ctx.table,
            ctx.filter,
            ctx.chunkSize,
        ).use { seq ->
            ctx.writer.openTable(ctx.tgtPool, ctx.table, ctx.opts).use { s ->
                val tgtNames = s.targetColumns.map { it.name }
                var chunkIdx = 0L
                for (chunk in seq) {
                    val srcNames = chunk.columns.map { it.name }
                    val srcIdx = tgtNames.map { tgt -> srcNames.indexOf(tgt) }
                    val reordered = chunk.rows.map { row ->
                        Array(tgtNames.size) { i ->
                            val si = srcIdx[i]
                            if (si >= 0) row[si] else null
                        }
                    }
                    val tgtDescriptors = s.targetColumns.map {
                        ColumnDescriptor(it.name, it.nullable, it.sqlTypeName)
                    }
                    val normalized = DataChunk(ctx.table, tgtDescriptors, reordered, chunkIdx++)
                    s.write(normalized)
                    s.commitChunk()
                }
                s.finishTable()
            }
        }
    }

    private fun preflight(r: DataTransferRequest, src: SchemaDefinition, tgt: SchemaDefinition): List<String> {
        val cands = if (r.tables != null) {
            for (t in r.tables) if (t !in src.tables) throw TransferPreflightException("Source table '$t' not found")
            r.tables
        } else src.tables.keys.toList()
        for (t in cands) {
            if (t !in tgt.tables) throw TransferPreflightException("Target table '$t' not found")
            for ((c, sd) in src.tables[t]!!.columns) {
                val td = tgt.tables[t]!!.columns[c] ?: throw TransferPreflightException("Column '$t.$c' missing in target")
                if (!compat(sd, td)) throw TransferPreflightException("Column '$t.$c' type mismatch: ${sd.type} vs ${td.type}")
            }
        }
        if (r.onConflict.equals("update", true))
            for (t in cands) if (tgt.tables[t]!!.primaryKey.isEmpty())
                throw TransferPreflightException("Table '$t' needs PK for --on-conflict update")
        return topoSort(cands, tgt)
    }

    private fun compat(s: ColumnDefinition, t: ColumnDefinition): Boolean {
        if (s.type == t.type) return true
        val a = s.type
        val b = t.type
        if (a is NeutralType.Integer && b is NeutralType.BigInteger) return true
        if (a is NeutralType.SmallInt && isIntegralTargetType(b)) return true
        if (isIdentifierCompatible(a, b)) return true
        if (a is NeutralType.Integer && b is NeutralType.Identifier) return true
        if (a is NeutralType.Text && b is NeutralType.Text) return true
        if (a is NeutralType.Char && b is NeutralType.Text) return true
        return false
    }

    private fun isIdentifierCompatible(source: NeutralType, target: NeutralType): Boolean {
        if (source !is NeutralType.Identifier) return false
        return isIntegralTargetType(target) || target is NeutralType.Identifier
    }

    private fun isIntegralTargetType(target: NeutralType): Boolean =
        target is NeutralType.Integer || target is NeutralType.BigInteger

    private fun topoSort(tables: List<String>, schema: SchemaDefinition): List<String> {
        val tableSet = tables.toSet()
        val edges = tables.flatMap { t ->
            val refs = mutableListOf<dev.dmigrate.core.dependency.FkEdge>()
            schema.tables[t]?.columns?.values?.forEach { c ->
                c.references?.let { refs += dev.dmigrate.core.dependency.FkEdge(t, toTable = it.table) }
            }
            schema.tables[t]?.constraints?.forEach { c ->
                c.references?.let { refs += dev.dmigrate.core.dependency.FkEdge(t, toTable = it.table) }
            }
            refs
        }
        val result = dev.dmigrate.core.dependency.sortTablesByDependency(tableSet, edges)
        if (result.circularEdges.isNotEmpty()) {
            val cyclic = result.circularEdges.map { it.fromTable }.toSet()
            throw TransferPreflightException("FK cycle: ${cyclic.joinToString()}")
        }
        return result.sorted
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
