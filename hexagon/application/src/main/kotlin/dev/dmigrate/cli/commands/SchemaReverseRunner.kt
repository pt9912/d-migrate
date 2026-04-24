package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionPool
import java.nio.file.Path

/**
 * Immutable DTO with all inputs for `d-migrate schema reverse`.
 */
data class SchemaReverseRequest(
    val source: String,
    val output: Path,
    val format: String = "yaml",
    val report: Path? = null,
    val includeViews: Boolean = false,
    val includeProcedures: Boolean = false,
    val includeFunctions: Boolean = false,
    val includeTriggers: Boolean = false,
    val includeAll: Boolean = false,
    val cliConfigPath: Path? = null,
    val outputFormat: String = "plain",
    val quiet: Boolean = false,
    val verbose: Boolean = false,
)

/**
 * Structured success document for `--output-format json|yaml`.
 */
data class SchemaReverseDocument(
    val command: String = "schema.reverse",
    val status: String,
    val exitCode: Int,
    val source: String,
    val output: String,
    val report: String,
    val notesCount: Int = 0,
    val warningsCount: Int = 0,
    val actionRequiredCount: Int = 0,
    val skippedObjectsCount: Int = 0,
)

/**
 * Core logic for `d-migrate schema reverse`. All external collaborators
 * are constructor-injected so every branch is unit-testable without a
 * CLI framework, filesystem, or real database.
 *
 * Exit codes:
 * - 0: success
 * - 2: invalid CLI arguments (format/extension mismatch, output/report collision)
 * - 4: connection or DB metadata error
 * - 7: config resolution, URL parse, or file write error
 */
class SchemaReverseRunner(
    private val sourceResolver: (String, Path?) -> String,
    private val urlParser: (String) -> dev.dmigrate.driver.connection.ConnectionConfig,
    private val poolFactory: (dev.dmigrate.driver.connection.ConnectionConfig) -> ConnectionPool,
    private val driverLookup: (DatabaseDialect) -> DatabaseDriver,
    private val schemaWriter: (Path, SchemaDefinition, String?) -> Unit,
    private val reportWriter: (Path, SchemaReadReportInput) -> Unit,
    private val sidecarPath: (Path, String) -> Path,
    private val formatValidator: (Path, String?) -> Unit,
    private val urlScrubber: (String) -> String = { it },
    private val printError: (message: String, source: String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    private val renderJson: (SchemaReverseDocument) -> String = { doc -> renderJsonDefault(doc) },
    private val renderYaml: (SchemaReverseDocument) -> String = { doc -> renderYamlDefault(doc) },
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    private data class ResolvedContext(
        val reportPath: Path,
        val userFacingSource: String,
        val sourceRef: ReverseSourceRef,
        val config: dev.dmigrate.driver.connection.ConnectionConfig,
    )

    fun execute(request: SchemaReverseRequest): Int {
        val ctx = when (val r = validateAndResolve(request)) {
            is ResolvedContext -> r
            else -> return r as Int
        }

        val result = readSchema(request, ctx) ?: return 4
        writeSchemaFile(request, result, ctx.userFacingSource)?.let { return it }
        writeReportFile(ctx, result)?.let { return it }
        printOutput(request, result, ctx.userFacingSource, ctx.reportPath)
        return 0
    }

    private fun validateAndResolve(request: SchemaReverseRequest): Any {
        try {
            formatValidator(request.output, request.format)
        } catch (e: IllegalArgumentException) {
            userFacingPrintError(e.message ?: "Invalid format/extension", request.source)
            return 2
        }

        val reportPath = request.report ?: sidecarPath(request.output, ".report.yaml")
        if (request.output.toAbsolutePath().normalize() == reportPath.toAbsolutePath().normalize()) {
            userFacingPrintError("Output and report paths must not be the same", request.source)
            return 2
        }

        val resolvedUrl = try {
            sourceResolver(request.source, request.cliConfigPath)
        } catch (e: Exception) {
            userFacingPrintError("Failed to resolve source: ${e.message}", request.source)
            return 7
        }

        val isAlias = !request.source.contains("://")
        val userFacingSource = if (isAlias) request.source else urlScrubber(resolvedUrl)
        val sourceRef = ReverseSourceRef(
            kind = if (isAlias) ReverseSourceKind.ALIAS else ReverseSourceKind.URL,
            value = userFacingSource,
        )

        val config = try {
            urlParser(resolvedUrl)
        } catch (e: Exception) {
            userFacingPrintError("Failed to parse connection URL: ${e.message}", userFacingSource)
            return 7
        }

        return ResolvedContext(reportPath, userFacingSource, sourceRef, config)
    }

    private fun readSchema(request: SchemaReverseRequest, ctx: ResolvedContext): SchemaReadResult? {
        return try {
            val pool = poolFactory(ctx.config)
            pool.use { p ->
                val options = SchemaReadOptions(
                    includeViews = request.includeAll || request.includeViews,
                    includeProcedures = request.includeAll || request.includeProcedures,
                    includeFunctions = request.includeAll || request.includeFunctions,
                    includeTriggers = request.includeAll || request.includeTriggers,
                )
                val reader = driverLookup(ctx.config.dialect).schemaReader()
                reader.read(p, options)
            }
        } catch (e: Exception) {
            userFacingPrintError("Connection or metadata error: ${e.message}", ctx.userFacingSource)
            null
        }
    }

    private fun writeSchemaFile(request: SchemaReverseRequest, result: SchemaReadResult, userFacingSource: String): Int? {
        return try {
            request.output.parent?.toFile()?.mkdirs()
            schemaWriter(request.output, result.schema, request.format)
            null
        } catch (e: Exception) {
            userFacingPrintError("Failed to write schema: ${e.message}", userFacingSource)
            7
        }
    }

    private fun writeReportFile(ctx: ResolvedContext, result: SchemaReadResult): Int? {
        return try {
            ctx.reportPath.parent?.toFile()?.mkdirs()
            val reportInput = SchemaReadReportInput(source = ctx.sourceRef, result = result)
            reportWriter(ctx.reportPath, reportInput)
            null
        } catch (e: Exception) {
            userFacingPrintError("Failed to write report: ${e.message}", ctx.userFacingSource)
            7
        }
    }

    private fun printOutput(request: SchemaReverseRequest, result: SchemaReadResult, userFacingSource: String, reportPath: Path) {
        when (request.outputFormat) {
            "json", "yaml" -> printStructuredOutput(request, result, userFacingSource, reportPath)
            else -> printPlainOutput(request, result, reportPath)
        }
    }

    private fun printStructuredOutput(request: SchemaReverseRequest, result: SchemaReadResult, userFacingSource: String, reportPath: Path) {
        if (request.quiet) return
        val doc = SchemaReverseDocument(
            status = "success", exitCode = 0, source = userFacingSource,
            output = request.output.toString(), report = reportPath.toString(),
            notesCount = result.notes.size,
            warningsCount = result.notes.count { it.severity == SchemaReadSeverity.WARNING },
            actionRequiredCount = result.notes.count { it.severity == SchemaReadSeverity.ACTION_REQUIRED },
            skippedObjectsCount = result.skippedObjects.size,
        )
        stdout(if (request.outputFormat == "json") renderJson(doc) else renderYaml(doc))
    }

    private fun printPlainOutput(request: SchemaReverseRequest, result: SchemaReadResult, reportPath: Path) {
        if (request.quiet) return
        stdout("Schema written to ${request.output}")
        stdout("Report written to $reportPath")
        for (note in result.notes) {
            if (note.severity == SchemaReadSeverity.INFO && !request.verbose) continue
            userFacingStderr("  ${note.severity.name} [${note.code}] ${note.objectName}: ${note.message}")
        }
        for (skip in result.skippedObjects) {
            userFacingStderr("  SKIPPED [${skip.code ?: "-"}] ${skip.type} ${skip.name}: ${skip.reason}")
        }
    }

    companion object {
        private fun esc(s: String) = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        fun renderJsonDefault(doc: SchemaReverseDocument): String = buildString {
            appendLine("{")
            appendLine("""  "command": "${doc.command}",""")
            appendLine("""  "status": "${doc.status}",""")
            appendLine("""  "exit_code": ${doc.exitCode},""")
            appendLine("""  "source": "${esc(doc.source)}",""")
            appendLine("""  "output": "${esc(doc.output)}",""")
            appendLine("""  "report": "${esc(doc.report)}",""")
            appendLine("""  "summary": {""")
            appendLine("""    "notes": ${doc.notesCount},""")
            appendLine("""    "warnings": ${doc.warningsCount},""")
            appendLine("""    "action_required": ${doc.actionRequiredCount},""")
            appendLine("""    "skipped_objects": ${doc.skippedObjectsCount}""")
            appendLine("  }")
            append("}")
        }

        fun renderYamlDefault(doc: SchemaReverseDocument): String = buildString {
            appendLine("command: ${doc.command}")
            appendLine("status: ${doc.status}")
            appendLine("exit_code: ${doc.exitCode}")
            appendLine("source: \"${esc(doc.source)}\"")
            appendLine("output: \"${esc(doc.output)}\"")
            appendLine("report: \"${esc(doc.report)}\"")
            appendLine("summary:")
            appendLine("  notes: ${doc.notesCount}")
            appendLine("  warnings: ${doc.warningsCount}")
            appendLine("  action_required: ${doc.actionRequiredCount}")
            append("  skipped_objects: ${doc.skippedObjectsCount}")
        }
    }
}
