package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.migration.DiffDdlGenerator
import java.nio.file.Path

/**
 * Bundle of resolved operand state handed from [SchemaMigratePreparation]
 * to the rest of the migrate pipeline. Holds the parsed/normalised
 * source + target plus the resolved dialect and renderer so the runner
 * doesn't carry these as ad-hoc locals.
 */
internal data class SchemaMigratePrepared(
    val sourceOp: CompareOperand,
    val targetOp: CompareOperand,
    val sourceResolved: ResolvedSchemaOperand,
    val targetResolved: ResolvedSchemaOperand,
    val sourceNormalized: ResolvedSchemaOperand,
    val targetNormalized: ResolvedSchemaOperand,
    val effectiveDialect: DatabaseDialect,
    val renderer: DiffDdlGenerator,
)

/**
 * Either a successful [SchemaMigratePrepared] or an early-exit exit
 * code captured by [SchemaMigratePreparation]. Used so the runner can
 * surface I/O / validation failures without threading two return
 * channels through `execute()`.
 */
internal sealed interface SchemaMigratePreparationResult {
    data class Ready(val prepared: SchemaMigratePrepared) : SchemaMigratePreparationResult
    data class ExitEarly(val exitCode: Int) : SchemaMigratePreparationResult
}

/**
 * Pre-render stage for `schema migrate`: CLI flag validation, operand
 * parsing, source/target loading via [fileLoader] / [dbLoader],
 * normalisation, schema validation, dialect resolution, and renderer
 * lookup. Each failure produces a structured exit code so the runner
 * can route the user-facing error without owning the same set of
 * branches.
 *
 * Extracted from [SchemaMigrateRunner] for the `LargeClass` budget.
 */
internal class SchemaMigratePreparation(
    private val operandParser: (String) -> CompareOperand,
    private val fileLoader: (CompareOperand.File) -> ResolvedSchemaOperand,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)?,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand,
    private val rendererFor: (DatabaseDialect) -> DiffDdlGenerator?,
    private val printError: (message: String, source: String) -> Unit,
    private val stderr: (String) -> Unit,
    private val renderReport: (SchemaMigrateReport, format: String) -> String,
    private val ensureParentDirectories: (Path) -> Unit,
    private val atomicWriter: (Path, String) -> Unit,
) {

    fun prepare(request: SchemaMigrateRequest): SchemaMigratePreparationResult {
        validateRequest(request)?.let { return SchemaMigratePreparationResult.ExitEarly(it) }

        val operands = parseOperands(request) ?: return SchemaMigratePreparationResult.ExitEarly(2)
        val (sourceOp, targetOp) = operands

        if (request.execute && targetOp !is CompareOperand.Database) {
            printError("--execute requires a DB target (db:<url>).", request.target)
            return SchemaMigratePreparationResult.ExitEarly(2)
        }

        if (hasOutputCollision(request, sourceOp, targetOp)) {
            return SchemaMigratePreparationResult.ExitEarly(2)
        }

        val sourceLoad = loadOperand(sourceOp, request.source, request.cliConfigPath)
        if (sourceLoad is OperandLoadResult.Failed) return SchemaMigratePreparationResult.ExitEarly(sourceLoad.exitCode)
        val sourceResolved = (sourceLoad as OperandLoadResult.Loaded).resolved

        val targetLoad = loadOperand(targetOp, request.target, request.cliConfigPath)
        if (targetLoad is OperandLoadResult.Failed) return SchemaMigratePreparationResult.ExitEarly(targetLoad.exitCode)
        val targetResolved = (targetLoad as OperandLoadResult.Loaded).resolved

        val normalized = normalizeOperands(request, sourceResolved, targetResolved)
            ?: return SchemaMigratePreparationResult.ExitEarly(7)
        val (sourceNormalized, targetNormalized) = normalized

        if (!sourceNormalized.validation.isValid || !targetNormalized.validation.isValid) {
            val exit = emitValidationFailure(request, sourceNormalized, targetNormalized)
            return SchemaMigratePreparationResult.ExitEarly(exit)
        }

        val effectiveDialect = resolveDialect(request, targetResolved)
            ?: return SchemaMigratePreparationResult.ExitEarly(2)

        // Atomic-Preserve follow-up (Finding #4, 2026-06-01): mirror
        // `schema generate`'s dialect-context check. A
        // `--mysql-named-sequences` set against a non-MySQL target (or
        // `--sqlite-named-sequences` against a non-SQLite target) is
        // a structurally meaningless combination — surface it as an
        // Exit-2 validation error so the operator doesn't think the
        // flag was applied.
        if (request.mysqlNamedSequences != null && effectiveDialect != DatabaseDialect.MYSQL) {
            printError(
                "--mysql-named-sequences is only valid with a MySQL target, " +
                    "not ${effectiveDialect.name.lowercase()}.",
                request.source,
            )
            return SchemaMigratePreparationResult.ExitEarly(2)
        }
        if (request.sqliteNamedSequences != null && effectiveDialect != DatabaseDialect.SQLITE) {
            printError(
                "--sqlite-named-sequences is only valid with a SQLite target, " +
                    "not ${effectiveDialect.name.lowercase()}.",
                request.source,
            )
            return SchemaMigratePreparationResult.ExitEarly(2)
        }

        val renderer = rendererFor(effectiveDialect)
        if (renderer == null) {
            printError("No renderer registered for dialect ${effectiveDialect.name}", request.source)
            return SchemaMigratePreparationResult.ExitEarly(2)
        }

        return SchemaMigratePreparationResult.Ready(
            SchemaMigratePrepared(
                sourceOp = sourceOp,
                targetOp = targetOp,
                sourceResolved = sourceResolved,
                targetResolved = targetResolved,
                sourceNormalized = sourceNormalized,
                targetNormalized = targetNormalized,
                effectiveDialect = effectiveDialect,
                renderer = renderer,
            ),
        )
    }

    private fun parseOperands(request: SchemaMigrateRequest): Pair<CompareOperand, CompareOperand>? = try {
        operandParser(request.source) to operandParser(request.target)
    } catch (e: IllegalArgumentException) {
        printError("Invalid operand: ${e.message}", request.source)
        null
    }

    private fun hasOutputCollision(
        request: SchemaMigrateRequest,
        sourceOp: CompareOperand,
        targetOp: CompareOperand,
    ): Boolean {
        val filePaths = listOfNotNull(
            (sourceOp as? CompareOperand.File)?.path,
            (targetOp as? CompareOperand.File)?.path,
        ).toTypedArray()
        if (filePaths.isEmpty()) return false
        request.output?.let { if (collidesWithOperand(it, *filePaths)) return true }
        request.report?.let { if (collidesWithOperand(it, *filePaths)) return true }
        return false
    }

    private fun normalizeOperands(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
    ): Pair<ResolvedSchemaOperand, ResolvedSchemaOperand>? = try {
        normalizer(source) to normalizer(target)
    } catch (e: IllegalStateException) {
        printError("Invalid reverse marker: ${e.message}", request.source)
        null
    }

    private fun validateRequest(request: SchemaMigrateRequest): Int? {
        if (request.dryRun && request.execute) {
            printError("--dry-run and --execute are mutually exclusive.", request.source)
            return 2
        }
        if (request.execute && request.report == null) {
            printError("--execute requires --report (audit-trail).", request.source)
            return 2
        }
        if (request.execute && request.planOnly) {
            printError("--execute and --plan-only are mutually exclusive.", request.source)
            return 2
        }
        if (request.planOnly && request.rollbackOutput != null) {
            printError("--plan-only forbids --rollback-output.", request.source)
            return 2
        }
        if (request.generateRollback && !request.planOnly && request.rollbackOutput == null) {
            printError(
                "--generate-rollback requires --rollback-output (or use --plan-only for a capability check).",
                request.source,
            )
            return 2
        }
        if (!request.generateRollback && request.rollbackOutput != null) {
            printError("--rollback-output requires --generate-rollback.", request.source)
            return 2
        }
        // Atomic-Preserve follow-up (Finding #4, 2026-06-01): mirror
        // `schema generate`'s parseability check for the named-sequences
        // flags. Without this, a typo like
        // `--sqlite-named-sequences helpr_table` falls through silently
        // because `SqliteNamedSequenceMode.fromCliName(...)` returns
        // null and the downstream `SequencePreserveStage` skips its
        // helper-table-mode branch — the operator only finds out via
        // a `SEQUENCE_PRESERVE_OPT_IN_REQUIRED` blocker that doesn't
        // surface the typo.
        if (request.mysqlNamedSequences != null &&
            MysqlNamedSequenceMode.fromCliName(request.mysqlNamedSequences) == null
        ) {
            printError(
                "Unknown --mysql-named-sequences value '${request.mysqlNamedSequences}'. " +
                    "Allowed: action_required, helper_table",
                request.source,
            )
            return 2
        }
        if (request.sqliteNamedSequences != null &&
            SqliteNamedSequenceMode.fromCliName(request.sqliteNamedSequences) == null
        ) {
            printError(
                "Unknown --sqlite-named-sequences value '${request.sqliteNamedSequences}'. " +
                    "Allowed: action_required, helper_table",
                request.source,
            )
            return 2
        }
        return null
    }

    private fun collidesWithOperand(out: Path, vararg operands: Path): Boolean {
        val n = out.toAbsolutePath().normalize()
        for (op in operands) {
            if (n == op.toAbsolutePath().normalize()) {
                printError("Output path must not be the same as source or target", out.toString())
                return true
            }
        }
        return false
    }

    private fun loadOperand(
        operand: CompareOperand,
        rawRef: String,
        configPath: Path?,
    ): OperandLoadResult = when (operand) {
        is CompareOperand.File -> try {
            OperandLoadResult.Loaded(fileLoader(operand))
        } catch (e: Exception) {
            printError("Failed to read schema file: ${e.message}", rawRef)
            OperandLoadResult.Failed(7)
        }
        is CompareOperand.Database -> loadDatabaseOperand(operand, rawRef, configPath)
    }

    private fun loadDatabaseOperand(
        operand: CompareOperand.Database,
        rawRef: String,
        configPath: Path?,
    ): OperandLoadResult {
        val loader = dbLoader
        if (loader == null) {
            printError("Database operands require a DB loader to be wired.", rawRef)
            return OperandLoadResult.Failed(2)
        }
        return try {
            OperandLoadResult.Loaded(loader(operand, configPath))
        } catch (e: CompareConfigException) {
            printError("Config/URL error: ${e.message}", rawRef)
            OperandLoadResult.Failed(7)
        } catch (e: Exception) {
            printError("Connection/metadata error: ${e.message}", rawRef)
            OperandLoadResult.Failed(4)
        }
    }

    private fun emitValidationFailure(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
    ): Int {
        for (e in source.validation.errors) stderr("source: ${e.code} ${e.message}")
        for (e in target.validation.errors) stderr("target: ${e.code} ${e.message}")
        val dialectName = request.dialect?.name ?: target.dialect?.name ?: source.dialect?.name ?: "UNKNOWN"
        val report = SchemaMigrateReport(
            status = "validation_failed",
            exitCode = 3,
            source = source.reference,
            target = target.reference,
            dialect = dialectName,
            planOnly = request.planOnly,
            blockers = emptyList(),
            diagnostics = emptyList(),
            operations = emptyList(),
            statements = null,
            summary = SchemaMigrateSummary(),
            bodyDisplay = request.bodyDisplay(),
        )
        request.report?.let { writeReport(it, report, request.reportFormat) }
        return 3
    }

    private fun writeReport(path: Path, report: SchemaMigrateReport, format: String) {
        try {
            ensureParentDirectories(path)
            atomicWriter(path, renderReport(report, format))
        } catch (e: Exception) {
            printError("Failed to write report: ${e.message}", path.toString())
        }
    }

    private fun resolveDialect(request: SchemaMigrateRequest, target: ResolvedSchemaOperand): DatabaseDialect? {
        val targetDialect = target.dialect
        val requested = request.dialect
        if (requested == null && targetDialect == null) {
            printError(
                "--dialect is required when neither source nor target is a database connection.",
                request.source,
            )
            return null
        }
        if (requested != null && targetDialect != null && requested != targetDialect) {
            printError(
                "Requested dialect ${requested.name} does not match target connection dialect " +
                    "${targetDialect.name} (TARGET_DIALECT_MISMATCH).",
                request.target,
            )
            return null
        }
        return requested ?: targetDialect
    }

    private sealed interface OperandLoadResult {
        data class Loaded(val resolved: ResolvedSchemaOperand) : OperandLoadResult
        data class Failed(val exitCode: Int) : OperandLoadResult
    }
}
