package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import dev.dmigrate.format.report.TransformationReportWriter
import dev.dmigrate.format.yaml.YamlSchemaCodec
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Immutable DTO mit allen CLI-Eingaben für `d-migrate schema generate`.
 *
 * [SchemaGenerateCommand] baut aus seinen Clikt-Feldern einen Request und
 * delegiert an [SchemaGenerateRunner] — der Command bleibt ein reiner
 * Argument-Mapper, während die gesamte Verzweigungs-, Formatierungs- und
 * I/O-Koordinierung im Runner sitzt.
 */
internal data class SchemaGenerateRequest(
    val source: Path,
    val target: String,
    val output: Path?,
    val report: Path?,
    val generateRollback: Boolean,
    val ctx: CliContext,
)

/**
 * Kern-Logik für `d-migrate schema generate`. Alle externen Abhängigkeiten
 * (Schema-Codec, Validator, DDL-Generator-Lookup, Report-Writer, File-Writer,
 * Output-Formatter, stdout/stderr) sind konstruktor-injiziert und damit
 * testbar ohne Clikt-Kontext, ohne Dateisystem und ohne echten DDL-Generator.
 *
 * Exit-Code-Mapping (Plan §6.10):
 * - 0 Erfolg
 * - 2 ungültiger `--target`-Wert
 * - 3 Validation schlägt fehl
 * - 7 Schema-Datei kann nicht geparst werden
 */
internal class SchemaGenerateRunner(
    private val schemaReader: (Path) -> SchemaDefinition =
        { path -> YamlSchemaCodec().read(path) },
    private val validator: (SchemaDefinition) -> ValidationResult =
        { SchemaValidator().validate(it) },
    private val generatorLookup: (DatabaseDialect) -> DdlGenerator =
        SchemaGenerateHelpers::getGenerator,
    private val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path) -> Unit =
        { path, result, schema, dialect, source ->
            TransformationReportWriter().write(path, result, schema, dialect, source)
        },
    private val fileWriter: (Path, String) -> Unit =
        { path, content -> path.writeText(content) },
    private val formatterFactory: (CliContext) -> OutputFormatter = ::OutputFormatter,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    /**
     * Führt die DDL-Generierung durch und gibt den CLI-Exit-Code zurück.
     */
    fun execute(request: SchemaGenerateRequest): Int {
        val formatter = formatterFactory(request.ctx)

        // ─── 1. Parse dialect ───────────────────────────────────
        val dialect = try {
            DatabaseDialect.fromString(request.target)
        } catch (e: IllegalArgumentException) {
            formatter.printError(e.message ?: "Unknown dialect", request.source.toString())
            return 2
        }

        // ─── 2. Read schema ────────────────────────────────────
        val schema = try {
            schemaReader(request.source)
        } catch (e: Exception) {
            formatter.printError("Failed to parse schema file: ${e.message}", request.source.toString())
            return 7
        }

        // ─── 3. Validate ───────────────────────────────────────
        val validationResult = validator(schema)
        if (!validationResult.isValid) {
            formatter.printValidationResult(validationResult, schema, request.source.toString())
            return 3
        }

        // ─── 4. Generate DDL ──────────────────────────────────
        val generator = generatorLookup(dialect)
        val result = generator.generate(schema)

        // ─── 5. Print notes & skipped objects on stderr ───────
        printNotes(result, request.ctx.verbose)

        // ─── 6. Route output (json | file | stdout) ──────────
        val ddl = result.render()
        when {
            request.ctx.outputFormat == "json" -> {
                stdout(SchemaGenerateHelpers.formatJsonOutput(result, schema, dialect.name.lowercase()))
            }
            request.output != null -> {
                writeFileOutput(request, generator, schema, result, dialect, ddl)
            }
            else -> {
                writeStdoutOutput(request, generator, schema, result, dialect, ddl)
            }
        }

        return 0
    }

    private fun printNotes(result: DdlResult, verbose: Boolean) {
        for (note in result.notes) {
            when (note.type) {
                NoteType.WARNING ->
                    stderr("  ⚠ Warning [${note.code}]: ${note.message}")
                NoteType.ACTION_REQUIRED -> {
                    stderr("  ⚠ Action required [${note.code}]: ${note.message}")
                    if (note.hint != null) stderr("    → Hint: ${note.hint}")
                }
                NoteType.INFO ->
                    if (verbose) stderr("  ℹ Info [${note.code}]: ${note.message}")
            }
        }
        for (skip in result.skippedObjects) {
            stderr("  ⚠ Skipped ${skip.type} '${skip.name}': ${skip.reason}")
        }
    }

    private fun writeFileOutput(
        request: SchemaGenerateRequest,
        generator: DdlGenerator,
        schema: SchemaDefinition,
        result: DdlResult,
        dialect: DatabaseDialect,
        ddl: String,
    ) {
        val outputPath = request.output!!
        fileWriter(outputPath, ddl + "\n")
        if (!request.ctx.quiet) stderr("DDL written to $outputPath")

        if (request.generateRollback) {
            val rollbackResult = generator.generateRollback(schema)
            val rbPath = SchemaGenerateHelpers.rollbackPath(outputPath)
            fileWriter(rbPath, rollbackResult.render() + "\n")
            if (!request.ctx.quiet) stderr("Rollback DDL written to $rbPath")
        }

        writeReport(request, result, schema, dialect.name.lowercase(), outputPath)
    }

    private fun writeStdoutOutput(
        request: SchemaGenerateRequest,
        generator: DdlGenerator,
        schema: SchemaDefinition,
        result: DdlResult,
        dialect: DatabaseDialect,
        ddl: String,
    ) {
        stdout(ddl)
        if (request.generateRollback) {
            stdout("\n-- ═══════════════════════════════════════")
            stdout("-- ROLLBACK")
            stdout("-- ═══════════════════════════════════════\n")
            stdout(generator.generateRollback(schema).render())
        }

        // --report without --output: still write the sidecar report
        if (request.report != null) {
            writeReport(request, result, schema, dialect.name.lowercase(), request.report)
        }
    }

    private fun writeReport(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        outputPath: Path,
    ) {
        val reportPath = request.report ?: SchemaGenerateHelpers.sidecarPath(outputPath, ".report.yaml")
        reportWriter(reportPath, result, schema, dialect, request.source)
        if (!request.ctx.quiet) stderr("Report written to $reportPath")
    }
}
