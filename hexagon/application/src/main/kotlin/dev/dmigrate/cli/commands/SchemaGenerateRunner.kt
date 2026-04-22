package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
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
    val mysqlNamedSequences: String? = null,
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
    private val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path, String?, MysqlNamedSequenceMode?) -> Unit,
    private val fileWriter: (Path, String) -> Unit =
        { path, content -> path.writeText(content) },
    private val formatJsonOutput: (DdlResult, SchemaDefinition, String, SplitMode, MysqlNamedSequenceMode?) -> String,
    private val sidecarPath: (Path, String) -> Path,
    private val rollbackPath: (Path) -> Path,
    private val splitPath: (Path, dev.dmigrate.driver.DdlPhase) -> Path,
    private val printError: (message: String, source: String) -> Unit,
    private val printValidationResult: (ValidationResult, SchemaDefinition, String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {

    private sealed interface Preflight {
        data class Ok(
            val dialect: DatabaseDialect,
            val options: DdlGenerationOptions,
            val mysqlSeqMode: MysqlNamedSequenceMode?,
        ) : Preflight
        data class Exit(val code: Int) : Preflight
    }

    fun execute(request: SchemaGenerateRequest): Int {
        val pre = when (val r = validateAndResolveOptions(request)) {
            is Preflight.Ok -> r
            is Preflight.Exit -> return r.code
        }
        val dialect = pre.dialect
        val options = pre.options
        val mysqlSeqMode = pre.mysqlSeqMode

        val schema = try {
            schemaReader(request.source)
        } catch (e: Exception) {
            printError("Failed to parse schema file: ${e.message}", request.source.toString())
            return 7
        }

        val validationResult = validator(schema)
        if (!validationResult.isValid) {
            printValidationResult(validationResult, schema, request.source.toString())
            return 3
        }

        val generator = generatorLookup(dialect)
        val result = generator.generate(schema, options)

        val splitExit = checkSplitDiagnostics(request, result)
        if (splitExit != null) return splitExit

        printNotes(result, request.verbose)

        return routeOutput(request, result, schema, generator, dialect, options, mysqlSeqMode)
    }

    private fun validateAndResolveOptions(request: SchemaGenerateRequest): Preflight {
        val splitExit = validateSplitModePreflight(request)
        if (splitExit != null) return Preflight.Exit(splitExit)

        val dialect = try {
            DatabaseDialect.fromString(request.target)
        } catch (e: IllegalArgumentException) {
            printError(e.message ?: "Unknown dialect", request.source.toString())
            return Preflight.Exit(2)
        }

        val spatialProfile = when (val profileResult = SpatialProfilePolicy.resolve(dialect, request.spatialProfile)) {
            is SpatialProfilePolicy.Result.Resolved -> profileResult.profile
            is SpatialProfilePolicy.Result.UnknownProfile -> {
                printError("Unknown spatial profile '${profileResult.raw}'. " +
                    "Allowed: ${SpatialProfilePolicy.allowedFor(dialect).joinToString { it.cliName }}",
                    request.source.toString())
                return Preflight.Exit(2)
            }
            is SpatialProfilePolicy.Result.NotAllowedForDialect -> {
                printError("Spatial profile '${profileResult.profile.cliName}' is not allowed for ${profileResult.dialect.name.lowercase()}. " +
                    "Allowed: ${SpatialProfilePolicy.allowedFor(dialect).joinToString { it.cliName }}",
                    request.source.toString())
                return Preflight.Exit(2)
            }
        }

        val mysqlSeqMode = resolveMysqlSeqMode(request, dialect) ?: return Preflight.Exit(2)
        val options = DdlGenerationOptions(
            spatialProfile = spatialProfile,
            mysqlNamedSequenceMode = mysqlSeqMode.value,
        )

        return Preflight.Ok(dialect, options, mysqlSeqMode.value)
    }

    private data class OptionalMode(val value: MysqlNamedSequenceMode?)

    private fun resolveMysqlSeqMode(request: SchemaGenerateRequest, dialect: DatabaseDialect): OptionalMode? {
        if (request.mysqlNamedSequences != null) {
            if (dialect != DatabaseDialect.MYSQL) {
                printError(
                    "--mysql-named-sequences is only valid with --target mysql, " +
                        "not ${dialect.name.lowercase()}. " +
                        "Allowed values for MySQL: action_required, helper_table.",
                    request.source.toString(),
                )
                return null
            }
            val mode = MysqlNamedSequenceMode.fromCliName(request.mysqlNamedSequences)
            if (mode == null) {
                printError(
                    "Unknown --mysql-named-sequences value '${request.mysqlNamedSequences}'. " +
                        "Allowed: action_required, helper_table",
                    request.source.toString(),
                )
                return null
            }
            return OptionalMode(mode)
        }
        return if (dialect == DatabaseDialect.MYSQL) OptionalMode(MysqlNamedSequenceMode.ACTION_REQUIRED)
        else OptionalMode(null)
    }

    private fun validateSplitModePreflight(request: SchemaGenerateRequest): Int? {
        if (request.splitMode != SplitMode.PRE_POST) return null
        if (request.generateRollback) {
            stderr("`--split pre-post` cannot be combined with `--generate-rollback`.")
            return 2
        }
        if (request.output == null && request.outputFormat != "json") {
            stderr("`--split pre-post` requires `--output` unless `--output-format json` is used.")
            return 2
        }
        return null
    }

    private fun checkSplitDiagnostics(request: SchemaGenerateRequest, result: DdlResult): Int? {
        if (request.splitMode != SplitMode.PRE_POST) return null
        val splitDiags = result.globalNotes.filter { it.code == "E060" }
        if (splitDiags.isEmpty()) return null
        for (d in splitDiags) {
            stderr("  \u2717 Split error [${d.code}]: ${d.message}")
            if (d.hint != null) stderr("    \u2192 Hint: ${d.hint}")
        }
        return 2
    }

    private fun routeOutput(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        generator: DdlGenerator,
        dialect: DatabaseDialect,
        options: DdlGenerationOptions,
        mysqlSeqMode: MysqlNamedSequenceMode?,
    ): Int {
        val dialectName = dialect.name.lowercase()
        val splitModeStr = if (request.splitMode == SplitMode.PRE_POST) "pre-post" else null

        if (request.splitMode == SplitMode.PRE_POST) {
            if (request.output != null) {
                writeSplitFileOutput(request, result, schema, dialectName, splitModeStr, mysqlSeqMode)
            }
            if (request.outputFormat == "json") {
                stdout(formatJsonOutput(result, schema, dialectName, request.splitMode, mysqlSeqMode))
            }
            if (request.output == null && request.outputFormat != "json") return 2
        } else {
            val ddl = result.render()
            when {
                request.outputFormat == "json" ->
                    stdout(formatJsonOutput(result, schema, dialectName, request.splitMode, mysqlSeqMode))
                request.output != null -> {
                    val gen = GeneratedDdl(generator, schema, result, dialect, ddl, options)
                    writeFileOutput(request, gen, splitModeStr)
                }
                else -> {
                    val gen = GeneratedDdl(generator, schema, result, dialect, ddl, options)
                    writeStdoutOutput(request, gen)
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
        mysqlSeqMode: MysqlNamedSequenceMode? = null,
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

        writeReport(request, result, schema, dialect, outputPath, splitModeStr, mysqlSeqMode)
    }

    private data class GeneratedDdl(
        val generator: DdlGenerator, val schema: SchemaDefinition, val result: DdlResult,
        val dialect: DatabaseDialect, val ddl: String, val options: DdlGenerationOptions,
    )

    private fun writeFileOutput(request: SchemaGenerateRequest, gen: GeneratedDdl, splitModeStr: String?) {
        val outputPath = request.output!!
        fileWriter(outputPath, gen.ddl + "\n")
        if (!request.quiet) stderr("DDL written to $outputPath")

        if (request.generateRollback) {
            val rollbackResult = gen.generator.generateRollback(gen.schema, gen.options)
            val rbPath = rollbackPath(outputPath)
            fileWriter(rbPath, rollbackResult.render() + "\n")
            if (!request.quiet) stderr("Rollback DDL written to $rbPath")
        }

        writeReport(request, gen.result, gen.schema, gen.dialect.name.lowercase(), outputPath, splitModeStr, gen.options.mysqlNamedSequenceMode)
    }

    private fun writeStdoutOutput(request: SchemaGenerateRequest, gen: GeneratedDdl) {
        stdout(gen.ddl)
        if (request.generateRollback) {
            stdout("\n-- ═══════════════════════════════════════")
            stdout("-- ROLLBACK")
            stdout("-- ═══════════════════════════════════════\n")
            stdout(gen.generator.generateRollback(gen.schema, gen.options).render())
        }

        if (request.report != null) {
            writeReport(request, gen.result, gen.schema, gen.dialect.name.lowercase(), request.report, null, gen.options.mysqlNamedSequenceMode)
        }
    }

    private fun writeReport(
        request: SchemaGenerateRequest,
        result: DdlResult,
        schema: SchemaDefinition,
        dialect: String,
        outputPath: Path,
        splitModeStr: String?,
        mysqlSeqMode: MysqlNamedSequenceMode? = null,
    ) {
        val reportPath = request.report ?: sidecarPath(outputPath, ".report.yaml")
        reportWriter(reportPath, result, schema, dialect, request.source, splitModeStr, mysqlSeqMode)
        if (!request.quiet) stderr("Report written to $reportPath")
    }
}
