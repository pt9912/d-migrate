package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Runner for `schema migrate` per `spec/cli-spec.md §6.1`.
 *
 * Slices delivered:
 *
 * - **E.1**: file-to-file mode. `--dialect` mandatory.
 * - **E.2**: DB-target (and DB-source) mode via the injected
 *   `dbLoader`. `--dialect` becomes optional when *target* is a DB —
 *   the loader supplies it from the connection. If both are set, they
 *   must match (otherwise Exit 2 `TARGET_DIALECT_MISMATCH`-style).
 * - **E.3+**: `--generate-rollback`, `--execute` — still rejected.
 *
 * Pipeline: load → normalize → validate → compare → plan → render.
 *
 * Output:
 *
 * - Up-SQL artefact: with `--output` written atomically (write-temp,
 *   rename); without it, rendered to stdout when not `--plan-only`.
 * - Report: with `--report` written atomically; without it, only on
 *   `--plan-only` and only to stdout. `--execute` requires `--report`
 *   (Exit 2) — but `--execute` is rejected upstream in E.1.
 *
 * Exit-Code mapping per spec §6.1:
 *
 * - `0` — successful render (or no-op diff)
 * - `2` — invalid CLI args (missing `--dialect`, file-target with
 *   `--execute`, etc.)
 * - `3` — source/target schema validation error
 * - `7` — local I/O / planning / render / artefact-write error
 * - `8` — `MIGRATION_BLOCKED` (renderer or planner blockers)
 */
class SchemaMigrateRunner(
    private val operandParser: (String) -> CompareOperand = CompareOperandParser::parse,
    private val fileLoader: (CompareOperand.File) -> ResolvedSchemaOperand,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)? = null,
    private val normalizer: (ResolvedSchemaOperand) -> ResolvedSchemaOperand = CompareOperandNormalizer::normalize,
    private val comparator: (SchemaDefinition, SchemaDefinition) -> SchemaDiff,
    private val planner: DiffPlanner = DiffPlanner(),
    private val rendererFor: (DatabaseDialect) -> DiffDdlGenerator?,
    private val urlScrubber: (String) -> String = { it },
    private val ensureParentDirectories: (Path) -> Unit = { it.parent?.toFile()?.mkdirs() },
    private val atomicWriter: (Path, String) -> Unit = ::defaultAtomicWriter,
    private val renderReport: (SchemaMigrateReport, format: String) -> String,
    private val printError: (message: String, source: String) -> Unit,
    private val stdout: (String) -> Unit = { println(it) },
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)
    private val userFacingStderr = userFacingErrors.stderrSink(stderr)

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun execute(
        request: SchemaMigrateRequest,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): Int {
        cancellationToken.throwIfCancellationRequested()

        // 1. CLI validation
        validateRequest(request)?.let { return it }

        // 2. Parse operands
        val sourceOp: CompareOperand
        val targetOp: CompareOperand
        try {
            sourceOp = operandParser(request.source)
            targetOp = operandParser(request.target)
        } catch (e: IllegalArgumentException) {
            userFacingPrintError("Invalid operand: ${e.message}", request.source)
            return 2
        }

        // Output collision check (only meaningful for file operands)
        val filePaths = listOfNotNull(
            (sourceOp as? CompareOperand.File)?.path,
            (targetOp as? CompareOperand.File)?.path,
        ).toTypedArray()
        if (filePaths.isNotEmpty()) {
            request.output?.let { if (collidesWithOperand(it, *filePaths)) return 2 }
            request.report?.let { if (collidesWithOperand(it, *filePaths)) return 2 }
        }

        cancellationToken.throwIfCancellationRequested()

        // 3. Load + normalize + validate both
        val sourceResolved = loadOperand(sourceOp, request.source, request.cliConfigPath) ?: return lastExitCode
        val targetResolved = loadOperand(targetOp, request.target, request.cliConfigPath) ?: return lastExitCode

        val (sourceNormalized, targetNormalized) = try {
            normalizer(sourceResolved) to normalizer(targetResolved)
        } catch (e: IllegalStateException) {
            userFacingPrintError("Invalid reverse marker: ${e.message}", request.source)
            return 7
        }

        if (!sourceNormalized.validation.isValid || !targetNormalized.validation.isValid) {
            return emitValidationFailure(request, sourceNormalized, targetNormalized)
        }

        // 4. Resolve effective dialect
        val effectiveDialect = resolveDialect(request, targetResolved) ?: return 2

        // 5. Resolve renderer
        val renderer = rendererFor(effectiveDialect)
        if (renderer == null) {
            userFacingPrintError("No renderer registered for dialect ${effectiveDialect.name}", request.source)
            return 2
        }

        cancellationToken.throwIfCancellationRequested()

        // 6. Pipeline: compare → plan → render
        val diff = comparator(targetNormalized.schema, sourceNormalized.schema)
        val plan = planner.plan(targetNormalized.schema, sourceNormalized.schema, diff)
        cancellationToken.throwIfCancellationRequested()
        val rendered = renderer.generateUp(plan, DdlGenerationOptions())

        // 7. Block on destructive without --allow-destructive
        val effective = applyDestructiveGuard(rendered, request.allowDestructive)

        // 8. Build report
        val report = buildReport(request, sourceResolved, targetResolved, plan, effective, effectiveDialect)

        // 9. Decide outcome
        return finalize(request, effective, report)
    }

    private fun resolveDialect(request: SchemaMigrateRequest, target: ResolvedSchemaOperand): DatabaseDialect? {
        val targetDialect = target.dialect
        val requested = request.dialect
        return when {
            requested == null && targetDialect == null -> {
                userFacingPrintError(
                    "--dialect is required when neither source nor target is a database connection.",
                    request.source,
                )
                null
            }
            requested != null && targetDialect != null && requested != targetDialect -> {
                userFacingPrintError(
                    "Requested dialect ${requested.name} does not match target connection dialect " +
                        "${targetDialect.name} (TARGET_DIALECT_MISMATCH).",
                    request.target,
                )
                null
            }
            else -> requested ?: targetDialect
        }
    }

    private var lastExitCode: Int = 7

    private fun validateRequest(request: SchemaMigrateRequest): Int? {
        if (request.execute) {
            userFacingPrintError("--execute is not supported yet (E.4).", request.source)
            return 2
        }
        if (request.generateRollback) {
            userFacingPrintError("--generate-rollback is not supported yet (E.3).", request.source)
            return 2
        }
        if (request.dryRun && request.execute) {
            userFacingPrintError("--dry-run and --execute are mutually exclusive.", request.source)
            return 2
        }
        if (request.planOnly && request.rollbackOutput != null) {
            userFacingPrintError("--plan-only forbids --rollback-output.", request.source)
            return 2
        }
        return null
    }

    private fun collidesWithOperand(out: Path, vararg operands: Path): Boolean {
        val n = out.toAbsolutePath().normalize()
        for (op in operands) {
            if (n == op.toAbsolutePath().normalize()) {
                userFacingPrintError("Output path must not be the same as source or target", out.toString())
                return true
            }
        }
        return false
    }

    private fun loadOperand(
        operand: CompareOperand,
        rawRef: String,
        configPath: Path?,
    ): ResolvedSchemaOperand? = when (operand) {
        is CompareOperand.File -> try {
            fileLoader(operand)
        } catch (e: Exception) {
            userFacingPrintError("Failed to read schema file: ${e.message}", rawRef)
            lastExitCode = 7
            null
        }
        is CompareOperand.Database -> {
            val loader = dbLoader
            if (loader == null) {
                userFacingPrintError("Database operands require a DB loader to be wired.", rawRef)
                lastExitCode = 2
                null
            } else {
                try {
                    loader(operand, configPath)
                } catch (e: CompareConfigException) {
                    userFacingPrintError("Config/URL error: ${e.message}", rawRef)
                    lastExitCode = 7
                    null
                } catch (e: Exception) {
                    userFacingPrintError("Connection/metadata error: ${e.message}", rawRef)
                    lastExitCode = 4
                    null
                }
            }
        }
    }

    private fun emitValidationFailure(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
    ): Int {
        for (e in source.validation.errors) userFacingStderr("source: ${e.code} ${e.message}")
        for (e in target.validation.errors) userFacingStderr("target: ${e.code} ${e.message}")
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
        )
        request.report?.let { writeReport(it, report, request.reportFormat) }
        return 3
    }

    private fun applyDestructiveGuard(
        rendered: MigrationDdlResult,
        allowDestructive: Boolean,
    ): MigrationDdlResult {
        if (allowDestructive || rendered.destructiveOperations.isEmpty()) return rendered
        if (rendered.blockers.any { it.reason == MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION }) {
            return rendered
        }
        val withGuard = rendered.copy(
            blockers = rendered.blockers + dev.dmigrate.driver.migration.MigrationBlocker(
                reason = MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION,
                operationIds = rendered.destructiveOperations,
            ),
        )
        return withGuard.copy(primaryBlockedReason = MigrationBlockedReason.DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION)
    }

    private fun buildReport(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
        plan: DiffResult,
        rendered: MigrationDdlResult,
        dialect: DatabaseDialect,
    ): SchemaMigrateReport {
        val isBlocked = rendered.isBlocked
        val isEmpty = plan.operations.isEmpty()
        val status = when {
            isBlocked -> "blocked"
            isEmpty -> "no_op"
            else -> "ok"
        }
        val exitCode = if (isBlocked) 8 else 0
        return SchemaMigrateReport(
            status = status,
            exitCode = exitCode,
            source = source.reference,
            target = target.reference,
            dialect = dialect.name,
            planOnly = request.planOnly,
            blockers = rendered.blockers.map {
                SchemaMigrateBlockerView(
                    reason = it.reason.name,
                    operationIds = it.operationIds.toList(),
                    diagnosticCodes = it.diagnostics.map { d -> d.code },
                )
            },
            diagnostics = rendered.diagnostics.map {
                SchemaMigrateDiagnosticView(
                    code = it.code,
                    severity = it.severity.name,
                    message = it.message,
                    operationId = it.operationId,
                )
            },
            operations = plan.operations.map {
                SchemaMigrateOperationView(
                    id = it.id,
                    kind = it::class.simpleName ?: "Unknown",
                    objectType = it.objectType.name,
                    path = it.objectRef.path,
                    phase = it.phase.name,
                    reversibility = it.reversibility.name,
                    rendered = it.id in rendered.operationsRendered,
                    skipped = it.id in rendered.operationsSkipped,
                )
            },
            statements = if (request.planOnly) null else rendered.statements.map {
                SchemaMigrateStatementView(
                    sql = it.sql,
                    operationIds = it.operationIds.toList(),
                    phase = it.phase.name,
                    destructive = it.risk.destructive,
                )
            },
            summary = SchemaMigrateSummary(
                operationsTotal = plan.operations.size,
                operationsRendered = rendered.operationsRendered.size,
                operationsSkipped = rendered.operationsSkipped.size,
                statementsTotal = rendered.statements.size,
                destructiveCount = rendered.destructiveOperations.size,
                manualActionCount = rendered.manualActions.size,
                nonReversibleCount = rendered.nonReversibleOperations.size,
                primaryBlockedReason = rendered.primaryBlockedReason?.name,
            ),
        )
    }

    private fun finalize(
        request: SchemaMigrateRequest,
        rendered: MigrationDdlResult,
        report: SchemaMigrateReport,
    ): Int {
        // Always emit the report when --report is set (also on blockers).
        request.report?.let { writeReport(it, report, request.reportFormat) ?: return 7 }

        // On blocker: report-only — do not emit Up-SQL.
        if (report.exitCode == 8) {
            if (request.report == null) stdout(renderReport(report, request.reportFormat))
            return 8
        }

        // On --plan-only: report only.
        if (request.planOnly) {
            if (request.report == null) stdout(renderReport(report, request.reportFormat))
            return report.exitCode
        }

        // Up-SQL artefact emission.
        val upSql = rendered.statements.joinToString("\n\n") { it.sql }
        if (request.output != null) {
            try {
                ensureParentDirectories(request.output)
                atomicWriter(request.output, upSql)
            } catch (e: Exception) {
                userFacingPrintError("Failed to write up-SQL artefact: ${e.message}", request.output.toString())
                return 7
            }
        } else {
            // Render Up-SQL to stdout when no --output was set (dry-run-by-default per spec §6.1).
            stdout(upSql)
        }
        return report.exitCode
    }

    private fun writeReport(path: Path, report: SchemaMigrateReport, format: String): Unit? {
        return try {
            ensureParentDirectories(path)
            atomicWriter(path, renderReport(report, format))
            Unit
        } catch (e: Exception) {
            userFacingPrintError("Failed to write report: ${e.message}", path.toString())
            null
        }
    }

    private companion object {
        /**
         * Writes via a `<path>.tmp-<uuid>` sibling and then renames
         * atomically. Failure modes leave the original file unchanged.
         */
        fun defaultAtomicWriter(path: Path, content: String) {
            val tmp = path.resolveSibling("${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
            tmp.writeText(content)
            try {
                java.nio.file.Files.move(
                    tmp,
                    path,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                java.nio.file.Files.deleteIfExists(tmp)
                throw e
            }
        }
    }
}

// ── Request / Report DTOs ───────────────────────────────────────────

data class SchemaMigrateRequest(
    val source: String,
    val target: String,
    /**
     * Required for file-to-file mode; optional with DB-target — the
     * loader derives it from the connection. If both this field and a
     * DB-target dialect are present, they must match (Exit 2).
     */
    val dialect: DatabaseDialect? = null,
    val output: Path? = null,
    val report: Path? = null,
    val rollbackOutput: Path? = null,
    val reportFormat: String = "json",
    val planOnly: Boolean = false,
    val allowDestructive: Boolean = false,
    val generateRollback: Boolean = false,
    val execute: Boolean = false,
    val dryRun: Boolean = false,
    val cliConfigPath: Path? = null,
)

data class SchemaMigrateReport(
    val status: String,
    val exitCode: Int,
    val source: String,
    val target: String,
    val dialect: String,
    val planOnly: Boolean,
    val operations: List<SchemaMigrateOperationView>,
    val statements: List<SchemaMigrateStatementView>?,
    val blockers: List<SchemaMigrateBlockerView>,
    val diagnostics: List<SchemaMigrateDiagnosticView>,
    val summary: SchemaMigrateSummary,
)

data class SchemaMigrateOperationView(
    val id: String,
    val kind: String,
    val objectType: String,
    val path: List<String>,
    val phase: String,
    val reversibility: String,
    val rendered: Boolean,
    val skipped: Boolean,
)

data class SchemaMigrateStatementView(
    val sql: String,
    val operationIds: List<String>,
    val phase: String,
    val destructive: Boolean,
)

data class SchemaMigrateBlockerView(
    val reason: String,
    val operationIds: List<String>,
    val diagnosticCodes: List<String>,
)

data class SchemaMigrateDiagnosticView(
    val code: String,
    val severity: String,
    val message: String,
    val operationId: String?,
)

data class SchemaMigrateSummary(
    val operationsTotal: Int = 0,
    val operationsRendered: Int = 0,
    val operationsSkipped: Int = 0,
    val statementsTotal: Int = 0,
    val destructiveCount: Int = 0,
    val manualActionCount: Int = 0,
    val nonReversibleCount: Int = 0,
    val primaryBlockedReason: String? = null,
)
