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

    fun execute(request: SchemaReverseRequest): Int {
        // 1. Pre-validate format/extension
        try {
            formatValidator(request.output, request.format)
        } catch (e: IllegalArgumentException) {
            printError(e.message ?: "Invalid format/extension", request.source)
            return 2
        }

        // 2. Report path
        val reportPath = request.report ?: sidecarPath(request.output, ".report.yaml")

        // 3. Output/report collision check
        val normalizedOutput = request.output.toAbsolutePath().normalize()
        val normalizedReport = reportPath.toAbsolutePath().normalize()
        if (normalizedOutput == normalizedReport) {
            printError("Output and report paths must not be the same", request.source)
            return 2
        }

        // 4. Source resolution
        val resolvedUrl: String
        try {
            resolvedUrl = sourceResolver(request.source, request.cliConfigPath)
        } catch (e: Exception) {
            printError("Failed to resolve source: ${scrubMessage(e.message)}", request.source)
            return 7
        }

        // 5. Build user-facing source reference (scrubbed)
        val isAlias = !request.source.contains("://")
        val userFacingSource = if (isAlias) request.source else urlScrubber(resolvedUrl)
        val sourceRef = ReverseSourceRef(
            kind = if (isAlias) ReverseSourceKind.ALIAS else ReverseSourceKind.URL,
            value = userFacingSource,
        )

        // 6. Parse URL and determine dialect
        val config: dev.dmigrate.driver.connection.ConnectionConfig
        try {
            config = urlParser(resolvedUrl)
        } catch (e: Exception) {
            printError("Failed to parse connection URL: ${scrubMessage(e.message)}", userFacingSource)
            return 7
        }

        // 7. Create pool and read schema
        val result: SchemaReadResult
        try {
            val pool = poolFactory(config)
            pool.use { p ->
                val options = SchemaReadOptions(
                    includeViews = request.includeAll || request.includeViews,
                    includeProcedures = request.includeAll || request.includeProcedures,
                    includeFunctions = request.includeAll || request.includeFunctions,
                    includeTriggers = request.includeAll || request.includeTriggers,
                )
                val reader = driverLookup(config.dialect).schemaReader()
                result = reader.read(p, options)
            }
        } catch (e: Exception) {
            printError("Connection or metadata error: ${scrubMessage(e.message)}", userFacingSource)
            return 4
        }

        // 8. Write schema file
        try {
            request.output.parent?.toFile()?.mkdirs()
            schemaWriter(request.output, result.schema, request.format)
        } catch (e: Exception) {
            printError("Failed to write schema: ${e.message}", userFacingSource)
            return 7
        }

        // 9. Write report
        try {
            reportPath.parent?.toFile()?.mkdirs()
            val reportInput = SchemaReadReportInput(source = sourceRef, result = result)
            reportWriter(reportPath, reportInput)
        } catch (e: Exception) {
            printError("Failed to write report: ${e.message}", userFacingSource)
            return 7
        }

        // 10. Output
        val warnings = result.notes.count { it.severity == SchemaReadSeverity.WARNING }
        val actionRequired = result.notes.count { it.severity == SchemaReadSeverity.ACTION_REQUIRED }

        when (request.outputFormat) {
            "json", "yaml" -> {
                if (!request.quiet) {
                    val doc = SchemaReverseDocument(
                        status = "success",
                        exitCode = 0,
                        source = userFacingSource,
                        output = request.output.toString(),
                        report = reportPath.toString(),
                        notesCount = result.notes.size,
                        warningsCount = warnings,
                        actionRequiredCount = actionRequired,
                        skippedObjectsCount = result.skippedObjects.size,
                    )
                    val rendered = if (request.outputFormat == "json") renderJson(doc) else renderYaml(doc)
                    stdout(rendered)
                }
                // No stderr notes in json/yaml mode for successful runs
            }
            else -> {
                // Plain mode
                if (!request.quiet) {
                    stdout("Schema written to ${request.output}")
                    stdout("Report written to $reportPath")
                }

                // stderr: notes and skipped objects
                if (!request.quiet) {
                    for (note in result.notes) {
                        if (note.severity == SchemaReadSeverity.INFO && !request.verbose) continue
                        stderr("  ${note.severity.name} [${note.code}] ${note.objectName}: ${note.message}")
                    }
                    for (skip in result.skippedObjects) {
                        stderr("  SKIPPED [${skip.code ?: "-"}] ${skip.type} ${skip.name}: ${skip.reason}")
                    }
                }
            }
        }

        return 0
    }

    private fun scrubMessage(message: String?): String {
        if (message == null) return "unknown error"
        // Scrub any URLs that might be in exception messages
        return Regex("[a-zA-Z][a-zA-Z0-9+\\-.]*://[^\\s]+").replace(message) {
            urlScrubber(it.value)
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
