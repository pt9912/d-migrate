package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.ExtensionAvailabilityStatus
import dev.dmigrate.driver.ExtensionDependencyReport
import dev.dmigrate.driver.ExtensionInstallPolicy
import dev.dmigrate.driver.ExtensionInstallPrivilegeStatus
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Rendering direction. Up emits the migration as planned; Down
 * walks the topo-sort in reverse and applies inverse semantics.
 */
internal enum class PostgresRenderDirection { UP, DOWN }

/**
 * Mutable accumulator for one renderer invocation. Owns the
 * statement list and the four bookkeeping sets (rendered, skipped,
 * destructive, non-reversible) plus blocker / diagnostic ledgers,
 * and projects the per-op risk according to the rendering direction.
 */
internal class PostgresDiffRenderContext(
    val direction: PostgresRenderDirection,
    val sql: PostgresDiffSqlBuilders,
    val options: DdlGenerationOptions,
    val migrationOverlays: List<MigrationOverlayDocument> = emptyList(),
    val sourceFingerprint: String? = null,
    val targetFingerprint: String? = null,
    private val currentSchema: SchemaDefinition? = null,
    private val desiredSchema: SchemaDefinition? = null,
) {
    private val statements = mutableListOf<MigrationDdlStatement>()
    private val rendered = mutableSetOf<String>()
    private val skipped = mutableSetOf<String>()
    private val manualActions = mutableSetOf<String>()
    private val destructive = mutableSetOf<String>()
    private val nonReversible = mutableSetOf<String>()
    private val blockers = mutableListOf<MigrationBlocker>()
    private val diagnostics = mutableListOf<DiffDiagnostic>()
    private val extensionDependencies = linkedMapOf<String, ExtensionDependencyAccumulator>()
    private val plannedExtensionInstalls = mutableSetOf<String>()

    fun emit(
        op: DiffOperation,
        sqlText: String,
        hints: DialectExecutionHints = POSTGRES_TRANSACTIONAL_DDL_HINTS,
    ) {
        // E.2 Sub-Slice A.3 strict-mode lift: when the active-direction
        // risk has `hasGap = true` and the operator asked for strict
        // gap handling, block before emitting any statement for this
        // operation. The first call to emit() for a multi-statement
        // op trips the guard and registers skip+blocker; subsequent
        // calls for the same op short-circuit on the `isSkipped`
        // check so no further statements leak through and no second
        // blocker is recorded.
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
        // Plan-2 §A.1: PostgreSQL DDL is fully transactional. The
        // default `hints` cover the lock-heavy ALTER TABLE / DROP
        // TABLE / CREATE TABLE / DROP INDEX / constraint paths
        // (TABLE_EXCLUSIVE + requiresExclusiveAccess). Callers
        // emitting lighter-lock DDL override:
        //   - CREATE INDEX (non-CONCURRENTLY) → SHARE lock →
        //     [POSTGRES_CREATE_INDEX_HINTS]
        //   - CREATE/DROP TYPE, CREATE/DROP/REPLACE VIEW →
        //     pg_type / view catalog only →
        //     [POSTGRES_METADATA_HINTS]
        // CREATE INDEX CONCURRENTLY (TransactionScope.NO_TRANSACTION +
        // NOT_TRANSACTIONAL) is not yet rendered by this adapter.
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = setOf(op.id),
            risk = riskFor(op),
            phase = op.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
            hints = hints,
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == PostgresRenderDirection.UP) {
            op.risks.up
        } else {
            // NOT_REVERSIBLE / MANUAL_REQUIRED ops should be short-circuited by the
            // dispatcher before reaching emit(), so a null down-risk here is a contract
            // violation by the caller. Loud failure beats silent SAFE.
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "POSTGRES_RENDER_SKIP") {
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
     * statement helpers (E.2 Sub-Slice A.3 ReplaceTrigger Drop+Create)
     * to suppress trailing diagnostics when the strict-gap guard short-
     * circuited the first `emit()` call.
     */
    fun isSkipped(op: DiffOperation): Boolean = op.id in skipped

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    fun addInfoDiagnostic(code: String, operationId: String, message: String) {
        addDiagnostic(code, operationId, message, DiffDiagnostic.Severity.INFO)
    }

    /**
     * Annotate an op with a WARNING-level diagnostic. Mirrors
     * `MysqlDiffRenderContext.warning(...)` so dialect helpers share
     * one warning call site instead of three styles.
     */
    fun warning(op: DiffOperation, message: String, code: String) {
        addDiagnostic(code = code, operationId = op.id, message = message, severity = DiffDiagnostic.Severity.WARNING)
    }

    fun addDiagnostic(
        code: String,
        operationId: String,
        message: String,
        severity: DiffDiagnostic.Severity = DiffDiagnostic.Severity.BLOCKER,
    ) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = severity,
            operationId = operationId,
        )
    }

    fun requireExtension(op: DiffOperation, extension: String, detail: String): Boolean {
        val status = extensionStatus(extension)
        recordExtensionDependency(extension, status, op.id)
        return when (status) {
            ExtensionAvailabilityStatus.VERIFIED_PRESENT -> {
                addInfoDiagnostic(
                    code = "EXTENSION_DEPENDENCY_VERIFIED",
                    operationId = op.id,
                    message = "Operation ${op.id} requires PostgreSQL extension '$extension' for $detail; " +
                        "target availability is verified.",
                )
                true
            }
            ExtensionAvailabilityStatus.MISSING -> {
                if (options.extensionInstallPolicy == ExtensionInstallPolicy.ALLOW_CREATE_IF_MISSING) {
                    return planExtensionInstall(op, extension, detail, status)
                }
                skip(
                    op,
                    "Operation ${op.id} requires PostgreSQL extension '$extension' for $detail, " +
                        "but target availability is declared MISSING.",
                    code = "EXTENSION_DEPENDENCY_MISSING",
                )
                addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
                false
            }
            ExtensionAvailabilityStatus.UNKNOWN -> {
                if (options.extensionInstallPolicy == ExtensionInstallPolicy.ALLOW_CREATE_IF_MISSING) {
                    return planExtensionInstall(op, extension, detail, status)
                }
                skip(
                    op,
                    "Operation ${op.id} requires PostgreSQL extension '$extension' for $detail, " +
                        "but target availability is not verified.",
                    code = "EXTENSION_DEPENDENCY_UNKNOWN",
                )
                addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
                false
            }
        }
    }

    private fun planExtensionInstall(
        op: DiffOperation,
        extension: String,
        detail: String,
        status: ExtensionAvailabilityStatus,
    ): Boolean {
        if (options.extensionInstallPrivilegeStatus == ExtensionInstallPrivilegeStatus.MISSING) {
            skip(
                op,
                "Operation ${op.id} requires PostgreSQL extension '$extension' for $detail, " +
                    "but CREATE EXTENSION privileges are declared MISSING.",
                code = "EXTENSION_INSTALL_PRIVILEGE_MISSING",
            )
            addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }

        val sqlText = createExtensionSql(extension)
        extensionDependencies[extension.lowercase()]?.installStatement = sqlText
        if (plannedExtensionInstalls.add(extension.lowercase())) {
            statements += MigrationDdlStatement(
                sql = sqlText,
                operationIds = setOf(op.id),
                risk = OperationRisk(requiresManualConfirmation = true),
                phase = op.phase,
                transactionScope = TransactionScope.RUNNER_OWNED,
                hints = POSTGRES_EXTENSION_INSTALL_HINTS,
            )
            manualActions += op.id
        }
        addInfoDiagnostic(
            code = "EXTENSION_INSTALL_PLANNED",
            operationId = op.id,
            message = "Operation ${op.id} requires PostgreSQL extension '$extension' for $detail; " +
                "target availability is $status and extension installation was explicitly allowed.",
        )
        if (options.extensionInstallPrivilegeStatus == ExtensionInstallPrivilegeStatus.UNVERIFIED) {
            addDiagnostic(
                code = "EXTENSION_INSTALL_PRIVILEGE_UNVERIFIED",
                operationId = op.id,
                message = "Operation ${op.id} plans CREATE EXTENSION for '$extension', " +
                    "but CREATE EXTENSION privileges were not verified before render.",
                severity = DiffDiagnostic.Severity.WARNING,
            )
        }
        return true
    }

    private fun createExtensionSql(extension: String): String =
        "CREATE EXTENSION IF NOT EXISTS ${quotePostgresIdentifier(extension)};"

    private fun extensionStatus(extension: String): ExtensionAvailabilityStatus =
        options.extensionAvailability.firstOrNull { declaration ->
            declaration.dialect.equals("postgresql", ignoreCase = true) &&
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
        val schema = if (direction == PostgresRenderDirection.UP) desiredSchema else currentSchema
        val columns = schema?.tables?.get(table)?.columns.orEmpty()
        return index.columnNames.any { name -> columns[name]?.type is NeutralType.Geometry }
    }

    /**
     * ADR 0025: the `tsvector` column a FULLTEXT index expands to on PostgreSQL — the
     * recorded [IndexDefinition.fullTextVectorColumn], or (for a hand-authored index
     * without it) the table's sole `tsvector` ([NeutralType.FullText]) column. Null when
     * neither is available, in which case the index cannot be reconstructed.
     */
    fun fullTextVectorColumn(table: String, index: IndexDefinition): String? {
        index.fullTextVectorColumn?.let { return it }
        val schema = if (direction == PostgresRenderDirection.UP) desiredSchema else currentSchema
        val columns = schema?.tables?.get(table)?.columns.orEmpty()
        return columns.entries.singleOrNull { it.value.type is NeutralType.FullText }?.key
    }

    /**
     * I-08: first GIN/GIST-indexed column whose type has no default operator
     * class in PostgreSQL (e.g. a tsvector column degraded to text on reverse),
     * or null when the index is renderable. PG rejects `USING gist (text_col)`.
     */
    fun indexColumnMissingOpClass(table: String, index: IndexDefinition): String? {
        val schema = if (direction == PostgresRenderDirection.UP) desiredSchema else currentSchema
        val columns = schema?.tables?.get(table)?.columns.orEmpty()
        return PostgresIndexOpClass.missingOpClassColumn(index) { columns[it]?.type }
    }

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        // F.4 Renderer-Blocker-Bridge (2026-05-19): planner-emitted
        // BLOCKER diagnostics are grouped by their classified
        // `MigrationBlockedReason` and emitted as one MigrationBlocker
        // per reason — so F.4 Mapper/Planner blockers surface as
        // `primaryBlockedReason = OBJECT_RENAME_UNSUPPORTED` and the
        // legacy `CONSTRAINT_NOT_DIFFABLE` / `MATERIALIZED_VIEW_DIFF_UNSUPPORTED`
        // pathways keep their `DIALECT_UNSUPPORTED_OPERATION` reason.
        // A CLI consumer that reads only the `blockers` list still
        // sees *every* reason the plan can't run.
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
            extensionDependencies = extensionDependencies.values.map { dep ->
                ExtensionDependencyReport(
                    dialect = "postgresql",
                    extension = dep.extension,
                    status = dep.status,
                    operationIds = dep.operationIds.toSet(),
                    installStatement = dep.installStatement,
                )
            },
        )
    }

    private data class ExtensionDependencyAccumulator(
        val extension: String,
        val status: ExtensionAvailabilityStatus,
        val operationIds: MutableSet<String>,
        var installStatement: String? = null,
    )

    companion object {
        /**
         * Default PostgreSQL DDL hint: fully transactional under the
         * runner-owned tx, takes an exclusive table lock for the
         * statement's duration. Covers ALTER TABLE (incl. ADD/DROP
         * COLUMN, ALTER COLUMN, ADD/DROP CONSTRAINT, ADD/DROP PK),
         * CREATE TABLE, DROP TABLE, DROP INDEX.
         */
        internal val POSTGRES_TRANSACTIONAL_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = true,
        )

        /**
         * Hint for `CREATE INDEX` (non-CONCURRENTLY) on a regular
         * table: PostgreSQL takes a SHARE lock — writes block, reads
         * proceed. Honest LockBehavior is TABLE_SHARED;
         * `requiresExclusiveAccess` is false because concurrent
         * readers are fine.
         */
        internal val POSTGRES_CREATE_INDEX_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_SHARED,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = false,
        )

        internal val POSTGRES_EXTENSION_INSTALL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.METADATA,
            implicitCommitPossible = false,
            sideEffectsPossible = true,
            requiresExclusiveAccess = false,
        )

        /**
         * Hint for catalog-only operations that don't touch user
         * tables: CREATE/DROP TYPE (writes `pg_type`), CREATE/DROP/
         * CREATE OR REPLACE VIEW (writes the view's `pg_class` row;
         * takes AccessShareLock on referenced relations during
         * planning, not exclusive). LockBehavior is METADATA;
         * `requiresExclusiveAccess` is false because no user-data
         * table is locked.
         */
        internal val POSTGRES_METADATA_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.METADATA,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = false,
        )
    }
}
