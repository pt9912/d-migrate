package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * SQLite-flavoured renderer for the migration pipeline (Phase D.4.a
 * first-matrix slice per Plan §6.4).
 *
 * Slice scope:
 *
 * - In scope: tables, columns (`ADD` / `DROP COLUMN`), indices,
 *   simple views (`CREATE`, `DROP`, `DROP+CREATE`-style replace).
 * - Deferred to D.4.b (RebuildTable pipeline,
 *   `MANUAL_ACTION_REQUIRED` blocker today): `AlterColumnType`,
 *   `AlterColumnNullability`, `AlterColumnDefault`,
 *   `AddPrimaryKey`, `DropPrimaryKey`, `AddConstraint`,
 *   `DropConstraint`. SQLite cannot mutate any of these without
 *   rebuilding the table.
 * - Out of scope (DIALECT_UNSUPPORTED_OPERATION): routines,
 *   triggers, sequence ops, custom types (SQLite has no
 *   `CREATE TYPE`).
 *
 * `ReplaceView` emits two statements (DROP IF EXISTS + CREATE) both
 * tagged with the source op-id, since SQLite has no
 * `CREATE OR REPLACE VIEW`.
 *
 * Down-direction walks the planner's topo-sort in reverse and
 * projects each op's `OperationRisks.down`. Any `NOT_REVERSIBLE`
 * op down-renders as a `ROLLBACK_NOT_POSSIBLE` blocker.
 */
class SqliteDiffDdlGenerator : DiffDdlGenerator {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    private val sql = SqliteDiffSqlBuilders()

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = SqliteRenderDirection.UP)

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = SqliteRenderDirection.DOWN)

    private fun render(
        diff: DiffResult,
        options: DdlGenerationOptions,
        direction: SqliteRenderDirection,
    ): MigrationDdlResult {
        val ctx = SqliteDiffRenderContext(direction = direction, sql = sql, options = options)
        val ops = if (direction == SqliteRenderDirection.UP) diff.operations else diff.operations.reversed()
        for (op in ops) renderOp(op, ctx)
        return ctx.toResult(diff)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun renderOp(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        if (ctx.direction == SqliteRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(op, "Operation ${op.id} is NOT_REVERSIBLE; cannot render down direction.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        when (op) {
            is DiffOperation.CreateTable -> SqliteDiffSimpleOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> SqliteDiffSimpleOps.renderDropTable(op, ctx)
            is DiffOperation.AddColumn -> SqliteDiffSimpleOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> SqliteDiffSimpleOps.renderDropColumn(op, ctx)
            is DiffOperation.AddIndex -> SqliteDiffSimpleOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> SqliteDiffSimpleOps.renderDropIndex(op, ctx)
            is DiffOperation.CreateView -> SqliteDiffSimpleOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> SqliteDiffSimpleOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> SqliteDiffSimpleOps.renderDropView(op, ctx)

            // Rebuild-required: deferred to D.4.b
            is DiffOperation.AlterColumnType,
            is DiffOperation.AlterColumnNullability,
            is DiffOperation.AlterColumnDefault,
            is DiffOperation.AddPrimaryKey,
            is DiffOperation.DropPrimaryKey,
            is DiffOperation.AddConstraint,
            is DiffOperation.DropConstraint,
            -> ctx.deferToRebuild(op)

            // Out of first matrix entirely
            is DiffOperation.CreateCustomType,
            is DiffOperation.AlterCustomType,
            is DiffOperation.DropCustomType,
            is DiffOperation.CreateSequence,
            is DiffOperation.AlterSequence,
            is DiffOperation.DropSequence,
            is DiffOperation.CreateFunction,
            is DiffOperation.ReplaceFunction,
            is DiffOperation.DropFunction,
            is DiffOperation.CreateProcedure,
            is DiffOperation.ReplaceProcedure,
            is DiffOperation.DropProcedure,
            is DiffOperation.CreateTrigger,
            is DiffOperation.ReplaceTrigger,
            is DiffOperation.DropTrigger,
            -> markUnsupported(op, ctx)
        }
    }

    private fun markUnsupported(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not in the first SQLite matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }
}
