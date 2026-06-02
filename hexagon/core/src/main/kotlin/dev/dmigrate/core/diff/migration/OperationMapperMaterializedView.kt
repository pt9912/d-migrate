package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.model.ViewDefinition

/**
 * Plan-2 §8 D.3b Sub-Slice A: emit helpers for the new
 * [DiffOperation.CreateMaterializedView] / [DiffOperation.DropMaterializedView]
 * routes. Hosted in its own file so [OperationMapper] stays under
 * Detekt's `LargeClass` / `TooManyFunctions` thresholds. Pure builders
 * — no rendering or SQL emission happens here.
 */
internal object OperationMapperMaterializedView {

    /**
     * Route a `viewsAdded` entry whose definition is materialized. The
     * helper always emits a [DiffOperation.CreateMaterializedView] so
     * downstream consumers see a typed op; when the `query` body is
     * absent it also appends a planner-level blocker so the renderer /
     * report can deterministically surface the missing metadata
     * without falling into a runtime error.
     */
    fun emitCreate(
        added: NamedView,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        val view = added.definition
        val ref = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf(added.name))
        val op = DiffOperation.CreateMaterializedView(
            id = OperationIdFactory.makeId("CreateMaterializedView", ref, CanonicalPayload.view(view)),
            objectRef = ref,
            view = view,
        )
        ops += op
        if (view.query == null) {
            diagnostics += DiffDiagnostic(
                code = "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
                message = "Materialized view '${added.name}' lacks a query body; CREATE MATERIALIZED VIEW " +
                    "cannot be rendered. Provide `query` in the schema source.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = op.id,
            )
        }
        emitRefreshSemanticsBlockerIfSet(added.name, view, op.id, diagnostics)
    }

    /**
     * Route a `viewsRemoved` entry whose definition is materialized.
     * The Up render only needs the name, so an absent `query` does not
     * block the Up plan. The Down render would fail to reconstruct the
     * MV, so we surface that as a planner-level `BLOCKED_DOWN_QUERY_UNKNOWN`
     * **WARNING** which the report builder picks up to populate the
     * rollback contract. Severity must NOT be `BLOCKER`: every dialect's
     * `RenderContext.toResult` actively promotes plan-level BLOCKER
     * diagnostics into a `DIALECT_UNSUPPORTED_OPERATION`
     * `MigrationBlocker`, which would flip `MigrationDdlResult.isBlocked`
     * to `true` and cause the CLI to exit `8` even though the forward DDL
     * is valid. The renderer's own Down pass emits the proper
     * `MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN` block when Down is actually
     * rendered.
     */
    fun emitDrop(
        removed: NamedView,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        val view = removed.definition
        val ref = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf(removed.name))
        val op = DiffOperation.DropMaterializedView(
            id = OperationIdFactory.makeId("DropMaterializedView", ref, CanonicalPayload.view(view)),
            objectRef = ref,
            view = view,
        )
        ops += op
        if (view.query == null) {
            diagnostics += DiffDiagnostic(
                code = "BLOCKED_DOWN_QUERY_UNKNOWN",
                message = "Materialized view '${removed.name}' has no recoverable query body; DROP " +
                    "MATERIALIZED VIEW can run forward but rollback would require manual " +
                    "reconstruction.",
                severity = DiffDiagnostic.Severity.WARNING,
                operationId = op.id,
            )
        }
        emitRefreshSemanticsBlockerIfSet(removed.name, view, op.id, diagnostics)
    }

    /**
     * Plan-2 §2 / §6.4.1: `ViewDefinition.refresh` is deliberately
     * unspecified in D.3b — the field exists in the schema model but has
     * no semantic interpretation. When it is set, the materialized-view
     * contract for the affected op must surface
     * `BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED` so the operator sees
     * the refresh-intent has not been honoured. Severity is `WARNING`:
     * BLOCKER would be promoted by `RenderContext.toResult` into a
     * `DIALECT_UNSUPPORTED_OPERATION` blocker that stops the Up DDL
     * unnecessarily — the refresh-semantic gap is a report-vertrag
     * concern, not a render concern.
     */
    private fun emitRefreshSemanticsBlockerIfSet(
        name: String,
        view: ViewDefinition,
        operationId: String,
        diagnostics: MutableList<DiffDiagnostic>,
    ) {
        if (view.refresh == null) return
        diagnostics += DiffDiagnostic(
            code = "BLOCKED_VIEW_DEFINITION_REFRESH_UNSPECIFIED",
            message = "Materialized view '$name' has `ViewDefinition.refresh` set " +
                "(value: '${view.refresh}') but D.3b does not yet evaluate refresh semantics. " +
                "The report surfaces this as a deterministic OOS-Blocker so the operator can " +
                "either remove the refresh field or wait for a future slice that handles it.",
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = operationId,
        )
    }

    /**
     * Plan-2 §8 D.3b Sub-Slice B: emit a [DiffOperation.ReplaceMaterializedView]
     * for a body/columns change on a materialized view where both sides
     * stay materialized. Severity rules:
     *
     * - `after.query == null` ⇒ the Up render cannot reconstruct the
     *   MV, so the diff is BLOCKER-blocked with
     *   `BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED`.
     * - `after.query` is present but `before.query == null` ⇒ Up runs
     *   fine, only the Down inverse needs `before.query`. Emitted as
     *   `BLOCKED_REPLACE_DOWN_BODY_UNKNOWN` at `WARNING` severity so
     *   `RenderContext.toResult` does not promote it into a
     *   `DIALECT_UNSUPPORTED_OPERATION` blocker that would stop the
     *   valid forward DDL.
     */
    fun emitReplace(
        name: String,
        before: ViewDefinition,
        after: ViewDefinition,
        diagnostics: MutableList<DiffDiagnostic>,
        ops: MutableList<DiffOperation>,
    ) {
        val ref = DiffObjectRef(DiffObjectType.MATERIALIZED_VIEW, listOf(name))
        val op = DiffOperation.ReplaceMaterializedView(
            id = OperationIdFactory.makeId(
                "ReplaceMaterializedView",
                ref,
                "before=" + CanonicalPayload.view(before) + "->after=" + CanonicalPayload.view(after),
            ),
            objectRef = ref,
            before = before,
            after = after,
        )
        ops += op
        when {
            after.query == null -> diagnostics += DiffDiagnostic(
                code = "BLOCKED_MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
                message = "Materialized view '$name' replace target lacks a query body; CREATE " +
                    "MATERIALIZED VIEW cannot be rendered. Provide `query` in the desired schema.",
                severity = DiffDiagnostic.Severity.BLOCKER,
                operationId = op.id,
            )
            before.query == null -> diagnostics += DiffDiagnostic(
                code = "BLOCKED_REPLACE_DOWN_BODY_UNKNOWN",
                message = "Materialized view '$name' has no recoverable original body; the Up DROP+" +
                    "CREATE renders fine but the rollback would require manual reconstruction of " +
                    "the prior body.",
                severity = DiffDiagnostic.Severity.WARNING,
                operationId = op.id,
            )
        }
        // Plan-2 §2: `ViewDefinition.refresh` is unevaluated in D.3b
        // regardless of which side carries it. Prefer the `after` side
        // when set so the diagnostic message reflects the operator's
        // desired state; fall back to `before` when only the previous
        // schema had the field (e.g. operator removed `refresh` as
        // part of the Replace).
        when {
            after.refresh != null ->
                emitRefreshSemanticsBlockerIfSet(name, after, op.id, diagnostics)
            before.refresh != null ->
                emitRefreshSemanticsBlockerIfSet(name, before, op.id, diagnostics)
        }
    }

    /**
     * Emit a `BLOCKED_CONVERSION_UNSUPPORTED` diagnostic for a
     * `View↔MaterializedView` materialized-flag flip. The legacy
     * [DiffOperation.ReplaceView] op stays in the plan so the report
     * builder has something to attach the contract to; the D.3a guard
     * inside the renderer blocks any SQL emission.
     */
    fun emitConversionDiagnostic(
        name: String,
        before: ViewDefinition,
        after: ViewDefinition,
        replaceOpId: String,
        diagnostics: MutableList<DiffDiagnostic>,
    ) {
        diagnostics += DiffDiagnostic(
            code = "BLOCKED_CONVERSION_UNSUPPORTED",
            message = "View '$name' changes its materialized flag " +
                "(${before.materialized} → ${after.materialized}). D.3b blocks View↔" +
                "Materialized-View conversion to avoid silent schema flips; the operator " +
                "must drop and recreate the object explicitly.",
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = replaceOpId,
        )
    }
}
