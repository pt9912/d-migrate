package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for constraint / index / view / custom-
 * type DDL (MySQL flavor). Differs from the PostgreSQL renderer in:
 *
 * - Drop-constraint syntax: `DROP FOREIGN KEY` for FK, `DROP INDEX`
 *   for UNIQUE.
 * - `DROP INDEX … ON tbl` syntax for index drop.
 * - Standalone ENUM custom types are not supported by MySQL; the
 *   first-matrix renderer surfaces them as
 *   `DIALECT_UNSUPPORTED_OPERATION`.
 */
internal object MysqlDiffOtherOps {

    fun renderAddConstraint(op: DiffOperation.AddConstraint, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            val drop = ctx.sql.dropConstraintSql(table, op.constraint)
            if (drop == null) {
                ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
                ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
                return
            }
            ctx.emit(op, drop)
            return
        }
        val line = ctx.sql.constraintLine(op.constraint)
        if (line == null) {
            ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
    }

    fun renderDropConstraint(op: DiffOperation.DropConstraint, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            val line = ctx.sql.constraintLine(op.constraint)
            if (line == null) {
                ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
                ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
                return
            }
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
            return
        }
        val drop = ctx.sql.dropConstraintSql(table, op.constraint)
        if (drop == null) {
            ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        ctx.emit(op, drop)
    }

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
            return
        }
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
            return
        }
        ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
    }

    fun renderCreateCustomType(op: DiffOperation.CreateCustomType, ctx: MysqlDiffRenderContext) {
        // MySQL has no standalone CREATE TYPE; ENUM is a column-level type.
        ctx.skip(op, "MySQL does not support standalone custom types; not in the first matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    fun renderDropCustomType(op: DiffOperation.DropCustomType, ctx: MysqlDiffRenderContext) {
        ctx.skip(op, "MySQL does not support standalone custom types; not in the first matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    fun renderCreateView(op: DiffOperation.CreateView, ctx: MysqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};")
            return
        }
        ctx.emit(op, ctx.sql.createViewSql(name, op.view))
    }

    fun renderReplaceView(op: DiffOperation.ReplaceView, ctx: MysqlDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        if (op.before.materialized || op.after.materialized || target.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        ctx.emit(op, ctx.sql.replaceViewSql(name, target))
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: MysqlDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};")
    }

    private fun blockMaterializedView(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        name: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} targets materialized view '$name' for dialect mysql " +
                "(materialized=true). Diff-based materialized-view migrations are blocked until " +
                "a dedicated emulation/refresh contract exists.",
            code = "MATERIALIZED_VIEW_DIFF_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }
}
