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

/** DDL output split mode for `schema generate` (0.9.2). */
enum class SplitMode {
    /** Single combined DDL output (default, backward compatible). */
    SINGLE,
    /** Split into pre-data and post-data artifacts. */
    PRE_POST,
}

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
    val splitMode: SplitMode = SplitMode.SINGLE,
)

/**
 * Core logic for `d-migrate schema generate`. All external collaborators
 * are constructor-injected so every branch is unit-testable without a
 * CLI framework, filesystem, or real DDL generator.
 *
 * Exit codes (Plan §6.10):
 * - 0 success
 * - 2 invalid --target, invalid spatial profile, or invalid --split combination
 * - 3 validation failure
 * - 7 schema file parse error
 */
class SchemaGenerateRunner(
    private val schemaReader: (Path) -> SchemaDefinition,
    private val validator: (SchemaDefinition) -> ValidationResult =
        { SchemaValidator().validate(it) },
    private val generatorLookup: (DatabaseDialect) -> DdlGenerator,
    private val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path, String?) -> Unit,
    private val fileWriter: (Path, String) -> Unit =
        { path, content -> path.writeText(content) },
    private val formatJsonOutput: (DdlResult, SchemaDefinition, String, SplitMode) -> String,
    private val sidecarPath: (Path, String) -> Path,
    private val rollbackPath: (Path) -> Path,
    private val splitPath: (Path, dev.dmigrate.driver.DdlPhase) -> Path,
    private val printError: (message: String, source: String) -> Unit,
    private val printValidationResult: (ValidationResult, SchemaDefinition, String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    fun execute(request: SchemaGenerateRequest): Int {
        // ─── 0. Split-mode preflight ────────────────────────────
        if (request.splitMode == SplitMode.PRE_POST) {
            if (request.generateRollback) {
                stderr("`--split pre-post` cannot be combined with `--generate-rollback`.")
                return 2
            }
            if (request.output == null && request.outputFormat != "json") {
                stderr("`--split pre-post` requires `--output` unless `--output-format json` is used.")
                return 2
            }
        }

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

        // ─── 5b. Check for unresolvable split diagnostics ────
        if (request.splitMode == SplitMode.PRE_POST) {
            val splitDiags = result.globalNotes.filter { it.code == "E060" }
            if (splitDiags.isNotEmpty()) {
                for (d in splitDiags) {
                    stderr("  \u2717 Split error [${d.code}]: ${d.message}")
                    if (d.hint != null) stderr("    \u2192 Hint: ${d.hint}")
                }
                return 2
            }
        }

        // ─── 5c. Print notes & skipped objects on stderr ─────
        printNotes(result, request.verbose)

        // ─── 6. Route output (json | file | stdout) ──────────
        val dialectName = dialect.name.lowercase()
        val splitModeStr = if (request.splitMode == SplitMode.PRE_POST) "pre-post" else null

        if (request.splitMode == SplitMode.PRE_POST) {
            // Split output: file and/or json
            if (request.output != null) {
                writeSplitFileOutput(request, result, schema, dialectName, splitModeStr)
            }
            if (request.outputFormat == "json") {
                stdout(formatJsonOutput(result, schema, dialectName, request.splitMode))
            }
            if (request.output == null && request.outputFormat != "json") {
                // Should not reach here — preflight catches this
                return 2
            }
        } else {
            // Single output: json | file | stdout (unchanged)
            val ddl = result.render()
            when {
                request.outputFormat == "json" -> {
                    stdout(formatJsonOutput(result, schema, dialectName, request.splitMode))
                }
                request.output != null -> {
                    writeFileOutput(request, generator, schema, result, dialect, ddl, options, splitModeStr)
                }
                else -> {
                    writeStdoutOutput(request, generator, schema, result, dialect, ddl, options)
                }
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
            val codePrefix = if (skip.code != null) " [${skip.code}]" else ""
            stderr("  ⚠ Skipped$codePrefix ${skip.type} '${skip.name}': ${skip.reason}")
            if (skip.hint != null) stderr("    → Hint: ${skip.hint}")
        }
    }

    private fun writeSplitFileOutput(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        splitModeStr: String?,
    ) {
        val outputPath = request.output!!
        val prePath = splitPath(outputPath, dev.dmigrate.driver.DdlPhase.PRE_DATA)
        val postPath = splitPath(outputPath, dev.dmigrate.driver.DdlPhase.POST_DATA)
        val preDdl = result.renderPhase(dev.dmigrate.driver.DdlPhase.PRE_DATA)
        val postDdl = result.renderPhase(dev.dmigrate.driver.DdlPhase.POST_DATA)
        fileWriter(prePath, preDdl + "\n")
        if (!request.quiet) stderr("Pre-data DDL written to $prePath")
        fileWriter(postPath, postDdl + "\n")
        if (!request.quiet) stderr("Post-data DDL written to $postPath")

        writeReport(request, result, schema, dialect, outputPath, splitModeStr)
    }

    private fun writeFileOutput(
        request: SchemaGenerateRequest,
        generator: DdlGenerator,
        schema: SchemaDefinition,
        result: DdlResult,
        dialect: DatabaseDialect,
        ddl: String,
        options: DdlGenerationOptions,
        splitModeStr: String?,
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

        writeReport(request, result, schema, dialect.name.lowercase(), outputPath, splitModeStr)
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
            writeReport(request, result, schema, dialect.name.lowercase(), request.report, null)
        }
    }

    private fun writeReport(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        outputPath: Path,
        splitModeStr: String?,
    ) {
        val reportPath = request.report ?: sidecarPath(outputPath, ".report.yaml")
        reportWriter(reportPath, result, schema, dialect, request.source, splitModeStr)
        if (!request.quiet) stderr("Report written to $reportPath")
    }
}
