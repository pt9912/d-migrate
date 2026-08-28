package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlScript
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.PreGenerationValidator
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SpatialProfilePolicy
import dev.dmigrate.driver.mysqlContext
import dev.dmigrate.driver.sqliteContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
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
    /**
     * Ablageort partitionierter Daten am Ziel. SQL Server bildet ihn auf eine
     * Filegroup ab; Dialekte ohne Filegroup-Begriff ignorieren ihn.
     */
    val partitionStorage: String? = null,
    val output: Path?,
    val report: Path?,
    val generateRollback: Boolean,
    val outputFormat: String,
    val verbose: Boolean,
    val quiet: Boolean,
    val splitMode: SplitMode = SplitMode.SINGLE,
    val mysqlNamedSequences: String? = null,
    val sqliteNamedSequences: String? = null,
    val deterministic: Boolean = false,
)

/**
 * Core logic for `d-migrate schema generate`. All external collaborators
 * are constructor-injected so every branch is unit-testable without a
 * CLI framework, filesystem, or real DDL generator.
 *
 * Exit codes (LF-008 / LF-009 / LF-013 / LN-012):
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
    /**
     * Driver-supplied pre-generation validator, looked up per dialect.
     * Runs **after** [validator] passes the dialect-agnostic gate and
     * **before** [generatorLookup] is asked to render DDL — the same
     * slot the SQLite-Seq-Emulation plan-doc §3.4 reserves for
     * mode-specific checks. Defaults to a no-op lookup so existing
     * wirings keep compiling without change; the SQLite branch hooks
     * `SqlitePreGenerationValidator` in via `SqliteDriver.preGenerationValidator()`.
     */
    private val preGenerationValidatorLookup: (DatabaseDialect) -> PreGenerationValidator =
        { PreGenerationValidator.NoOp },
    private val reportWriter: (Path, DdlResult, SchemaDefinition, String, Path, String?, DdlGenerationOptions) -> Unit,
    private val fileWriter: (Path, String) -> Unit =
        { path, content ->
            path.parent?.let { Files.createDirectories(it) }
            path.writeText(content)
        },
    private val formatJsonOutput: (
        DdlResult,
        SchemaDefinition,
        String,
        SplitMode,
        MysqlNamedSequenceMode?,
        SqliteNamedSequenceMode?,
    ) -> String,
    private val sidecarPath: (Path, String) -> Path,
    private val rollbackPath: (Path) -> Path,
    private val splitPath: (Path, dev.dmigrate.driver.DdlPhase) -> Path,
    private val printError: (message: String, source: String) -> Unit,
    private val printValidationResult: (ValidationResult, SchemaDefinition, String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
    private val getenv: (String) -> String? = System::getenv,
) {

    private sealed interface Preflight {
        data class Ok(
            val dialect: DatabaseDialect,
            val options: DdlGenerationOptions,
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

        // Driver-supplied pre-generation gate: lets a dialect emit
        // mode-specific blockers (e.g. SQLite helper_table E059 for
        // PK + SequenceNextVal) without polluting the dialect-agnostic
        // SchemaValidator. NoOp for drivers that have no such rules.
        val preGenErrors = preGenerationValidatorLookup(dialect).validate(schema, options)
        if (preGenErrors.isNotEmpty()) {
            printValidationResult(
                ValidationResult(errors = preGenErrors),
                schema,
                request.source.toString(),
            )
            return 3
        }

        val generator = generatorLookup(dialect)
        // FKs als POST_DATA-ALTERs nur für Generatoren, die das umsetzen (PG, MSSQL).
        val effectiveOptions = options.copy(
            deferForeignKeys = request.splitMode == SplitMode.PRE_POST && generator.supportsDeferredForeignKeys,
        )
        val result = generator.generate(schema, effectiveOptions)

        val splitExit = checkSplitDiagnostics(request, result)
        if (splitExit != null) return splitExit

        printNotes(result, request.verbose)

        return routeOutput(request, result, schema, generator, dialect, effectiveOptions)
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
                printError(
                    "Spatial profile '${profileResult.profile.cliName}' is not allowed for " +
                        "${profileResult.dialect.name.lowercase()}. " +
                        "Allowed: ${SpatialProfilePolicy.allowedFor(dialect).joinToString { it.cliName }}",
                    request.source.toString(),
                )
                return Preflight.Exit(2)
            }
        }

        val mysqlSeqMode = resolveMysqlSeqMode(request, dialect) ?: return Preflight.Exit(2)
        val sqliteSeqMode = resolveSqliteSeqMode(request, dialect) ?: return Preflight.Exit(2)
        val generatedAt = resolveGeneratedAt(request) ?: return Preflight.Exit(2)
        val dialectContext: DdlDialectContext = when (dialect) {
            DatabaseDialect.MYSQL -> DdlDialectContext.MySql(
                namedSequenceMode = mysqlSeqMode.value ?: MysqlNamedSequenceMode.ACTION_REQUIRED,
            )
            DatabaseDialect.SQLITE -> DdlDialectContext.Sqlite(
                namedSequenceMode = sqliteSeqMode.value ?: SqliteNamedSequenceMode.ACTION_REQUIRED,
            )
            else -> DdlDialectContext.None
        }
        val options = DdlGenerationOptions(
            spatialProfile = spatialProfile,
            partitionStorage = request.partitionStorage ?: DdlGenerationOptions().partitionStorage,
            dialectContext = dialectContext,
            generatedAt = generatedAt.value,
            deterministic = request.deterministic,
            // Deferral hängt an der Generator-Fähigkeit und wird nach dem (einzigen)
            // Generator-Lookup in execute() gesetzt.
            deferForeignKeys = false,
        )

        return Preflight.Ok(dialect, options)
    }

    private data class OptionalMode(val value: MysqlNamedSequenceMode?)
    private data class OptionalSqliteMode(val value: SqliteNamedSequenceMode?)
    private data class OptionalInstant(val value: Instant?)

    private fun resolveGeneratedAt(request: SchemaGenerateRequest): OptionalInstant? {
        val raw = getenv("SOURCE_DATE_EPOCH") ?: return OptionalInstant(null)
        val epochSeconds = raw.toLongOrNull()
        if (epochSeconds == null) {
            printError("Invalid SOURCE_DATE_EPOCH '$raw': expected Unix epoch seconds", request.source.toString())
            return null
        }
        return try {
            OptionalInstant(Instant.ofEpochSecond(epochSeconds))
        } catch (e: DateTimeException) {
            printError("Invalid SOURCE_DATE_EPOCH '$raw': ${e.message}", request.source.toString())
            null
        }
    }

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

    private fun resolveSqliteSeqMode(request: SchemaGenerateRequest, dialect: DatabaseDialect): OptionalSqliteMode? {
        if (request.sqliteNamedSequences != null) {
            if (dialect != DatabaseDialect.SQLITE) {
                printError(
                    "--sqlite-named-sequences is only valid with --target sqlite, " +
                        "not ${dialect.name.lowercase()}. " +
                        "Allowed values for SQLite: action_required, helper_table.",
                    request.source.toString(),
                )
                return null
            }
            val mode = SqliteNamedSequenceMode.fromCliName(request.sqliteNamedSequences)
            if (mode == null) {
                printError(
                    "Unknown --sqlite-named-sequences value '${request.sqliteNamedSequences}'. " +
                        "Allowed: action_required, helper_table",
                    request.source.toString(),
                )
                return null
            }
            return OptionalSqliteMode(mode)
        }
        return if (dialect == DatabaseDialect.SQLITE) OptionalSqliteMode(SqliteNamedSequenceMode.ACTION_REQUIRED)
        else OptionalSqliteMode(null)
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
    ): Int {
        val dialectName = dialect.name.lowercase()
        val splitModeStr = if (request.splitMode == SplitMode.PRE_POST) "pre-post" else null
        val outputWriter = SchemaGenerateOutputWriter(
            fileWriter = fileWriter,
            reportWriter = reportWriter,
            sidecarPath = sidecarPath,
            rollbackPath = rollbackPath,
            splitPath = splitPath,
            stdout = stdout,
            stderr = stderr,
        )

        if (request.splitMode == SplitMode.PRE_POST) {
            if (request.output != null) {
                outputWriter.writeSplitFileOutput(request, result, schema, dialect, splitModeStr, options)
            }
            if (request.outputFormat == "json") {
                stdout(
                    formatJsonOutput(
                        result, schema, dialectName, request.splitMode,
                        options.mysqlContext?.namedSequenceMode,
                        options.sqliteContext?.namedSequenceMode,
                    ),
                )
            }
            if (request.output == null && request.outputFormat != "json") return 2
        } else {
            // Skript-Darstellung (Batch-Trenner je Dialekt), nicht das rohe render().
            val ddl = DdlScript.render(result, dialect)
            when {
                request.outputFormat == "json" ->
                    stdout(
                    formatJsonOutput(
                        result, schema, dialectName, request.splitMode,
                        options.mysqlContext?.namedSequenceMode,
                        options.sqliteContext?.namedSequenceMode,
                    ),
                )
                request.output != null -> {
                    val gen = GeneratedDdl(generator, schema, result, dialect, ddl, options)
                    outputWriter.writeFileOutput(request, gen, splitModeStr)
                }
                else -> {
                    val gen = GeneratedDdl(generator, schema, result, dialect, ddl, options)
                    outputWriter.writeStdoutOutput(request, gen)
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
}
