package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.driver.MysqlCheckEnforcementResolver
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier

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
        if (op.constraint.type == ConstraintType.EXCLUDE) {
            blockExcludeOnMysql(op, ctx)
            return
        }
        val table = op.objectRef.path[0]
        val isLogicalAdd = ctx.direction == MysqlRenderDirection.UP
        if (op.constraint.type == ConstraintType.CHECK && !gateMysqlCheck(op, ctx, isLogicalAdd)) {
            return
        }
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            emitDropConstraint(op, ctx, table)
            return
        }
        emitAddConstraint(op, ctx, table)
    }

    fun renderDropConstraint(op: DiffOperation.DropConstraint, ctx: MysqlDiffRenderContext) {
        if (op.constraint.type == ConstraintType.EXCLUDE) {
            blockExcludeOnMysql(op, ctx)
            return
        }
        val table = op.objectRef.path[0]
        val isLogicalAdd = ctx.direction == MysqlRenderDirection.DOWN
        if (op.constraint.type == ConstraintType.CHECK && !gateMysqlCheck(op, ctx, isLogicalAdd)) {
            return
        }
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            emitAddConstraint(op, ctx, table)
            return
        }
        emitDropConstraint(op, ctx, table)
    }

    private fun emitAddConstraint(op: DiffOperation, ctx: MysqlDiffRenderContext, table: String) {
        val constraint = when (op) {
            is DiffOperation.AddConstraint -> op.constraint
            is DiffOperation.DropConstraint -> op.constraint
            else -> error("emitAddConstraint called with unsupported op ${op::class.simpleName}")
        }
        val line = ctx.sql.constraintLine(constraint)
        if (line == null) {
            ctx.skip(op, "Constraint type ${constraint.type} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
    }

    private fun emitDropConstraint(op: DiffOperation, ctx: MysqlDiffRenderContext, table: String) {
        val constraint = when (op) {
            is DiffOperation.AddConstraint -> op.constraint
            is DiffOperation.DropConstraint -> op.constraint
            else -> error("emitDropConstraint called with unsupported op ${op::class.simpleName}")
        }
        val drop = ctx.sql.dropConstraintSql(table, constraint)
        if (drop == null) {
            ctx.skip(op, "Constraint type ${constraint.type} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        ctx.emit(op, drop)
    }

    /**
     * F.5 Sub-Slice C: gate MySQL CHECK rendering against the live
     * server's enforcement capability. Returns `true` when the
     * renderer may proceed, `false` after emitting the block.
     *
     * Logical-add ops (ADD CHECK on Up, ADD CHECK on Down-of-Drop)
     * need both `known` and `enforced`: a parsed-but-not-evaluated
     * CHECK is silently no-op and would mask real data violations.
     *
     * Logical-drop ops only need `known`: dropping a never-enforced
     * constraint is harmless, but the renderer still needs proof the
     * server supports the `DROP CHECK` syntax (MySQL ≥ 8.0.16 /
     * MariaDB ≥ 10.2.1).
     */
    private fun gateMysqlCheck(
        op: DiffOperation,
        ctx: MysqlDiffRenderContext,
        isLogicalAdd: Boolean,
    ): Boolean {
        val cap = MysqlCheckEnforcementResolver.resolve(ctx.options.mysqlServerVersion)
        if (!cap.known) {
            ctx.skip(
                op,
                "MySQL CHECK rendering is blocked: ${cap.rationale}. Set --mysql-server-version " +
                    "or run against a live MySQL ≥ 8.0.16 / MariaDB ≥ 10.2.1 target.",
                code = PlannerBlockerClassifier.MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        if (isLogicalAdd && !cap.enforced) {
            ctx.skip(
                op,
                "MySQL CHECK rendering is blocked: ${cap.rationale}. The server parses the clause " +
                    "but never evaluates it, so the migration would silently no-op.",
                code = PlannerBlockerClassifier.MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE,
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        return true
    }

    private fun blockExcludeOnMysql(op: DiffOperation, ctx: MysqlDiffRenderContext) {
        ctx.skip(
            op,
            "MySQL does not support EXCLUDE constraints (PostgreSQL-only feature). " +
                "Use a UNIQUE index plus a CHECK constraint or model the invariant in application code.",
            code = PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
            return
        }
        if (ctx.indexTouchesGeometry(table, op.index)) {
            blockSpatialIndex(op, ctx, table)
            return
        }
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            if (ctx.indexTouchesGeometry(table, op.index)) {
                blockSpatialIndex(op, ctx, table)
                return
            }
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
            return
        }
        ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
    }

    private fun blockSpatialIndex(op: DiffOperation, ctx: MysqlDiffRenderContext, table: String) {
        ctx.skip(
            op,
            "Operation ${op.id} targets an index on a geometry column in `$table`. MySQL requires " +
                "SPATIAL INDEX semantics, which the neutral index model cannot express yet.",
            code = "SPATIAL_INDEX_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
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

    /**
     * F.4 Sub-Slice B: MySQL native view rename. Views share the table
     * namespace, so the rename uses `RENAME TABLE` rather than
     * `ALTER VIEW` (which in MySQL only supports body changes via
     * `CREATE OR REPLACE VIEW`, not name changes). The Mapper-/Planner-
     * phase has already filtered out materialized views via
     * `MysqlObjectRenamePolicy` (MySQL has no MV support).
     */
    fun renderRenameView(op: DiffOperation.RenameView, ctx: MysqlDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, "RENAME TABLE ${ctx.sql.quote(oldName)} TO ${ctx.sql.quote(newName)};")
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
