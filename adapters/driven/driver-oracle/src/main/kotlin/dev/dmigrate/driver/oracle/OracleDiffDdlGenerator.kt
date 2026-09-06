package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult

/**
 * Oracle-flavoured renderer for the migration pipeline (ADR 0052, Sub-Slices
 * 5a-5c). Covers three of the [DiffOperation] families so far:
 *
 * In scope: the table/column/primary-key family (5a) — `CreateTable`,
 * `DropTable`, `RenameTable`, `AddColumn`, `DropColumn`, `RenameColumn`,
 * `AlterColumnType`, `AlterColumnNullability`, `AlterColumnDefault`,
 * `AddPrimaryKey`, `DropPrimaryKey` — plus the constraint/index family (5b):
 * `AddConstraint`, `DropConstraint`, `AddIndex`, `DropIndex`; and the
 * view/custom-type family (5c): `CreateView`, `ReplaceView`, `DropView`,
 * `RenameView`, `CreateCustomType`, `AlterCustomType`, `DropCustomType`.
 *
 * Everything else surfaces as `DIALECT_UNSUPPORTED_OPERATION` — sequences
 * (5d) and the remaining families follow in later sub-slices per
 * `docs/planning/in-progress/oracle-dialect-scoping.md`.
 *
 * Not yet wired into `MigrateRendererRegistry`/`DialectCommandGate` — like
 * every dialect before it, that happens in the closing sub-slice (5e), not
 * as each operation family lands. Until then this renderer is reachable
 * only via direct instantiation (unit/integration tests).
 *
 * Implementation is split across helpers to satisfy Detekt's
 * `TooManyFunctions` threshold while keeping the dispatch focused:
 *
 * - [OracleDiffSqlBuilders] — identifier quoting, bare type/default SQL.
 * - [OracleDiffTableOps] — table / column / primary-key ops (reuses
 *   [OracleColumnConstraintHelper] / [OracleIndexDdlBuilder] from the
 *   Generate path).
 * - [OracleDiffObjectOps] — constraint / index ops, same reuse.
 * - [OracleDiffViewOps] / [OracleDiffCustomTypeOps] — views and custom types.
 * - [OracleDiffRenderContext] — bookkeeping for one render pass.
 */
class OracleDiffDdlGenerator : DiffDdlGenerator {

    override val dialect: DatabaseDialect = DatabaseDialect.ORACLE

    private val sql = OracleDiffSqlBuilders(OracleTypeMapper())

    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = OracleRenderDirection.UP)

    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions): MigrationDdlResult =
        render(diff, options, direction = OracleRenderDirection.DOWN)

    private fun render(diff: DiffResult, options: DdlGenerationOptions, direction: OracleRenderDirection): MigrationDdlResult {
        val ctx = OracleDiffRenderContext(
            direction = direction,
            sql = sql,
            options = options,
            currentSchema = diff.currentSchema,
            desiredSchema = diff.desiredSchema,
        )
        val ops = if (direction == OracleRenderDirection.UP) diff.operations else diff.operations.reversed()
        for (op in ops) renderOp(op, ctx)
        return ctx.toResult(diff)
    }

    private fun renderOp(op: DiffOperation, ctx: OracleDiffRenderContext) {
        if (ctx.direction == OracleRenderDirection.DOWN && op.reversibility == Reversibility.NOT_REVERSIBLE) {
            ctx.skip(op, "Operation ${op.id} is NOT_REVERSIBLE; cannot render down direction.")
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        // Ohne Down-Risikoprofil hat der Planner fuer diese Richtung keine
        // Umkehr definiert (`MANUAL_REQUIRED`, etwa `AlterCustomType`). Ohne
        // diesen Waechter liefe der Renderer in `riskFor`s `error(...)` --
        // eine Exception statt des Blockers, den der Port verlangt.
        if (ctx.direction == OracleRenderDirection.DOWN && op.risks.down == null) {
            ctx.skip(
                op,
                "Operation ${op.id} carries no risk profile for the Down direction; the planner defines no " +
                    "inverse for it, so the renderer cannot construct one either.",
                code = "ROLLBACK_NOT_POSSIBLE",
            )
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, setOf(op.id))
            return
        }
        when (categorize(op)) {
            OpCategory.TABLE -> renderTableOp(op, ctx)
            OpCategory.OBJECT -> renderObjectOp(op, ctx)
            OpCategory.VIEW_OR_TYPE -> renderViewOrTypeOp(op, ctx)
            OpCategory.UNSUPPORTED -> markUnsupported(op, ctx)
        }
    }

    /**
     * Compile-time exhaustiveness guard: every [DiffOperation] subtype must
     * be triaged here. When a new subtype is added to the sealed hierarchy,
     * this `when` fails to compile until the new case is categorised —
     * preventing it from silently falling into [markUnsupported] by accident
     * as later sub-slices land.
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
        -> OpCategory.OBJECT

        is DiffOperation.CreateView,
        is DiffOperation.ReplaceView,
        is DiffOperation.DropView,
        is DiffOperation.RenameView,
        is DiffOperation.CreateCustomType,
        is DiffOperation.AlterCustomType,
        is DiffOperation.DropCustomType,
        -> OpCategory.VIEW_OR_TYPE

        is DiffOperation.AlterTablePartitions,
        is DiffOperation.CreateSequence,
        is DiffOperation.AlterSequence,
        is DiffOperation.DropSequence,
        is DiffOperation.RenameSequence,
        is DiffOperation.AlterSequenceCurrentValue,
        is DiffOperation.CreateMaterializedView,
        is DiffOperation.ReplaceMaterializedView,
        is DiffOperation.DropMaterializedView,
        is DiffOperation.CreateFunction,
        is DiffOperation.ReplaceFunction,
        is DiffOperation.DropFunction,
        is DiffOperation.RenameFunction,
        is DiffOperation.CreateProcedure,
        is DiffOperation.ReplaceProcedure,
        is DiffOperation.DropProcedure,
        is DiffOperation.RenameProcedure,
        is DiffOperation.CreateTrigger,
        is DiffOperation.ReplaceTrigger,
        is DiffOperation.DropTrigger,
        is DiffOperation.RenameTrigger,
        -> OpCategory.UNSUPPORTED
    }

    private fun renderTableOp(op: DiffOperation, ctx: OracleDiffRenderContext) {
        when (op) {
            is DiffOperation.CreateTable -> OracleDiffTableOps.renderCreateTable(op, ctx)
            is DiffOperation.DropTable -> OracleDiffTableOps.renderDropTable(op, ctx)
            is DiffOperation.RenameTable -> OracleDiffTableOps.renderRenameTable(op, ctx)
            is DiffOperation.AddColumn -> OracleDiffTableOps.renderAddColumn(op, ctx)
            is DiffOperation.DropColumn -> OracleDiffTableOps.renderDropColumn(op, ctx)
            is DiffOperation.RenameColumn -> OracleDiffTableOps.renderRenameColumn(op, ctx)
            is DiffOperation.AlterColumnType -> OracleDiffTableOps.renderAlterColumnType(op, ctx)
            is DiffOperation.AlterColumnNullability -> OracleDiffTableOps.renderAlterColumnNullability(op, ctx)
            is DiffOperation.AlterColumnDefault -> OracleDiffTableOps.renderAlterColumnDefault(op, ctx)
            is DiffOperation.AddPrimaryKey -> OracleDiffTableOps.renderAddPrimaryKey(op, ctx)
            is DiffOperation.DropPrimaryKey -> OracleDiffTableOps.renderDropPrimaryKey(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised TABLE but renderTableOp does not handle it")
        }
    }

    private fun renderObjectOp(op: DiffOperation, ctx: OracleDiffRenderContext) {
        when (op) {
            is DiffOperation.AddConstraint -> OracleDiffObjectOps.renderAddConstraint(op, ctx)
            is DiffOperation.DropConstraint -> OracleDiffObjectOps.renderDropConstraint(op, ctx)
            is DiffOperation.AddIndex -> OracleDiffObjectOps.renderAddIndex(op, ctx)
            is DiffOperation.DropIndex -> OracleDiffObjectOps.renderDropIndex(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised OBJECT but renderObjectOp does not handle it")
        }
    }

    private fun renderViewOrTypeOp(op: DiffOperation, ctx: OracleDiffRenderContext) {
        when (op) {
            is DiffOperation.CreateView -> OracleDiffViewOps.renderCreateView(op, ctx)
            is DiffOperation.ReplaceView -> OracleDiffViewOps.renderReplaceView(op, ctx)
            is DiffOperation.DropView -> OracleDiffViewOps.renderDropView(op, ctx)
            is DiffOperation.RenameView -> OracleDiffViewOps.renderRenameView(op, ctx)
            is DiffOperation.CreateCustomType -> OracleDiffCustomTypeOps.renderCreateCustomType(op, ctx)
            is DiffOperation.AlterCustomType -> OracleDiffCustomTypeOps.renderAlterCustomType(op, ctx)
            is DiffOperation.DropCustomType -> OracleDiffCustomTypeOps.renderDropCustomType(op, ctx)
            else -> error("Op ${op::class.simpleName} is categorised VIEW_OR_TYPE but renderViewOrTypeOp does not handle it")
        }
    }

    private fun markUnsupported(op: DiffOperation, ctx: OracleDiffRenderContext) {
        ctx.skip(op, "Operation ${op::class.simpleName} is not yet supported by the Oracle migrate renderer.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    private enum class OpCategory { TABLE, OBJECT, VIEW_OR_TYPE, UNSUPPORTED }
}
