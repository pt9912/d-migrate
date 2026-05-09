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
 * E.1 first-slice runner for `schema migrate`. Implements the
 * file-to-file mode per `spec/cli-spec.md §6.1 schema migrate`:
 *
 * - both `--source` and `--target` are local schema files,
 * - `--dialect` is mandatory (no DB connection to derive from),
 * - `--execute` is rejected (Exit 2 — DB execution lands in E.4),
 * - `--generate-rollback` is deferred to E.3.
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

        // E.1: only file-to-file is supported.
        if (sourceOp !is CompareOperand.File) {
            userFacingPrintError("E.1 only supports file sources; got ${sourceOp::class.simpleName}", request.source)
            return 2
        }
        if (targetOp !is CompareOperand.File) {
            userFacingPrintError("E.1 only supports file targets; got ${targetOp::class.simpleName}", request.target)
            return 2
        }

        // Output collision check
        request.output?.let { if (collidesWithOperand(it, sourceOp.path, targetOp.path)) return 2 }
        request.report?.let { if (collidesWithOperand(it, sourceOp.path, targetOp.path)) return 2 }

        cancellationToken.throwIfCancellationRequested()

        // 3. Load + normalize + validate both
        val sourceResolved = loadFileOperand(sourceOp, request.source) ?: return 7
        val targetResolved = loadFileOperand(targetOp, request.target) ?: return 7

        val (sourceNormalized, targetNormalized) = try {
            normalizer(sourceResolved) to normalizer(targetResolved)
        } catch (e: IllegalStateException) {
            userFacingPrintError("Invalid reverse marker: ${e.message}", request.source)
            return 7
        }

        if (!sourceNormalized.validation.isValid || !targetNormalized.validation.isValid) {
            return emitValidationFailure(request, sourceNormalized, targetNormalized)
        }

        // 4. Resolve renderer
        val renderer = rendererFor(request.dialect)
        if (renderer == null) {
            userFacingPrintError("No renderer registered for dialect ${request.dialect.name}", request.source)
            return 2
        }

        cancellationToken.throwIfCancellationRequested()

        // 5. Pipeline: compare → plan → render
        val diff = comparator(targetNormalized.schema, sourceNormalized.schema)
        val plan = planner.plan(targetNormalized.schema, sourceNormalized.schema, diff)
        cancellationToken.throwIfCancellationRequested()
        val rendered = renderer.generateUp(plan, DdlGenerationOptions())

        // 6. Block on destructive without --allow-destructive
        val effective = applyDestructiveGuard(rendered, request.allowDestructive)

        // 7. Build report
        val report = buildReport(request, sourceResolved, targetResolved, plan, effective)

        // 8. Decide outcome
        return finalize(request, effective, report)
    }

    private fun validateRequest(request: SchemaMigrateRequest): Int? {
        if (request.execute) {
            userFacingPrintError("--execute is not supported in E.1; remove the flag.", request.source)
            return 2
        }
        if (request.generateRollback) {
            userFacingPrintError("--generate-rollback is not supported in E.1; lands in E.3.", request.source)
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

    private fun loadFileOperand(operand: CompareOperand.File, rawRef: String): ResolvedSchemaOperand? {
        return try {
            fileLoader(operand)
        } catch (e: Exception) {
            userFacingPrintError("Failed to read schema file: ${e.message}", rawRef)
            null
        }
    }

    private fun emitValidationFailure(
        request: SchemaMigrateRequest,
        source: ResolvedSchemaOperand,
        target: ResolvedSchemaOperand,
    ): Int {
        for (e in source.validation.errors) userFacingStderr("source: ${e.code} ${e.message}")
        for (e in target.validation.errors) userFacingStderr("target: ${e.code} ${e.message}")
        val report = SchemaMigrateReport(
            status = "validation_failed",
            exitCode = 3,
            source = source.reference,
            target = target.reference,
            dialect = request.dialect.name,
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
            dialect = request.dialect.name,
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
    val dialect: DatabaseDialect,
    val output: Path? = null,
    val report: Path? = null,
    val rollbackOutput: Path? = null,
    val reportFormat: String = "json",
    val planOnly: Boolean = false,
    val allowDestructive: Boolean = false,
    val generateRollback: Boolean = false,
    val execute: Boolean = false,
    val dryRun: Boolean = false,
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
