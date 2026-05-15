package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * MySQL-flavoured renderer for the migration pipeline (Phase D.3
 * first matrix per Plan §6.3). Translates a [DiffResult] into
 * `MigrationDdlStatement`s using only operations covered by the
 * first matrix:
 *
 * In scope: tables, columns (limited — see below), primary keys,
 * foreign-key/unique constraints, indices, views (with
 * `CREATE OR REPLACE VIEW`).
 *
 * Out of scope (rendered as `DIALECT_UNSUPPORTED_OPERATION`):
 * routines, triggers, sequence operations, custom types
 * (MySQL has no standalone `CREATE TYPE`), `AlterCustomType`,
 * `AlterColumnNullability` (MySQL needs the column type to change
 * nullability — first-matrix carve-out), `AlterColumnType` casts
 * outside the safe-cast allow-list.
 *
 * Helpers ([MysqlDiffSqlBuilders], [MysqlDiffTableOps],
 * [MysqlDiffOtherOps], [MysqlDiffRenderContext]) follow the same
 * shape as the PostgreSQL renderer to keep diffs across dialects
 * easy to review.
 */
class MysqlDiffDdlGenerator : DiffDdlGenerator {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    private val sql = MysqlDiffSqlBuilders(MysqlTypeMapper())

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = MysqlRenderDirection.UP)

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = MysqlRenderDirection.DOWN)

    private fun render(
        diff: DiffResult,
        options: DdlGenerationOptions,
        direction: MysqlRenderDirection,
    ): MigrationDdlResult {
        val ctx = MysqlDiffRenderContext(
            direction = direction,
            sql = sql,
            options = options,
            currentSchema = diff.currentSchema,
            desiredSchema = diff.desiredSchema,
        )
        val ops = if (direction == MysqlRenderDirection.UP) diff.operations else diff.operations.reversed()
        for (op in ops) renderOp(op, ctx)
        return ctx.toResult(diff)
    }

    private fun renderOp(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        if (ctx.direction == MysqlRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(op, "Operation ${op.id} is NOT_REVERSIBLE; cannot render down direction.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        when (categorize(op)) {
            OpCategory.TABLE -> renderTableOp(op, ctx)
            OpCategory.OTHER -> renderOtherOp(op, ctx)
            OpCategory.UNSUPPORTED -> markUnsupported(op, ctx)
        }
    }

    /**
     * Compile-time exhaustiveness guard: every [DiffOperation] subtype
     * must be triaged here. When a new subtype is added to the sealed
     * hierarchy, this `when` will fail to compile until the new case
     * is categorised — preventing it from silently falling into
     * `markUnsupported` because [renderTableOp] / [renderOtherOp]
     * happened to not list it.
     */
    private fun categorize(op: DiffOperation): OpCategory = when (op) {
        is DiffOperation.CreateTable,
        is DiffOperation.DropTable,
        is DiffOperation.RenameTable,
        is DiffOperation.AddColumn,
        is DiffOperation.DropColumn,
        is DiffOperation.RenameColumn,
        is DiffOperation.AlterColumnType,
        is DiffOperation.AlterColumnNullability,
        is DiffOperation.AlterColumnDefault,
        is DiffOperation.AddPrimaryKey,
        is DiffOperation.DropPrimaryKey,
        -> OpCategory.TABLE

        is DiffOperation.AddConstraint,
        is DiffOperation.DropConstraint,
        is DiffOperation.AddIndex,
        is DiffOperation.DropIndex,
        is DiffOperation.CreateCustomType,
        is DiffOperation.DropCustomType,
        is DiffOperation.CreateView,
        is DiffOperation.ReplaceView,
        is DiffOperation.DropView,
        -> OpCategory.OTHER

        is DiffOperation.AlterCustomType,
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
        -> OpCategory.UNSUPPORTED
    }

    private fun renderTableOp(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        when (op) {
            is DiffOperation.CreateTable -> MysqlDiffTableOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> MysqlDiffTableOps.renderDropTable(op, ctx)
            is DiffOperation.RenameTable -> MysqlDiffTableOps.renderRenameTable(op, ctx)
            is DiffOperation.AddColumn -> MysqlDiffTableOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> MysqlDiffTableOps.renderDropColumn(op, ctx)
            is DiffOperation.RenameColumn -> MysqlDiffTableOps.renderRenameColumn(op, ctx)
            is DiffOperation.AlterColumnType -> MysqlDiffTableOps.renderAlterColumnType(op, ctx)
            is DiffOperation.AlterColumnNullability -> MysqlDiffTableOps.renderAlterColumnNullability(op, ctx)
            is DiffOperation.AlterColumnDefault -> MysqlDiffTableOps.renderAlterColumnDefault(op, ctx)
            is DiffOperation.AddPrimaryKey -> MysqlDiffTableOps.renderAddPrimaryKey(op, ctx)
            is DiffOperation.DropPrimaryKey -> MysqlDiffTableOps.renderDropPrimaryKey(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised TABLE but renderTableOp does not handle it")
        }
    }

    private fun renderOtherOp(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        when (op) {
            is DiffOperation.AddConstraint -> MysqlDiffOtherOps.renderAddConstraint(op, ctx)
            is DiffOperation.DropConstraint -> MysqlDiffOtherOps.renderDropConstraint(op, ctx)
            is DiffOperation.AddIndex -> MysqlDiffOtherOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> MysqlDiffOtherOps.renderDropIndex(op, ctx)
            is DiffOperation.CreateCustomType -> MysqlDiffOtherOps.renderCreateCustomType(op, ctx)
            is DiffOperation.DropCustomType -> MysqlDiffOtherOps.renderDropCustomType(op, ctx)
            is DiffOperation.CreateView -> MysqlDiffOtherOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> MysqlDiffOtherOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> MysqlDiffOtherOps.renderDropView(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised OTHER but renderOtherOp does not handle it")
        }
    }

    private fun markUnsupported(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not in the first MySQL matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    private enum class OpCategory { TABLE, OTHER, UNSUPPORTED }
}
