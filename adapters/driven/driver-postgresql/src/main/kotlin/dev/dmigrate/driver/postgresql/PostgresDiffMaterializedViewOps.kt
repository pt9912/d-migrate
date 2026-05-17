package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Plan-2 §8 D.3b Sub-Slice A: PostgreSQL renderer for the new
 * materialized-view diff operations
 * ([DiffOperation.CreateMaterializedView] / [DiffOperation.DropMaterializedView]).
 *
 * The legacy [PostgresDiffOtherOps.renderCreateView] / `renderDropView`
 * D.3a guard (`MATERIALIZED_VIEW_DIFF_UNSUPPORTED`) stays active for
 * defense-in-depth: if a future change accidentally routes a
 * `materialized=true` view through the regular View pipeline, the guard
 * still blocks the render.
 */
internal object PostgresDiffMaterializedViewOps {

    fun renderCreateMaterializedView(
        op: DiffOperation.CreateMaterializedView,
        ctx: PostgresDiffRenderContext,
    ) {
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(
                op,
                "DROP MATERIALIZED VIEW ${ctx.sql.quote(name)};",
                PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
            )
            return
        }
        val query = op.view.query
        if (query.isNullOrBlank()) {
            ctx.skip(
                op,
                "Operation ${op.id} cannot render CREATE MATERIALIZED VIEW for '$name' " +
                    "because ViewDefinition.query is absent. Provide the body in the schema source.",
                code = "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        // CREATE MATERIALIZED VIEW: catalog write + initial refresh
        // populates the MV in the same statement. Lock: ACCESS_EXCLUSIVE
        // on the new relation, ACCESS_SHARE on referenced objects.
        ctx.emit(
            op,
            "CREATE MATERIALIZED VIEW ${ctx.sql.quote(name)} AS ${query.trimEnd(';')};",
            PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
        )
    }

    fun renderDropMaterializedView(
        op: DiffOperation.DropMaterializedView,
        ctx: PostgresDiffRenderContext,
    ) {
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            val query = op.view.query
            if (query.isNullOrBlank()) {
                ctx.skip(
                    op,
                    "Operation ${op.id} cannot render down for DROP MATERIALIZED VIEW '$name' " +
                        "because ViewDefinition.query is absent — the rollback would need to " +
                        "recreate the MV from its original body.",
                    code = "MATERIALIZED_VIEW_DOWN_QUERY_UNKNOWN",
                )
                ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
                return
            }
            ctx.emit(
                op,
                "CREATE MATERIALIZED VIEW ${ctx.sql.quote(name)} AS ${query.trimEnd(';')};",
                PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
            )
            return
        }
        ctx.emit(
            op,
            "DROP MATERIALIZED VIEW ${ctx.sql.quote(name)};",
            PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
        )
    }
}
