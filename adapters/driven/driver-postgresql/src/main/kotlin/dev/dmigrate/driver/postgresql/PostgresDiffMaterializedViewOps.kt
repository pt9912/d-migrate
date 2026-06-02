package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Plan-2 §8 D.3b Sub-Slices A/B: PostgreSQL renderer for the
 * materialized-view diff operations
 * ([DiffOperation.CreateMaterializedView] /
 * [DiffOperation.ReplaceMaterializedView] /
 * [DiffOperation.DropMaterializedView]).
 *
 * All statements use [PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS]
 * (`lockBehavior=TABLE_EXCLUSIVE`, `requiresExclusiveAccess=true`) rather
 * than `POSTGRES_METADATA_HINTS`: a materialized view is more than a
 * catalog entry — `CREATE MATERIALIZED VIEW … AS <query>` runs the
 * initial refresh as part of the statement and takes an
 * `AccessExclusiveLock` on the new relation (plus `AccessShareLock` on
 * referenced relations during the SELECT); `DROP MATERIALIZED VIEW`
 * exclusively locks the target before removing it. Plan §5 / §6.4
 * specifies `locking=ACCESS_EXCLUSIVE` at the statement-hint level for
 * both directions.
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
                PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
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
            PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
        )
    }

    /**
     * Plan-2 §8 D.3b Sub-Slice B: PostgreSQL emits the Replace as two
     * statements — `DROP MATERIALIZED VIEW <name>;` then
     * `CREATE MATERIALIZED VIEW <name> AS <query>;`. Both statements
     * share the same [op.id] so Workstream-G's
     * `executionStatementGroups` treats them as one atomic unit; PG's
     * transactional DDL guarantees no reader observes the intermediate
     * dropped state inside the runner-owned transaction.
     */
    fun renderReplaceMaterializedView(
        op: DiffOperation.ReplaceMaterializedView,
        ctx: PostgresDiffRenderContext,
    ) {
        val name = op.objectRef.rootName
        val (sourceQuery, sourceLabel) = if (ctx.direction == PostgresRenderDirection.DOWN) {
            op.before.query to "before.query"
        } else {
            op.after.query to "after.query"
        }
        if (sourceQuery.isNullOrBlank()) {
            val code = if (ctx.direction == PostgresRenderDirection.DOWN) {
                "MATERIALIZED_VIEW_REPLACE_DOWN_BODY_UNKNOWN"
            } else {
                "MATERIALIZED_VIEW_DIFF_METADATA_UNSUPPORTED"
            }
            ctx.skip(
                op,
                "Operation ${op.id} cannot render ${ctx.direction.name} for REPLACE MATERIALIZED VIEW " +
                    "'$name' because $sourceLabel is absent.",
                code = code,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return
        }
        ctx.emit(
            op,
            "DROP MATERIALIZED VIEW ${ctx.sql.quote(name)};",
            PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
        )
        ctx.emit(
            op,
            "CREATE MATERIALIZED VIEW ${ctx.sql.quote(name)} AS ${sourceQuery.trimEnd(';')};",
            PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
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
                PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
            )
            return
        }
        ctx.emit(
            op,
            "DROP MATERIALIZED VIEW ${ctx.sql.quote(name)};",
            PostgresDiffRenderContext.POSTGRES_TRANSACTIONAL_DDL_HINTS,
        )
    }
}
