package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
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
    val tables: List<String>? = null, val filter: String? = null,
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
    fun execute(request: DataTransferRequest): Int {
        // Scrub raw operand strings for all user-facing output
        val safeSrc = scrubRef(request.source)
        val safeTgt = scrubRef(request.target)

        val err = validateFlags(request)
        if (err != null) { printError(err, safeSrc); return 2 }

        val srcUrl: String; val tgtUrl: String; val srcRef: String; val tgtRef: String
        try {
            srcUrl = sourceResolver(request.source, request.cliConfigPath)
            srcRef = if (request.source.contains("://")) urlScrubber(srcUrl) else request.source
        } catch (e: Exception) { printError("Source config: ${scrub(e.message)}", safeSrc); return 7 }
        try {
            tgtUrl = targetResolver(request.target, request.cliConfigPath)
            tgtRef = if (request.target.contains("://")) urlScrubber(tgtUrl) else request.target
        } catch (e: Exception) { printError("Target config: ${scrub(e.message)}", safeTgt); return 7 }

        val srcCfg: ConnectionConfig; val tgtCfg: ConnectionConfig
        try { srcCfg = urlParser(srcUrl); tgtCfg = urlParser(tgtUrl) }
        catch (e: Exception) { printError("URL parse: ${scrub(e.message)}", srcRef); return 7 }

        val srcPool: ConnectionPool
        try { srcPool = poolFactory(srcCfg) }
        catch (e: Exception) { printError("Source connection: ${scrub(e.message)}", srcRef); return 4 }
        val tgtPool: ConnectionPool
        try { tgtPool = poolFactory(tgtCfg) }
        catch (e: Exception) { srcPool.close(); printError("Target connection: ${scrub(e.message)}", tgtRef); return 4 }

        try {
            val srcDrv = driverLookup(srcCfg.dialect); val tgtDrv = driverLookup(tgtCfg.dialect)
            val readOpts = SchemaReadOptions(includeViews = false, includeProcedures = false,
                includeFunctions = false, includeTriggers = false)
            val srcSchema: SchemaDefinition; val tgtSchema: SchemaDefinition
            try {
                srcSchema = srcDrv.schemaReader().read(srcPool, readOpts).schema
                tgtSchema = tgtDrv.schemaReader().read(tgtPool, readOpts).schema
            } catch (e: Exception) { printError("Schema read: ${scrub(e.message)}", srcRef); return 4 }

            val tables: List<String>
            try { tables = preflight(request, srcSchema, tgtSchema) }
            catch (e: TransferPreflightException) { printError("Preflight: ${e.message}", srcRef); return 3 }

            val opts = ImportOptions(triggerMode = TriggerMode.valueOf(request.triggerMode.uppercase()),
                truncate = request.truncate, onConflict = OnConflict.valueOf(request.onConflict.uppercase()))
            val filter = DataExportHelpers.resolveFilter(
                rawFilter = request.filter,
                dialect = srcCfg.dialect,
                sinceColumn = request.sinceColumn,
                since = request.since,
            )
            val reader = srcDrv.dataReader(); val writer = tgtDrv.dataWriter()

            try {
                for (table in tables) {
                    reader.streamTable(srcPool, table, filter, request.chunkSize).use { seq ->
                        writer.openTable(tgtPool, table, opts).use { s ->
                            // Build index mapping: source col position → target col position
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
                                val tgtDescriptors = s.targetColumns.map { ColumnDescriptor(it.name, it.nullable, it.sqlTypeName) }
                                val normalized = DataChunk(table, tgtDescriptors, reordered, chunkIdx++)
                                s.write(normalized)
                                s.commitChunk()
                            }
                            s.finishTable()
                        }
                    }
                    if (!request.quiet && !request.noProgress) stderr("  Transferred: $table")
                }
            } catch (e: ImportSchemaMismatchException) {
                printError("Schema mismatch: ${e.message}", tgtRef); return 3
            } catch (e: UnsupportedTriggerModeException) {
                printError("Trigger mode: ${e.message}", tgtRef); return 2
            } catch (e: Exception) {
                printError("Transfer error: ${scrub(e.message)}", srcRef); return 5
            }

            if (!request.quiet && !request.noProgress)
                stderr("Transfer complete: ${tables.size} table(s) $srcRef -> $tgtRef")
            return 0
        } finally { tgtPool.close(); srcPool.close() }
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
        val a = s.type; val b = t.type
        if (a is NeutralType.Integer && b is NeutralType.BigInteger) return true
        if (a is NeutralType.SmallInt && (b is NeutralType.Integer || b is NeutralType.BigInteger)) return true
        if (a is NeutralType.Identifier && (b is NeutralType.Integer || b is NeutralType.BigInteger || b is NeutralType.Identifier)) return true
        if (a is NeutralType.Integer && b is NeutralType.Identifier) return true
        if (a is NeutralType.Text && b is NeutralType.Text) return true
        if (a is NeutralType.Char && b is NeutralType.Text) return true
        return false
    }

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
        if (r.sinceColumn != null && r.since == null) return "--since-column requires --since"
        if (r.since != null && r.sinceColumn == null) return "--since requires --since-column"
        if (r.sinceColumn != null && DataExportHelpers.firstInvalidTableIdentifier(listOf(r.sinceColumn)) != null) {
            return "--since-column '${r.sinceColumn}' is not a valid identifier"
        }
        if (r.filter != null && r.since != null && DataExportHelpers.containsLiteralQuestionMark(r.filter)) {
            return "--filter '?' forbidden with --since (M-R5)"
        }
        try { TriggerMode.valueOf(r.triggerMode.uppercase()) } catch (_: Exception) { return "Unknown --trigger-mode: ${r.triggerMode}" }
        try { OnConflict.valueOf(r.onConflict.uppercase()) } catch (_: Exception) { return "Unknown --on-conflict: ${r.onConflict}" }
        return null
    }

    // buildFilter consolidated into DataExportHelpers.resolveFilter (Qualität Maßnahme 1)

    private fun scrub(m: String?) = if (m == null) "unknown" else
        Regex("[a-zA-Z][a-zA-Z0-9+\\-.]*://[^\\s]+").replace(m) { urlScrubber(it.value) }

    private fun scrubRef(raw: String) =
        if (raw.contains("://")) urlScrubber(raw) else raw
}
