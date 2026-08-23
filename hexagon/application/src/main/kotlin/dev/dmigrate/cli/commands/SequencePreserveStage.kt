package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceSupportNaming
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCapability
import dev.dmigrate.driver.SequenceCapabilityDefaults
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest

/**
 * Atomic-Preserve Phase C.1 (2026-06-01): rewrites the heutige Probe-
 * in-Stage path onto the atomic Probe + Restore window.
 *
 * Stage is the **input layer** for the atomic-preserve pipeline:
 *
 * 1. **Candidate filter (§6.4.1)** — same as the 0.9.7 path:
 *    `CreateSequence` (with `renameProvenance`), `AlterSequence`
 *    (either side opts in), `RenameSequence` (source opts in).
 *    `DropSequence` stays excluded.
 * 2. **Skip paths** — file-target, no-execute, dialect outside the
 *    allowlist, SQLite without `--sqlite-named-sequences helper_table`,
 *    no candidates. Identical surface to the heutige Implementierung.
 * 3. **Capability gate** — every candidate's kind name must appear in
 *    the dialect's `SequenceCapability.transactionalProtectedSequenceOperations`
 *    set (populated by Phase C.4 wiring). A kind outside the allowlist
 *    blocks per candidate with `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`
 *    — no silent fall-through to the heutige Pfad.
 * 4. **Batch build** — Stage emits an [AtomicSequencePreserveBatch]
 *    with one [AtomicSequencePreserveRequest] per candidate; the
 *    `renderRestore` closure delegates to [AtomicPreserveRestoreSql]
 *    for dialect-specific SQL. The closure runs **inside** the lock
 *    at execute time; Stage itself opens no JDBC connection.
 * 5. **Plan augmentation (§6.4.6)** — the [DiffOperation.AlterSequenceCurrentValue]
 *    follow-ups stay in the augmented plan as audit markers so
 *    plan-only / report / rollback artefacts continue to surface the
 *    preserve intent. They carry [ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE][
 *    DiffOperation.AlterSequenceCurrentValue.Companion.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE]
 *    rather than a probed value — the live-execute path filters them
 *    out via `internalFollowUpIds` (see `SegmentAwareMigrationExecutor`).
 *
 * **Restrictions** *(not addressed by this stage)*:
 *
 * - Multi-sequence plans on a dialect where
 *   `SequenceCapability.supportsAtomicPreserveAllInPlan == false` are
 *   handled by a separate execution-stage gate (Phase D) — this stage
 *   builds the batch unconditionally, the gate downstream decides
 *   whether to run it.
 * - Cross-database locks, App-side retry/backpressure and a global
 *   schema-lock are permanently out-of-scope; see the plan-doc §3.2
 *   for the full carve-out list.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.1 (re-cut ausführungs-letzter aktivierender
 * Commit); §3.2 for out-of-scope, §5 Phase D for the AllInPlan-Flag.
 */
object SequencePreserveStage {

    /** Codes the stage emits; classifier mapping in [PlannerBlockerClassifier]. */
    private const val CONFIG_INVALID_CODE = PlannerBlockerClassifier.SEQUENCE_PRESERVE_CONFIG_INVALID_CODE
    private const val REQUIRES_DB_TARGET_CODE = PlannerBlockerClassifier.SEQUENCE_PRESERVE_REQUIRES_DB_TARGET_CODE
    private const val NOT_SUPPORTED_BY_DIALECT_CODE =
        PlannerBlockerClassifier.SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT_CODE
    private const val OPT_IN_REQUIRED_CODE =
        PlannerBlockerClassifier.SEQUENCE_PRESERVE_OPT_IN_REQUIRED_CODE
    private const val ATOMIC_UNSUPPORTED_CODE =
        PlannerBlockerClassifier.SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED_CODE

    /**
     * Three-state outcome of the stage. The render pipeline consumes
     * this analog to [MysqlSequenceCanonicityStage.Outcome].
     *
     * - [Succeeded] carries the augmented [DiffResult], the
     *   [AtomicSequencePreserveBatch] the execute-stage hands to the
     *   atomic runner, and INFO diagnostics. The batch is **never**
     *   empty when [Succeeded] fires — empty-candidate plans return
     *   [NotRun] instead.
     * - [Failed] carries blocker diagnostics + the unchanged plan;
     *   the render pipeline short-circuits to `buildFailureResult`.
     * - [NotRun] means none of the gating conditions applied (no
     *   candidates, file target with empty candidates, `!execute`,
     *   etc.); the render pipeline proceeds with the original plan
     *   and no atomic batch.
     */
    sealed interface Outcome {
        data class Succeeded(
            val augmentedPlan: DiffResult,
            val atomicBatch: AtomicSequencePreserveBatch,
            val infoDiagnostics: List<DiffDiagnostic>,
        ) : Outcome
        data class Failed(
            val diagnostics: List<DiffDiagnostic>,
            val plan: DiffResult,
        ) : Outcome
        data object NotRun : Outcome
    }

    fun run(
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
        capabilityResolver: (DatabaseDialect) -> SequenceCapability = SequenceCapabilityDefaults::forDialect,
    ): Outcome {
        val candidates = collectCandidates(plan, dialect)
        if (target !is CompareOperand.Database) {
            return if (candidates.isEmpty()) Outcome.NotRun else requiresDbTargetBlocker(candidates, plan)
        }
        if (!request.execute) return Outcome.NotRun
        if (dialect !in PRESERVE_DIALECTS) {
            return blockUnsupportedDialect(plan, dialect)
        }
        if (dialect == DatabaseDialect.SQLITE && candidates.isNotEmpty()) {
            val mode = request.sqliteNamedSequences?.let(SqliteNamedSequenceMode::fromCliName)
            if (mode != SqliteNamedSequenceMode.HELPER_TABLE) {
                return sqliteOptInRequiredBlocker(candidates, plan)
            }
        }
        if (candidates.isEmpty()) return Outcome.NotRun
        configInvalidIfMysqlNeedsIt(candidates, plan)?.let { return it }

        // C.1 capability gate: every candidate kind MUST be in the
        // dialect's transactionalProtectedSequenceOperations allowlist
        // (Phase C.4 wires PG/MySQL/SQLite to CreateSequence /
        // AlterSequence / RenameSequence). Anything else surfaces the
        // SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED blocker — no fall-through
        // to a non-atomic path (Lesart α, no carve-outs).
        val capability = capabilityResolver(dialect)
        unsupportedKindsBlocker(candidates, capability, plan)?.let { return it }

        // Phase D gate: a dialect whose
        // `supportsAtomicPreserveAllInPlan == false` cannot hold the
        // lock across multiple sequences in the same plan. Plan §5
        // Phase D pins this gate so a future dialect that lands
        // single-sequence atomic-preserve before its cross-plan
        // deadlock proof still surfaces a structured blocker for
        // multi-sequence plans. PG/MySQL/SQLite all default to
        // `true` after Phase D landed (2026-06-01).
        allInPlanBlocker(candidates, capability, plan)?.let { return it }

        return buildBatch(candidates, dialect, plan)
    }

    /**
     * §6.4.3 priority blocker: file target + preserve-candidates ⇒
     * the atomic executor cannot run, so block per candidate with
     * `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET`.
     */
    private fun requiresDbTargetBlocker(candidates: List<Candidate>, plan: DiffResult): Outcome.Failed {
        val diagnostics = candidates.map { ctx ->
            DiffDiagnostic(
                code = REQUIRES_DB_TARGET_CODE,
                message = "preserveCurrentValue on ${ctx.parentOp::class.simpleName} " +
                    "`${ctx.applyRef.name}` requires a live database target — " +
                    "rerun with --execute against a DB source/target.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = ctx.parentOp.id,
            )
        }
        return Outcome.Failed(diagnostics = diagnostics, plan = plan)
    }

    private fun sqliteOptInRequiredBlocker(
        candidates: List<Candidate>,
        plan: DiffResult,
    ): Outcome.Failed {
        val diagnostics = candidates.map { ctx ->
            DiffDiagnostic(
                code = OPT_IN_REQUIRED_CODE,
                message = "preserveCurrentValue on ${ctx.parentOp::class.simpleName} " +
                    "`${ctx.applyRef.name}` requires SQLite's helper-table emulation; " +
                    "rerun with `--sqlite-named-sequences helper_table` to opt in.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = ctx.parentOp.id,
            )
        }
        return Outcome.Failed(diagnostics = diagnostics, plan = plan)
    }

    /**
     * Phase D gate: if a plan carries ≥ 2 preserve candidates and the
     * dialect's `supportsAtomicPreserveAllInPlan` is `false`, block
     * every candidate with `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`.
     *
     * The gate runs **after** [unsupportedKindsBlocker] so a kind-
     * mismatch always surfaces first (more actionable diagnostic).
     * Single-candidate plans bypass this gate even when the flag is
     * `false` — the lock window holds across one sequence by
     * construction in every supported dialect.
     */
    private fun allInPlanBlocker(
        candidates: List<Candidate>,
        capability: SequenceCapability,
        plan: DiffResult,
    ): Outcome.Failed? {
        if (candidates.size < 2) return null
        if (capability.supportsAtomicPreserveAllInPlan) return null
        val diagnostics = candidates.map { ctx ->
            DiffDiagnostic(
                code = ATOMIC_UNSUPPORTED_CODE,
                message = "preserveCurrentValue on ${ctx.parentOp::class.simpleName} " +
                    "`${ctx.applyRef.name}` cannot run in a multi-sequence plan: the " +
                    "dialect's atomic-preserve runner does not yet hold the lock across " +
                    "every preserve candidate in one plan (supportsAtomicPreserveAllInPlan = false).",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = ctx.parentOp.id,
            )
        }
        return Outcome.Failed(diagnostics = diagnostics, plan = plan)
    }

    private fun unsupportedKindsBlocker(
        candidates: List<Candidate>,
        capability: SequenceCapability,
        plan: DiffResult,
    ): Outcome.Failed? {
        val allowlist: Set<String> = capability.transactionalProtectedSequenceOperations
            .map { it.value }
            .toSet()
        val unsupported = candidates.filter { ctx ->
            ctx.parentOp::class.simpleName !in allowlist
        }
        if (unsupported.isEmpty()) return null
        val diagnostics = unsupported.map { ctx ->
            DiffDiagnostic(
                code = ATOMIC_UNSUPPORTED_CODE,
                message = "preserveCurrentValue on ${ctx.parentOp::class.simpleName} " +
                    "`${ctx.applyRef.name}` is not in the dialect's atomic-preserve " +
                    "allowlist (${capability.transactionalProtectedSequenceOperations.joinToString { it.value }}); " +
                    "operation kind cannot run inside the atomic transaction.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = ctx.parentOp.id,
            )
        }
        return Outcome.Failed(diagnostics = diagnostics, plan = plan)
    }

    fun buildFailureResult(diagnostics: List<DiffDiagnostic>): MigrationDdlResult {
        val blockers = diagnostics
            .filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
            .groupBy { PlannerBlockerClassifier.classify(it.code) }
            .map { (reason, group) ->
                MigrationBlocker(reason = reason, diagnostics = group)
            }
        val primary = blockers.firstOrNull()?.reason ?: MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = blockers,
            primaryBlockedReason = primary,
            diagnostics = diagnostics,
        )
    }

    // ── Candidate filter (§6.4.1) ──────────────────────────────────────

    private data class Candidate(
        val parentOp: DiffOperation,
        val probeRef: SequenceObjectRef,
        val applyRef: SequenceObjectRef,
        val pairId: String,
        val revertAfterRename: Boolean,
    )

    private fun collectCandidates(plan: DiffResult, dialect: DatabaseDialect): List<Candidate> {
        val coreDialect = toCoreDialect(dialect)
        return plan.operations.mapNotNull { op -> classifyCandidate(op, plan, coreDialect) }
    }

    private fun classifyCandidate(
        op: DiffOperation,
        plan: DiffResult,
        coreDialect: RenameProjectionDialect,
    ): Candidate? = when (op) {
        is DiffOperation.CreateSequence -> classifyCreate(op, coreDialect)
        is DiffOperation.AlterSequence -> classifyAlter(op, coreDialect)
        is DiffOperation.RenameSequence -> classifyRename(op, plan, coreDialect)
        else -> null
    }

    private fun classifyCreate(
        op: DiffOperation.CreateSequence,
        coreDialect: RenameProjectionDialect,
    ): Candidate? {
        if (!op.sequence.preserveCurrentValue) return null
        if (op.renameProvenance == null) return null
        val ref = SequenceObjectRef(op.objectRef.rootName, null, coreDialect)
        return Candidate(
            parentOp = op,
            probeRef = ref,
            applyRef = ref,
            pairId = "create:${op.id}",
            revertAfterRename = false,
        )
    }

    private fun classifyAlter(
        op: DiffOperation.AlterSequence,
        coreDialect: RenameProjectionDialect,
    ): Candidate? {
        if (!op.after.preserveCurrentValue && !op.before.preserveCurrentValue) return null
        val ref = SequenceObjectRef(op.objectRef.rootName, null, coreDialect)
        return Candidate(
            parentOp = op,
            probeRef = ref,
            applyRef = ref,
            pairId = "alter:${op.id}",
            revertAfterRename = false,
        )
    }

    private fun classifyRename(
        op: DiffOperation.RenameSequence,
        plan: DiffResult,
        coreDialect: RenameProjectionDialect,
    ): Candidate? {
        val source = plan.currentSchema?.sequences?.get(op.fromName) ?: return null
        if (!source.preserveCurrentValue) return null
        return Candidate(
            parentOp = op,
            probeRef = SequenceObjectRef(op.fromName, null, coreDialect),
            applyRef = SequenceObjectRef(op.toName, null, coreDialect),
            pairId = "rename:${op.id}",
            revertAfterRename = true,
        )
    }

    private fun toCoreDialect(dialect: DatabaseDialect): RenameProjectionDialect = when (dialect) {
        DatabaseDialect.POSTGRESQL -> RenameProjectionDialect.POSTGRESQL
        DatabaseDialect.MYSQL -> RenameProjectionDialect.MYSQL
        DatabaseDialect.SQLITE -> RenameProjectionDialect.SQLITE
        DatabaseDialect.MSSQL -> RenameProjectionDialect.MSSQL
    }

    // ── Skip-path helpers ──────────────────────────────────────────────

    private fun blockUnsupportedDialect(plan: DiffResult, dialect: DatabaseDialect): Outcome {
        val coreDialect = toCoreDialect(dialect)
        val candidates = collectCandidatesIgnoringDialect(plan, coreDialect)
        if (candidates.isEmpty()) return Outcome.NotRun
        val diagnostics = candidates.map { ctx ->
            DiffDiagnostic(
                code = NOT_SUPPORTED_BY_DIALECT_CODE,
                message = "preserveCurrentValue is not supported on ${dialect.name} for " +
                    "${ctx.parentOp::class.simpleName} `${ctx.applyRef.name}`.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = ctx.parentOp.id,
            )
        }
        return Outcome.Failed(diagnostics = diagnostics, plan = plan)
    }

    private fun collectCandidatesIgnoringDialect(
        plan: DiffResult,
        coreDialect: RenameProjectionDialect,
    ): List<Candidate> = plan.operations.mapNotNull { op -> classifyCandidate(op, plan, coreDialect) }

    // ── MySQL initial config check (§6.4.4) ────────────────────────────

    private fun configInvalidIfMysqlNeedsIt(candidates: List<Candidate>, plan: DiffResult): Outcome? {
        val hasMysqlCandidates = candidates.any { it.probeRef.dialect == RenameProjectionDialect.MYSQL }
        if (!hasMysqlCandidates) return null
        if (MysqlSequenceSupportNaming.SUPPORTED_MANAGED_BY.isEmpty()) {
            return failedSingleDiagnostic(
                CONFIG_INVALID_CODE,
                "MySQL sequence metadata is missing supported managed_by markers; " +
                    "cannot perform a deterministic preserve run.",
                plan = plan,
            )
        }
        if (MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS.isEmpty()) {
            return failedSingleDiagnostic(
                CONFIG_INVALID_CODE,
                "MySQL sequence metadata is missing supported format versions; " +
                    "cannot perform a deterministic preserve run.",
                plan = plan,
            )
        }
        return null
    }

    private fun failedSingleDiagnostic(code: String, message: String, plan: DiffResult): Outcome.Failed =
        Outcome.Failed(
            diagnostics = listOf(
                DiffDiagnostic(
                    code = code,
                    message = message,
                    severity = DiffDiagnostic.Severity.BLOCKER,
                ),
            ),
            plan = plan,
        )

    // ── Atomic batch construction + plan augmentation ──────────────────

    private fun buildBatch(
        candidates: List<Candidate>,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): Outcome.Succeeded {
        val requests = candidates.map { ctx ->
            AtomicSequencePreserveRequest(
                sequenceRef = ctx.applyRef,
                renderRestore = { probe ->
                    AtomicPreserveRestoreSql.forDialect(dialect, ctx.applyRef, probe)
                },
            )
        }
        // The batch's protectedOperationIds carry the **instance** IDs
        // of the protected parent ops (CreateSequence / AlterSequence /
        // RenameSequence). `segmentForExecute` matches them against
        // `MigrationDdlStatement.operationIds` (also instance IDs) to
        // route parent statements into the AtomicPreserveSegment;
        // the kind-name allowlist on
        // `SequenceCapability.transactionalProtectedSequenceOperations`
        // is checked separately by [unsupportedKindsBlocker] above —
        // same `ProtectedOperationId` type, different namespace
        // (kind names in the capability, instance IDs in the batch).
        val protectedInstanceIds = candidates
            .map { ProtectedOperationId(it.parentOp.id) }
        val followUps = candidates.map { ctx -> ctx to buildAuditFollowUp(ctx) }
        val followUpInstanceIds = followUps.map { (_, op) -> op.id }
        val batch = AtomicSequencePreserveBatch(
            requests = requests,
            protectedOperationIds = protectedInstanceIds,
            internalFollowUpIds = followUpInstanceIds,
        )
        val augmentedPlan = augmentPlan(plan, followUps)
        return Outcome.Succeeded(
            augmentedPlan = augmentedPlan,
            atomicBatch = batch,
            infoDiagnostics = emptyList(),
        )
    }

    /**
     * Builds the audit-marker [DiffOperation.AlterSequenceCurrentValue]
     * for a candidate. The follow-up carries
     * [DiffOperation.AlterSequenceCurrentValue.Companion.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE]
     * instead of a real probed value — the atomic executor probes
     * inside the lock at execute time and runs the restore SQL
     * produced by [AtomicSequencePreserveRequest.renderRestore].
     *
     * The follow-up exists for two reasons:
     *
     * - Plan-only / report / rollback artefacts continue to surface
     *   the preserve intent for the human reader. The rendered SQL
     *   carries the sentinel value (e.g. `SELECT setval('seq', 0,
     *   true)`); the operator reads it as "preserveCurrentValue is
     *   active, runtime probe pins the actual value".
     * - The op-id is the anchor [SegmentAwareMigrationExecutor] uses
     *   via `internalFollowUpIds` to filter the marker out of the
     *   protected-statements list it passes to the atomic executor's
     *   `executeProtectedOperations` callback.
     */
    private fun buildAuditFollowUp(
        ctx: Candidate,
    ): DiffOperation.AlterSequenceCurrentValue {
        // PG renderer requires isCalled non-null; the renderer
        // assertion fires regardless of currentValue, so the sentinel
        // path must still supply a Boolean. `true` is the safe choice
        // because the atomic executor's restore writes the freshly-
        // probed value; setval(..., value, true) makes the next
        // nextval return `value + 1`, which matches the runtime probe
        // semantic.
        val isCalled = if (ctx.probeRef.dialect == RenameProjectionDialect.POSTGRESQL) true else null
        return DiffOperation.AlterSequenceCurrentValue(
            id = "${ctx.parentOp.id}:preserve",
            objectRef = ctx.parentOp.objectRef,
            pairId = ctx.pairId,
            probeSequenceRef = ctx.probeRef,
            applySequenceRef = ctx.applyRef,
            currentValue = DiffOperation.AlterSequenceCurrentValue.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE,
            isCalled = isCalled,
            restoreValue = null,
            restoreIsCalled = null,
            rollbackImpossible = true,
            rollbackImpossibleReason =
                "Atomic-preserve handles probe + restore at execute time inside the lock; " +
                    "the audit follow-up carries the sentinel current-value (rollback uses " +
                    "the runtime-probed value, not the marker).",
            revertAfterRename = ctx.revertAfterRename,
            dependencies = setOf(ctx.parentOp.id),
        )
    }

    private fun augmentPlan(
        plan: DiffResult,
        followUps: List<Pair<Candidate, DiffOperation.AlterSequenceCurrentValue>>,
    ): DiffResult {
        if (followUps.isEmpty()) return plan
        val byParentId = followUps.associate { (ctx, op) -> ctx.parentOp.id to op }
        val newOps = buildList(plan.operations.size + followUps.size) {
            for (op in plan.operations) {
                add(op)
                byParentId[op.id]?.let { followUp -> add(followUp) }
            }
        }
        return plan.copy(operations = newOps)
    }
}

/**
 * Die Dialekte, deren Renderer den Preserve-Pfad ausdruecken koennen. Steht als
 * Menge und nicht als Bedingungskette da: mit dem vierten Dialekt war die
 * Verkettung an Detekts Komplexitaetsschwelle.
 */
private val PRESERVE_DIALECTS = setOf(
    DatabaseDialect.POSTGRESQL,
    DatabaseDialect.MYSQL,
    DatabaseDialect.SQLITE,
    DatabaseDialect.MSSQL,
)
