package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * PostgreSQL-flavoured renderer for the migration pipeline (Phase
 * D.2 first matrix per Plan §6.2). Translates a [DiffResult] into
 * `MigrationDdlStatement`s using only operations covered by the
 * first matrix:
 *
 * In scope: tables, columns, primary keys, foreign-key/unique
 * constraints, indices, views (with `CREATE OR REPLACE VIEW`),
 * simple ENUM custom types.
 *
 * Out of scope (rendered as `DIALECT_UNSUPPORTED_OPERATION`):
 * routines, triggers, `AlterCustomType`, and `AlterColumnType` casts
 * that are not in the safe-cast allow-list. PostgreSQL sequences are
 * renderable for the declarative attributes in `SequenceDefinition`;
 * live current-value migration remains out of scope.
 *
 * The renderer is stateless and thread-safe. `generateUp` consumes
 * the planner's topo-sorted operations as-is; `generateDown` walks
 * the same operations in reverse and applies their inverse
 * semantics. A `NOT_REVERSIBLE` operation in the down direction
 * yields a `ROLLBACK_NOT_POSSIBLE` blocker.
 *
 * Implementation is split across helpers to satisfy Detekt's
 * `TooManyFunctions` threshold while keeping the dispatch focused:
 *
 * - [PostgresDiffSqlBuilders] — stateless SQL fragment templates
 *   and the safe-implicit-cast allow-list.
 * - [PostgresDiffTableOps] — table / column / primary-key ops.
 * - [PostgresDiffOtherOps] — constraint / index / view / custom-type ops.
 * - [PostgresDiffRenderContext] — bookkeeping for one render pass.
 */
class PostgresDiffDdlGenerator : DiffDdlGenerator {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    private val sql = PostgresDiffSqlBuilders(PostgresTypeMapper())

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = PostgresRenderDirection.UP)

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = PostgresRenderDirection.DOWN)

    private fun render(
        diff: DiffResult,
        options: DdlGenerationOptions,
        direction: PostgresRenderDirection,
    ): MigrationDdlResult {
        val ctx = PostgresDiffRenderContext(
            direction = direction,
            sql = sql,
            options = options,
            migrationOverlays = diff.migrationOverlays,
            sourceFingerprint = diff.current.fingerprint,
            targetFingerprint = diff.desired.fingerprint,
            currentSchema = diff.currentSchema,
            desiredSchema = diff.desiredSchema,
        )
        val ops = if (direction == PostgresRenderDirection.UP) diff.operations else diff.operations.reversed()
        for (op in ops) renderOp(op, ctx)
        return ctx.toResult(diff)
    }

    private fun renderOp(op: DiffOperation, ctx: PostgresDiffRenderContext) {
        if (ctx.direction == PostgresRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(op, "Operation ${op.id} is NOT_REVERSIBLE; cannot render down direction.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        if (renderTableOp(op, ctx)) return
        if (renderOtherOp(op, ctx)) return
        if (renderSequenceOp(op, ctx)) return
        markUnsupported(op, ctx)
    }

    private fun renderTableOp(op: DiffOperation, ctx: PostgresDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateTable -> PostgresDiffTableOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> PostgresDiffTableOps.renderDropTable(op, ctx)
            is DiffOperation.RenameTable -> PostgresDiffTableOps.renderRenameTable(op, ctx)
            is DiffOperation.AddColumn -> PostgresDiffTableOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> PostgresDiffTableOps.renderDropColumn(op, ctx)
            is DiffOperation.RenameColumn -> PostgresDiffTableOps.renderRenameColumn(op, ctx)
            is DiffOperation.AlterColumnType -> PostgresDiffTableOps.renderAlterColumnType(op, ctx)
            is DiffOperation.AlterColumnNullability -> PostgresDiffTableOps.renderAlterColumnNullability(op, ctx)
            is DiffOperation.AlterColumnDefault -> PostgresDiffTableOps.renderAlterColumnDefault(op, ctx)
            is DiffOperation.AddPrimaryKey -> PostgresDiffTableOps.renderAddPrimaryKey(op, ctx)
            is DiffOperation.DropPrimaryKey -> PostgresDiffTableOps.renderDropPrimaryKey(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderOtherOp(op: DiffOperation, ctx: PostgresDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.AddConstraint -> PostgresDiffOtherOps.renderAddConstraint(op, ctx)
            is DiffOperation.DropConstraint -> PostgresDiffOtherOps.renderDropConstraint(op, ctx)
            is DiffOperation.AddIndex -> PostgresDiffOtherOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> PostgresDiffOtherOps.renderDropIndex(op, ctx)
            is DiffOperation.CreateCustomType -> PostgresDiffOtherOps.renderCreateCustomType(op, ctx)
            is DiffOperation.DropCustomType -> PostgresDiffOtherOps.renderDropCustomType(op, ctx)
            is DiffOperation.CreateView -> PostgresDiffOtherOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> PostgresDiffOtherOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> PostgresDiffOtherOps.renderDropView(op, ctx)
            else -> return false
        }
        return true
    }

    private fun renderSequenceOp(op: DiffOperation, ctx: PostgresDiffRenderContext): Boolean {
        when (op) {
            is DiffOperation.CreateSequence -> PostgresDiffSequenceOps.renderCreateSequence(op, ctx)
            is DiffOperation.AlterSequence -> PostgresDiffSequenceOps.renderAlterSequence(op, ctx)
            is DiffOperation.DropSequence -> PostgresDiffSequenceOps.renderDropSequence(op, ctx)
            else -> return false
        }
        return true
    }

    private fun markUnsupported(op: DiffOperation, ctx: PostgresDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not in the first PostgreSQL matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }
}
