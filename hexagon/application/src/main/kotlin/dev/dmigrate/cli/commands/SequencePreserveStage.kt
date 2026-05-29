package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceSupportNaming
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import java.nio.file.Path

/**
 * 0.9.7 preserve-current-value Sub-Slice D (2026-05-21): per-op
 * probe-and-emit dispatcher for [DiffOperation.AlterSequenceCurrentValue]
 * follow-ups. Wired into [SchemaMigrateRenderPipeline] between
 * `MigrationPreflightPlanner.plan(...)` and `renderer.generateUp(...)`.
 *
 * The stage owns four responsibilities, all documented in
 * Plan-Doc §6.4 (Sub-Slice D Detail-DoD):
 *
 * 1. **Candidate filter (§6.4.1)**: select parent sequence ops whose
 *    `SequenceDefinition.preserveCurrentValue = true` AND match the
 *    plan-doc kandidaten-table — `AlterSequence` / `RenameSequence`
 *    with a managed source, and `CreateSequence` only when
 *    [shouldProbeCreateSequence] returns `true`. `DropSequence` is
 *    not a candidate.
 * 2. **Skip-paths (§6.4.3)**: file-target priority blocker, no
 *    execute, non-MySQL/PG dialect, no probe wired, no candidates
 *    → distinct outcomes.
 * 3. **Routing (§6.4.5)**: probe-result → `RouteOutcome` per
 *    `(parentOp, probeResult)` tuple. Read produces a follow-up;
 *    NotFound is a blocker except for CreateSequence (info-only);
 *    Failed and unsupported routes block with structured codes.
 * 4. **Plan augmentation (§6.4.6)**: follow-up ops land directly
 *    behind their parent op in the resulting [DiffResult.operations]
 *    list with `dependencies = setOf(parent.id)` so a future top-
 *    sort cannot reorder them.
 *
 * Three-state [Outcome] mirrors [MysqlSequenceCanonicityStage] so the
 * render pipeline consumes both stages uniformly.
 */
object SequencePreserveStage {

    /** Codes the stage emits; classifier mapping in [PlannerBlockerClassifier]. */
    private const val PROBE_FAILED_CODE = PlannerBlockerClassifier.SEQUENCE_PRESERVE_PROBE_FAILED_CODE
    private const val CONFIG_INVALID_CODE = PlannerBlockerClassifier.SEQUENCE_PRESERVE_CONFIG_INVALID_CODE
    private const val REQUIRES_DB_TARGET_CODE = PlannerBlockerClassifier.SEQUENCE_PRESERVE_REQUIRES_DB_TARGET_CODE
    private const val NOT_SUPPORTED_BY_DIALECT_CODE =
        PlannerBlockerClassifier.SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT_CODE
    private const val OPT_IN_REQUIRED_CODE =
        PlannerBlockerClassifier.SEQUENCE_PRESERVE_OPT_IN_REQUIRED_CODE
    private const val NOT_FOUND_INFO_CODE = "SEQUENCE_PRESERVE_NOT_FOUND"
    private const val NOT_RUN_POLICY_INFO_CODE = "SEQUENCE_PRESERVE_NOT_RUN_POLICY"

    /**
     * Three-state outcome of the stage. The render-pipeline consumes
     * this analog to [MysqlSequenceCanonicityStage.Outcome].
     *
     * - [Succeeded] carries an augmented [DiffResult] (the original
     *   operations + follow-ups inserted behind their parents) plus
     *   the INFO diagnostics the stage decided to surface but not
     *   block on (e.g. `SEQUENCE_PRESERVE_NOT_FOUND` for a
     *   CreateSequence without prior state).
     * - [Failed] carries the augmented diagnostics (BLOCKER + INFO)
     *   AND the original plan unchanged — the render pipeline
     *   short-circuits to `buildFailureResult` so no render happens.
     * - [NotRun] means none of the gating conditions applied; the
     *   render pipeline proceeds with the original plan.
     */
    sealed interface Outcome {
        data class Succeeded(
            val augmentedPlan: DiffResult,
            val infoDiagnostics: List<DiffDiagnostic>,
        ) : Outcome
        data class Failed(
            val diagnostics: List<DiffDiagnostic>,
            val plan: DiffResult,
        ) : Outcome
        data object NotRun : Outcome
    }

    fun run(
        probe: SequenceCurrentValueProbeFn?,
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): Outcome {
        // §6.4.3: file-target with preserve-candidates is a structural
        // blocker (the probe needs a live DB; plan-only against a file
        // target cannot emit setval/UPDATE follow-ups, so the migration
        // would silently miss the preserve step). `SchemaMigratePreparation`
        // already exits 2 for `--execute` + file target, so this branch
        // primarily fires for `--plan-only` against a file source — but
        // it stays here as a single Stage-level guard so the diagnostic
        // path is consistent regardless of preparation-layer routing.
        val candidates = collectCandidates(plan, dialect)
        if (target !is CompareOperand.Database) {
            return if (candidates.isEmpty()) Outcome.NotRun else requiresDbTargetBlocker(candidates, plan)
        }
        if (!request.execute) return Outcome.NotRun
        // Forward-compat dialect-allowlist guard. All three current
        // dialects pass; the branch is exhaustive scaffolding for a
        // future enum extension without a wired preserve flow.
        if (dialect != DatabaseDialect.POSTGRESQL &&
            dialect != DatabaseDialect.MYSQL &&
            dialect != DatabaseDialect.SQLITE
        ) {
            return blockUnsupportedDialect(plan, dialect)
        }
        // 0.9.7-E.3-Folge-Slice plan-doc §7.5: SQLite uses the helper-
        // table emulation, which is opt-in via
        // `--sqlite-named-sequences helper_table`. If the operator
        // hasn't opted in, every preserve-candidate surfaces a
        // structured blocker BEFORE the probe connection is opened.
        // The diagnostic carries the explicit remedy in its message so
        // the operator can flip the flag without consulting the
        // dialect-routing matrix. The renderer enforces the same gate;
        // the stage-side block keeps the surface specific instead of
        // letting a generic MANUAL_ACTION_REQUIRED leak through.
        if (dialect == DatabaseDialect.SQLITE && candidates.isNotEmpty()) {
            val mode = request.sqliteNamedSequences?.let(SqliteNamedSequenceMode::fromCliName)
            if (mode != SqliteNamedSequenceMode.HELPER_TABLE) {
                return sqliteOptInRequiredBlocker(candidates, plan)
            }
        }
        if (candidates.isEmpty()) return Outcome.NotRun
        if (probe == null) return notRunPolicy(candidates, plan)

        // §6.4.4 initial config check (only when MySQL candidates exist).
        configInvalidIfMysqlNeedsIt(candidates, plan)?.let { return it }

        return runCandidates(candidates, probe, target, request.cliConfigPath, plan)
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
     * §6.4.3 priority blocker: when the target is a file and the plan
     * carries preserve-candidate sequence ops, no probe can run and
     * the resulting plan would lie about the preserve follow-ups it
     * cannot produce. Block per-candidate with the
     * `SEQUENCE_PRESERVE_REQUIRES_DB_TARGET` code so the operator
     * sees a structured blocker instead of a silently-incomplete
     * preserve plan.
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

    /**
     * Builds a [MigrationDdlResult] header for the stage's `Failed`
     * outcome — the render pipeline short-circuits to this when the
     * stage produces blocker diagnostics. Mirrors the
     * `buildFailureResult` pattern from [MysqlSequenceCanonicityStage].
     */
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

    /**
     * Bundles a parent sequence-op with the dialect-aware
     * [SequenceObjectRef] pair the renderer / probe will use.
     * `probeRef` and `applyRef` differ only for `RenameSequence` —
     * Create / Alter use the same ref for both sides.
     */
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
        // DropSequence is intentionally not a candidate per §6.4.1.
        else -> null
    }

    private fun classifyCreate(
        op: DiffOperation.CreateSequence,
        coreDialect: RenameProjectionDialect,
    ): Candidate? {
        if (!op.sequence.preserveCurrentValue) return null
        if (!shouldProbeCreateSequence(op)) return null
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
        // Either side opting in is enough — the renderer's Down path
        // restores against `before`, so a flip from preserve=true to
        // preserve=false should still emit the Down restore.
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
        if (!shouldProbeRenameSequence(op, plan)) return null
        return Candidate(
            parentOp = op,
            probeRef = SequenceObjectRef(op.fromName, null, coreDialect),
            applyRef = SequenceObjectRef(op.toName, null, coreDialect),
            pairId = "rename:${op.id}",
            revertAfterRename = true,
        )
    }

    /**
     * §6.4.1 conservative pre-probe gate. Plan-Doc §3.1 notes this is
     * intentionally narrow in the first tranche — only Create-aus-
     * Rename-Fallback paths have a deterministic prior state today.
     * A future slice may widen this to "live target carries the
     * sequence already" via a separate sentinel.
     */
    private fun shouldProbeCreateSequence(op: DiffOperation.CreateSequence): Boolean =
        op.renameProvenance != null

    /**
     * §6.4.1 rename-candidate predicate. Drop+Create-Fallbacks never
     * reach this branch — the Mapper decomposes them upstream — so
     * the only check here is "does the SOURCE sequence carry
     * preserveCurrentValue = true". `currentSchema` MUST be populated
     * for the lookup; an artefact-deserialised plan without it
     * cannot opt into preserve-on-rename.
     */
    private fun shouldProbeRenameSequence(op: DiffOperation.RenameSequence, plan: DiffResult): Boolean {
        val source = plan.currentSchema?.sequences?.get(op.fromName) ?: return false
        return source.preserveCurrentValue
    }

    private fun toCoreDialect(dialect: DatabaseDialect): RenameProjectionDialect = when (dialect) {
        DatabaseDialect.POSTGRESQL -> RenameProjectionDialect.POSTGRESQL
        DatabaseDialect.MYSQL -> RenameProjectionDialect.MYSQL
        DatabaseDialect.SQLITE -> RenameProjectionDialect.SQLITE
    }

    // ── Skip-path helpers ──────────────────────────────────────────────

    private fun blockUnsupportedDialect(plan: DiffResult, dialect: DatabaseDialect): Outcome {
        // §6.4.3: a dialect outside the allowlist (today: every
        // non-PG/MySQL/SQLite dialect; the enum has none yet so the
        // branch is forward-compat scaffolding) blocks per-candidate
        // with NOT_SUPPORTED_BY_DIALECT. Without candidates the stage
        // stays NotRun.
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

    /**
     * Like [collectCandidates] but without the dialect-aware probe
     * filter — used for the dialect-not-supported skip path, where
     * the dialect is e.g. SQLite and we need the candidate list to
     * stamp per-op blockers even though no probe would run.
     */
    private fun collectCandidatesIgnoringDialect(
        plan: DiffResult,
        coreDialect: RenameProjectionDialect,
    ): List<Candidate> = plan.operations.mapNotNull { op -> classifyCandidate(op, plan, coreDialect) }

    private fun notRunPolicy(candidates: List<Candidate>, plan: DiffResult): Outcome {
        // §6.4.3: probe-fn null → per-op INFO. The infoDiagnostics
        // ride along with Succeeded(plan unchanged) so the report
        // surfaces them without blocking.
        val notes = candidates.map { ctx ->
            DiffDiagnostic(
                code = NOT_RUN_POLICY_INFO_CODE,
                message = "preserveCurrentValue is active for ${ctx.parentOp::class.simpleName} " +
                    "`${ctx.applyRef.name}` but no SequenceCurrentValueProbe is wired; " +
                    "probe and follow-up are intentionally skipped.",
                severity = DiffDiagnostic.Severity.INFO,
                operationId = ctx.parentOp.id,
            )
        }
        return Outcome.Succeeded(augmentedPlan = plan, infoDiagnostics = notes)
    }

    // ── Initial-check (§6.4.4) ─────────────────────────────────────────

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

    // ── Probe & route (§6.4.5) + augment (§6.4.6) ──────────────────────

    private fun runCandidates(
        candidates: List<Candidate>,
        probe: SequenceCurrentValueProbeFn,
        target: CompareOperand.Database,
        configPath: Path?,
        plan: DiffResult,
    ): Outcome {
        val followUps = mutableListOf<Pair<Candidate, DiffOperation.AlterSequenceCurrentValue>>()
        val blockerDiagnostics = mutableListOf<DiffDiagnostic>()
        val infoDiagnostics = mutableListOf<DiffDiagnostic>()

        for (ctx in candidates) {
            val probeResult = try {
                probe(target, configPath, ctx.probeRef)
            } catch (e: Exception) {
                blockerDiagnostics += diagFromException(ctx, e)
                continue
            }
            when (val outcome = routeProbeResult(ctx, probeResult)) {
                is RouteOutcome.FollowUp -> followUps += ctx to outcome.op
                is RouteOutcome.Block -> blockerDiagnostics += outcome.diagnostic
                is RouteOutcome.Info -> infoDiagnostics += outcome.diagnostic
            }
        }

        if (blockerDiagnostics.isNotEmpty()) {
            return Outcome.Failed(
                diagnostics = blockerDiagnostics + infoDiagnostics,
                plan = plan,
            )
        }
        val augmented = augmentPlan(plan, followUps)
        return Outcome.Succeeded(augmentedPlan = augmented, infoDiagnostics = infoDiagnostics)
    }

    /** Sealed routing result per [Candidate] × [SequenceCurrentValueProbeResult]. */
    private sealed interface RouteOutcome {
        data class FollowUp(val op: DiffOperation.AlterSequenceCurrentValue) : RouteOutcome
        data class Block(val diagnostic: DiffDiagnostic) : RouteOutcome
        data class Info(val diagnostic: DiffDiagnostic) : RouteOutcome
    }

    private fun routeProbeResult(
        ctx: Candidate,
        probeResult: SequenceCurrentValueProbeResult,
    ): RouteOutcome = when (probeResult) {
        is SequenceCurrentValueProbeResult.Read -> {
            if (probeResult.matchedRows != 1) {
                RouteOutcome.Block(
                    blockerDiag(ctx, PROBE_FAILED_CODE,
                        "Sequence preserve probe for `${ctx.probeRef.name}` returned " +
                            "${probeResult.matchedRows} rows (expected exactly 1)."),
                )
            } else {
                RouteOutcome.FollowUp(buildFollowUp(ctx, probeResult))
            }
        }
        is SequenceCurrentValueProbeResult.NotFound -> when (ctx.parentOp) {
            is DiffOperation.CreateSequence -> RouteOutcome.Info(
                infoDiag(ctx, NOT_FOUND_INFO_CODE,
                    "No existing target state for sequence `${ctx.applyRef.name}`; " +
                        "current-value preserve is skipped and rollback is " +
                        "ROLLBACK_NOT_POSSIBLE."),
            )
            else -> RouteOutcome.Block(
                blockerDiag(ctx, PROBE_FAILED_CODE,
                    "Sequence preserve probe for `${ctx.probeRef.name}` returned NotFound; " +
                        "${ctx.parentOp::class.simpleName} requires a deterministic prior " +
                        "state to preserve."),
            )
        }
        is SequenceCurrentValueProbeResult.Failed -> RouteOutcome.Block(
            blockerDiag(ctx, PROBE_FAILED_CODE,
                "Sequence preserve probe for `${ctx.probeRef.name}` failed " +
                    "(${probeResult.code}): ${probeResult.message}"),
        )
        SequenceCurrentValueProbeResult.NotApplicable -> RouteOutcome.Block(
            blockerDiag(ctx, NOT_SUPPORTED_BY_DIALECT_CODE,
                "Sequence preserve is not supported for `${ctx.probeRef.name}` on " +
                    "${ctx.probeRef.dialect.name}."),
        )
    }

    private fun buildFollowUp(
        ctx: Candidate,
        probe: SequenceCurrentValueProbeResult.Read,
    ): DiffOperation.AlterSequenceCurrentValue {
        // Preserve means Up applies the probed value AND Down restores
        // back to the same probed value (we're keeping the runtime
        // state across declarative changes, not modifying it). So Up
        // and Down share the value + isCalled — currentValue ==
        // restoreValue, isCalled == restoreIsCalled. The asymmetry
        // lives in the sequence ref (applyRef vs probeRef) and in the
        // CreateSequence-specific rollbackImpossible flag.
        val isCalled = if (ctx.probeRef.dialect == RenameProjectionDialect.POSTGRESQL) probe.isCalled else null
        val rollbackImpossible = ctx.parentOp is DiffOperation.CreateSequence
        val rollbackImpossibleReason = if (rollbackImpossible) {
            "CreateSequence has no pre-Up state — current-value restore is not deterministic."
        } else {
            null
        }
        return DiffOperation.AlterSequenceCurrentValue(
            id = "${ctx.parentOp.id}:preserve",
            objectRef = ctx.parentOp.objectRef,
            pairId = ctx.pairId,
            probeSequenceRef = ctx.probeRef,
            applySequenceRef = ctx.applyRef,
            currentValue = probe.value,
            isCalled = isCalled,
            restoreValue = if (rollbackImpossible) null else probe.value,
            restoreIsCalled = if (rollbackImpossible) null else isCalled,
            rollbackImpossible = rollbackImpossible,
            rollbackImpossibleReason = rollbackImpossibleReason,
            revertAfterRename = ctx.revertAfterRename,
            dependencies = setOf(ctx.parentOp.id),
        )
    }

    private fun diagFromException(ctx: Candidate, e: Exception): DiffDiagnostic = blockerDiag(
        ctx, PROBE_FAILED_CODE,
        "Sequence preserve probe for `${ctx.probeRef.name}` threw " +
            "${e::class.simpleName}: ${e.message ?: "(no message)"}",
    )

    private fun blockerDiag(ctx: Candidate, code: String, message: String): DiffDiagnostic =
        DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = ctx.parentOp.id,
        )

    private fun infoDiag(ctx: Candidate, code: String, message: String): DiffDiagnostic =
        DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.INFO,
            operationId = ctx.parentOp.id,
        )

    // ── Plan augmentation (§6.4.6) ─────────────────────────────────────

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
 * 0.9.7 preserve-current-value Sub-Slice D probe-fn typealias. The
 * runner takes ONE optional lambda; the dialect-dispatch lives at the
 * CLI boundary (see `SequenceCurrentValueProbeRunner` in the CLI
 * adapter). Per-op probe (not batch) — identical to the drift-check
 * pattern.
 */
typealias SequenceCurrentValueProbeFn = (
    target: CompareOperand.Database,
    configPath: Path?,
    sequenceRef: SequenceObjectRef,
) -> SequenceCurrentValueProbeResult
