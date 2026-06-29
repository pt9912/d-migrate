package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.referencesGeometryColumn
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.ExtensionDependencyReport
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/** Rendering direction. */
internal enum class SqliteRenderDirection { UP, DOWN }

/** Mutable accumulator for one SQLite renderer invocation. Mirrors the PG / MySQL contexts. */
internal class SqliteDiffRenderContext(
    val direction: SqliteRenderDirection,
    val sql: SqliteDiffSqlBuilders,
    val options: DdlGenerationOptions,
    internal val currentSchema: SchemaDefinition? = null,
    internal val desiredSchema: SchemaDefinition? = null,
) {
    private val statements = mutableListOf<MigrationDdlStatement>()
    private val rendered = mutableSetOf<String>()

    /**
     * 0.9.7 Phase F2: `SqliteDiffSequenceOps` flips this to `true`
     * once the first `CREATE TABLE IF NOT EXISTS dmg_sequences`
     * bootstrap has been emitted in the current direction, so
     * subsequent `CreateSequence` / `DropSequence` (DOWN) ops don't
     * re-emit the bootstrap statement.
     */
    var bootstrapEmitted: Boolean = false

    /**
     * VA4/5d Befund 1: flips to `true` once the SpatiaLite metadata bootstrap
     * (`SELECT CASE WHEN CheckSpatialMetaData() = 0 THEN InitSpatialMetaData() END;`)
     * has been emitted in the current (UP) direction, so it is emitted at most once
     * before the first `AddGeometryColumn`. Separate from [bootstrapEmitted] (the
     * `dmg_sequences` helper) — the two bootstraps are independent.
     */
    var spatialMetadataBootstrapEmitted: Boolean = false
    private val skipped = mutableSetOf<String>()
    private val manualActions = mutableSetOf<String>()
    private val destructive = mutableSetOf<String>()
    private val nonReversible = mutableSetOf<String>()
    private val blockers = mutableListOf<MigrationBlocker>()
    private val diagnostics = mutableListOf<DiffDiagnostic>()
    private val extensionDependencies = linkedMapOf<String, ExtensionDependencyAccumulator>()
    private val sqliteCastPreflights = linkedMapOf<String, SqliteCastPreflightDeclaration>()

    fun emit(op: DiffOperation, sqlText: String) {
        // E.2 Sub-Slice A.3 strict-mode lift: mirrors the
        // PostgresDiffRenderContext / MysqlDiffRenderContext guard.
        // When the active-direction risk has `hasGap = true` and the
        // operator set `strictGapOperations`, block before emitting any
        // statement for this op. Subsequent emit() calls for the same
        // op short-circuit on `isSkipped`.
        if (options.strictGapOperations && riskFor(op).hasGap) {
            if (!isSkipped(op)) {
                skip(
                    op,
                    "Operation ${op.id} renders with a visibility gap (`hasGap = true`) and " +
                        "`--strict-gap-operations` is set. The operator must split the change into a " +
                        "manual maintenance window or accept the gap by removing the strict flag.",
                    code = "OPERATION_HAS_GAP_STRICT_BLOCKED",
                )
                addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            }
            return
        }
        // Plan-2 §A.1: direct SQLite DDL via this path is wrapped by
        // the runner's outer JDBC transaction; SQLite rolls back the
        // statement on failure. Lock footprint is the schema's
        // exclusive write-lock until commit.
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = setOf(op.id),
            risk = riskFor(op),
            phase = op.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
            hints = SQLITE_DIRECT_DDL_HINTS,
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == SqliteRenderDirection.UP) {
            op.risks.up
        } else {
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "SQLITE_RENDER_SKIP") {
        skipped += op.id
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = op.id,
        )
    }

    /**
     * Whether [op] is already in the skipped set — used by multi-
     * statement helpers (E.2 Sub-Slice C ReplaceTrigger Drop+Create)
     * to suppress trailing diagnostics when the strict-gap guard
     * short-circuited the first `emit()` call.
     */
    fun isSkipped(op: DiffOperation): Boolean = op.id in skipped

    /**
     * Annotate an op with a WARNING-level diagnostic. Mirrors the
     * `MysqlDiffRenderContext.warning(...)` convenience so dialect
     * helpers can share the same call site.
     */
    fun warning(op: DiffOperation, message: String, code: String) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = op.id,
        )
    }

    /**
     * Mark an operation as deferred to the future RebuildTable
     * pipeline (D.4.b). Does not emit DDL; surfaces a
     * `MANUAL_ACTION_REQUIRED` blocker referring to Plan §6.4.
     */
    fun deferToRebuild(op: DiffOperation) {
        skip(
            op,
            "SQLite cannot ALTER this aspect of column/constraint without a full table rebuild. " +
                "The D.4.a renderer surfaces this as MANUAL_ACTION_REQUIRED; the rebuild " +
                "pipeline lands in D.4.b (Plan §6.4).",
            code = "SQLITE_REBUILD_REQUIRED",
        )
        addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    fun requireExtension(op: DiffOperation, extension: String, detail: String): Boolean {
        val status = extensionStatus(extension)
        recordExtensionDependency(extension, status, op.id)
        return when (status) {
            ExtensionAvailabilityStatus.VERIFIED_PRESENT -> {
                addDiagnostic(
                    DiffDiagnostic(
                        code = "EXTENSION_DEPENDENCY_VERIFIED",
                        message = "Operation ${op.id} requires SQLite extension '$extension' for $detail; " +
                            "target availability is verified.",
                        severity = DiffDiagnostic.Severity.INFO,
                        operationId = op.id,
                    ),
                )
                true
            }
            ExtensionAvailabilityStatus.MISSING -> {
                skip(
                    op,
                    "Operation ${op.id} requires SQLite extension '$extension' for $detail, " +
                        "but target availability is declared MISSING.",
                    code = "EXTENSION_DEPENDENCY_MISSING",
                )
                addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
                false
            }
            ExtensionAvailabilityStatus.UNKNOWN -> {
                skip(
                    op,
                    "Operation ${op.id} requires SQLite extension '$extension' for $detail, " +
                        "but target availability is not verified.",
                    code = "EXTENSION_DEPENDENCY_UNKNOWN",
                )
                addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
                false
            }
        }
    }

    private fun extensionStatus(extension: String): ExtensionAvailabilityStatus =
        options.extensionAvailability.firstOrNull { declaration ->
            declaration.dialect.equals("sqlite", ignoreCase = true) &&
                declaration.extension.equals(extension, ignoreCase = true)
        }?.status ?: ExtensionAvailabilityStatus.UNKNOWN

    private fun recordExtensionDependency(
        extension: String,
        status: ExtensionAvailabilityStatus,
        operationId: String,
    ) {
        val key = extension.lowercase()
        val existing = extensionDependencies[key]
        if (existing == null) {
            extensionDependencies[key] = ExtensionDependencyAccumulator(extension, status, mutableSetOf(operationId))
        } else {
            existing.operationIds += operationId
        }
    }

    fun indexTouchesGeometry(table: String, index: IndexDefinition): Boolean {
        val schema = if (direction == SqliteRenderDirection.UP) desiredSchema else currentSchema
        val columns = schema?.tables?.get(table)?.columns.orEmpty()
        return index.referencesGeometryColumn { columns[it]?.type }
    }

    /**
     * VA4: erste Geometriespalte, die [index] indiziert (für `CreateSpatialIndex`,
     * das genau eine Geometriespalte adressiert), oder null. Schema-Auswahl wie
     * [indexTouchesGeometry] (gewünschtes Schema UP, aktuelles DOWN).
     */
    fun geometryIndexColumn(table: String, index: IndexDefinition): String? {
        val schema = if (direction == SqliteRenderDirection.UP) desiredSchema else currentSchema
        val columns = schema?.tables?.get(table)?.columns.orEmpty()
        return index.columnNames.firstOrNull { name -> columns[name]?.type is NeutralType.Geometry }
    }

    /**
     * Emits a single statement attached to a *set* of operation IDs.
     * Used by the RebuildTable pipeline where one rebuild covers
     * multiple business operations on the same table.
     *
     * The [risk] is supplied per statement so that bookkeeping
     * statements (PRAGMAs, BEGIN/COMMIT) can be tagged SAFE while
     * destructive steps (DROP TABLE, INSERT-SELECT) carry the
     * bucket-derived risk projection.
     *
     * The [phase] follows the §4.4 phase ordering: PRAGMA wrapping
     * and BEGIN sit in PREPARE; CREATE/INSERT/DROP/RENAME in TABLES;
     * recreated indices in INDEXES; the closing PRAGMA/COMMIT in
     * CLEANUP. This lets dry-run / staged-execute filters address
     * sub-ranges of the rebuild without string-matching SQL.
     */
    fun emitRebuildStatement(
        sqlText: String,
        opIds: Set<String>,
        risk: OperationRisk = OperationRisk.SAFE,
        phase: DiffPhase = DiffPhase.TABLES,
        hints: DialectExecutionHints = SQLITE_REBUILD_INSIDE_TX_HINTS,
    ) {
        // Plan-2 §G.1: the SQLite rebuild pipeline emits its own
        // BEGIN IMMEDIATE / COMMIT bracket plus pre-/post-PRAGMAs.
        // The whole rebuild group is dispatched as STREAM_OWNED so
        // the executor leaves autoCommit untouched. Per-statement
        // boundary refinement (PRAGMA vs. inside-tx vs. COMMIT) is
        // tracked as Plan-2 §G.3 (`transactionBoundary`).
        //
        // Plan-2 §A.1: the default `hints` describe a statement
        // inside the BEGIN/COMMIT bracket (FULLY_TRANSACTIONAL +
        // TABLE_EXCLUSIVE). Callers emitting PRAGMAs or runner-hook
        // markers outside the bracket override via
        // [SQLITE_REBUILD_OUTSIDE_TX_HINTS].
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = opIds,
            risk = risk,
            phase = phase,
            transactionScope = TransactionScope.STREAM_OWNED,
            hints = hints,
        )
    }

    /** Mark an op as rendered without emitting a separate statement (rebuild absorbs it). */
    fun markRendered(op: DiffOperation) {
        rendered += op.id
    }

    /**
     * Project an `OperationRisk` summary across a rebuild bucket: any
     * op that is destructive / lossy / requires confirmation in the
     * current direction propagates that flag to the bucket-level risk.
     * `requiresTableRewrite` is always set for a rebuild — that's the
     * defining characteristic of the bucket.
     */
    fun bucketRisk(bucket: List<DiffOperation>): OperationRisk {
        var destructive = false
        var dataLossPossible = false
        var requiresManualConfirmation = false
        for (op in bucket) {
            val r = riskFor(op)
            if (r.destructive) destructive = true
            if (r.dataLossPossible) dataLossPossible = true
            if (r.requiresManualConfirmation) requiresManualConfirmation = true
        }
        return OperationRisk(
            destructive = destructive,
            dataLossPossible = dataLossPossible,
            requiresTableRewrite = true,
            requiresManualConfirmation = requiresManualConfirmation,
        )
    }

    /**
     * Reflect the bucket's projected risk into the context-level
     * tracking sets. Called after [bucketRisk] has been computed and
     * the rebuild statements emitted.
     */
    fun applyBucketRisk(opIds: Set<String>, risk: OperationRisk) {
        if (risk.destructive) destructive += opIds
        if (risk.requiresManualConfirmation) manualActions += opIds
    }

    fun addDiagnostic(d: DiffDiagnostic) {
        diagnostics += d
    }

    fun recordSqliteCastPreflight(declaration: SqliteCastPreflightDeclaration) {
        sqliteCastPreflights[declaration.bindingKey] = declaration
    }

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        // F.4 Renderer-Blocker-Bridge (2026-05-19): see
        // `PostgresDiffRenderContext.toResult` for the shared contract
        // — every planner-emitted BLOCKER diagnostic is classified via
        // `PlannerBlockerClassifier.classify(diag.code)` and grouped
        // into one MigrationBlocker per reason.
        val effectiveBlockers = if (plannerBlockers.isEmpty()) {
            blockers
        } else {
            blockers + plannerBlockers
                .groupBy { PlannerBlockerClassifier.classify(it.code) }
                .map { (reason, diags) -> MigrationBlocker(reason = reason, diagnostics = diags) }
        }
        val primary = effectiveBlockers.firstOrNull()?.reason
        val requiresConfirmation = manualActions.isNotEmpty() || destructive.isNotEmpty()
        return MigrationDdlResult(
            statements = statements,
            operationsRendered = rendered,
            operationsSkipped = skipped,
            manualActions = manualActions,
            destructiveOperations = destructive,
            nonReversibleOperations = nonReversible,
            requiresConfirmation = requiresConfirmation,
            blockers = effectiveBlockers,
            primaryBlockedReason = primary,
            diagnostics = combinedDiagnostics,
            spatialProfile = options.spatialProfile.name,
            sqliteCastPreflights = sqliteCastPreflights.values.toList(),
            extensionDependencies = extensionDependencies.values.map { dep ->
                ExtensionDependencyReport(
                    dialect = "sqlite",
                    extension = dep.extension,
                    status = dep.status,
                    operationIds = dep.operationIds.toSet(),
                    installStatement = null,
                )
            },
        )
    }

    private data class ExtensionDependencyAccumulator(
        val extension: String,
        val status: ExtensionAvailabilityStatus,
        val operationIds: MutableSet<String>,
    )

    companion object {
        internal val SQLITE_DIRECT_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = true,
        )

        /**
         * Default hints for rebuild statements emitted *between*
         * `BEGIN IMMEDIATE;` and `COMMIT;` that perform actual DDL
         * (CREATE temp, INSERT-SELECT, DROP, RENAME, DROP/CREATE
         * dependent triggers/views, CREATE INDEX). The BEGIN/COMMIT
         * markers themselves and `PRAGMA foreign_key_check;` use
         * [SQLITE_TX_MARKER_HINTS] — they're transaction-control or
         * read-only checks, not table-locking DDL. Plan-2 §A.1.
         */
        internal val SQLITE_REBUILD_INSIDE_TX_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = true,
        )

        /**
         * Hints for rebuild statements that run *inside* the
         * `BEGIN IMMEDIATE;` … `COMMIT;` bracket but are not body
         * DDL — i.e. the BEGIN/COMMIT markers themselves,
         * `PRAGMA foreign_key_check;` (read-only integrity check),
         * and runner-hook markers like
         * `-- dmigrate:runner-hook=assert-foreign-keys-clean` that
         * SQLite never sees because the d-migrate runner intercepts
         * them. They participate in the transactional contract
         * (FULLY_TRANSACTIONAL) but don't take a table-exclusive
         * lock and don't require exclusive access. Plan-2 §A.1.
         */
        internal val SQLITE_TX_MARKER_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.NONE,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = false,
        )

        /**
         * Hints for rebuild statements emitted *outside* the
         * BEGIN/COMMIT bracket: pre-BEGIN `PRAGMA foreign_keys = OFF`
         * + save-fk-state hook, post-COMMIT `PRAGMA foreign_keys = ON`
         * / restore-fk-state hook. Run under autocommit; SQLite makes
         * no transactional guarantee for them. Plan-2 §A.1.
         */
        internal val SQLITE_REBUILD_OUTSIDE_TX_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.NOT_TRANSACTIONAL,
            lockBehavior = LockBehavior.NONE,
            implicitCommitPossible = false,
            sideEffectsPossible = true,
            requiresExclusiveAccess = false,
        )
    }
}
