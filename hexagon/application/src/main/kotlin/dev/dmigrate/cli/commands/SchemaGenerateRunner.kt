package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SpatialProfilePolicy
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Immutable DTO with all inputs for `d-migrate schema generate`.
 */
data class SchemaGenerateRequest(
    val source: Path,
    val target: String,
    val spatialProfile: String? = null,
    val output: Path?,
    val report: Path?,
    val generateRollback: Boolean,
    val outputFormat: String,
    val verbose: Boolean,
    val quiet: Boolean,
)

/**
 * Core logic for `d-migrate schema generate`. All external collaborators
 * are constructor-injected so every branch is unit-testable without a
 * CLI framework, filesystem, or real DDL generator.
 *
 * Exit codes (Plan §6.10):
 * - 0 success
 * - 2 invalid --target
 * - 3 validation failure
 * - 7 schema file parse error
 */
class SchemaGenerateRunner(
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult =
        { SchemaValidator().validate(it) },
    private val generatorLookup: (DatabaseDialect) -> DdlGenerator,
    private val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path) -> Unit,
    private val fileWriter: (Path, String) -> Unit =
        { path, content -> path.writeText(content) },
    private val formatJsonOutput: (DdlResult, SchemaDefinition, String) -> String,
    private val sidecarPath: (Path, String) -> Path,
    private val rollbackPath: (Path) -> Path,
    private val printError: (message: String, source: String) -> Unit,
    private val printValidationResult: (ValidationResult, SchemaDefinition, String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: SchemaGenerateRequest): Int {
        // ─── 1. Parse dialect ───────────────────────────────────
        val dialect = try {
            DatabaseDialect.fromString(request.target)
        } catch (e: IllegalArgumentException) {
            printError(e.message ?: "Unknown dialect", request.source.toString())
            return 2
        }

        // ─── 2. Resolve spatial profile (before schema read) ──
        val profileResult = SpatialProfilePolicy.resolve(dialect, request.spatialProfile)
        val spatialProfile = when (profileResult) {
            is SpatialProfilePolicy.Result.Resolved -> profileResult.profile
            is SpatialProfilePolicy.Result.UnknownProfile -> {
                printError("Unknown spatial profile '${profileResult.raw}'. " +
                    "Allowed: ${SpatialProfilePolicy.allowedFor(dialect).joinToString { it.cliName }}",
                    request.source.toString())
                return 2
            }
            is SpatialProfilePolicy.Result.NotAllowedForDialect -> {
                printError("Spatial profile '${profileResult.profile.cliName}' is not allowed for ${profileResult.dialect.name.lowercase()}. " +
                    "Allowed: ${SpatialProfilePolicy.allowedFor(dialect).joinToString { it.cliName }}",
                    request.source.toString())
                return 2
            }
        }
        val options = DdlGenerationOptions(spatialProfile = spatialProfile)

        // ─── 3. Read schema ────────────────────────────────────
        val schema = try {
            schemaReader(request.source)
        } catch (e: Exception) {
            printError("Failed to parse schema file: ${e.message}", request.source.toString())
            return 7
        }

        // ─── 4. Validate ───────────────────────────────────────
        val validationResult = validator(schema)
        if (!validationResult.isValid) {
            printValidationResult(validationResult, schema, request.source.toString())
            return 3
        }

        // ─── 5. Generate DDL ──────────────────────────────────
        val generator = generatorLookup(dialect)
        val result = generator.generate(schema, options)

        // ─── 5. Print notes & skipped objects on stderr ───────
        printNotes(result, request.verbose)

        // ─── 6. Route output (json | file | stdout) ──────────
        val ddl = result.render()
        when {
            request.outputFormat == "json" -> {
                stdout(formatJsonOutput(result, schema, dialect.name.lowercase()))
            }
            request.output != null -> {
                writeFileOutput(request, generator, schema, result, dialect, ddl, options)
            }
            else -> {
                writeStdoutOutput(request, generator, schema, result, dialect, ddl, options)
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
        options: DdlGenerationOptions,
    ) {
        val outputPath = request.output!!
        fileWriter(outputPath, ddl + "\n")
        if (!request.quiet) stderr("DDL written to $outputPath")

        if (request.generateRollback) {
            val rollbackResult = generator.generateRollback(schema, options)
            val rbPath = rollbackPath(outputPath)
            fileWriter(rbPath, rollbackResult.render() + "\n")
            if (!request.quiet) stderr("Rollback DDL written to $rbPath")
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
        options: DdlGenerationOptions,
    ) {
        stdout(ddl)
        if (request.generateRollback) {
            stdout("\n-- ═══════════════════════════════════════")
            stdout("-- ROLLBACK")
            stdout("-- ═══════════════════════════════════════\n")
            stdout(generator.generateRollback(schema, options).render())
        }

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
        val reportPath = request.report ?: sidecarPath(outputPath, ".report.yaml")
        reportWriter(reportPath, result, schema, dialect, request.source)
        if (!request.quiet) stderr("Report written to $reportPath")
    }
}
