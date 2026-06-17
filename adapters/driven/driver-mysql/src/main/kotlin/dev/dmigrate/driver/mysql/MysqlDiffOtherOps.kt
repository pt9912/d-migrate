package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.CheckPreflightGate
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlCheckEnforcementResolver
import dev.dmigrate.driver.ViewQueryTransformer
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.mysqlContext

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
        if (op.constraint.type == ConstraintType.CHECK && isLogicalAdd && blockOnCheckPreflight(op, ctx)) {
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
            // F.5 Sub-Slice F: Down of `DropConstraint(CHECK)` would
            // re-emit the original `ADD CONSTRAINT … CHECK (expr)`,
            // but a missing/blank expression makes that impossible —
            // surface `ROLLBACK_NOT_POSSIBLE` instead of the generic
            // dialect-unsupported blocker. EXCLUDE is already blocked
            // above; FOREIGN_KEY / UNIQUE keep their existing path.
            if (isRollbackBlockedRawSql(op.constraint)) {
                blockRollbackNotPossible(op, ctx, table)
                return
            }
            emitAddConstraint(op, ctx, table)
            return
        }
        emitDropConstraint(op, ctx, table)
    }

    /**
     * F.5 Sub-Slice F: see [renderDropConstraint]'s Down-branch — the
     * inverse cannot be rendered without the original CHECK / EXCLUDE
     * expression.
     */
    private fun blockRollbackNotPossible(
        op: DiffOperation.DropConstraint,
        ctx: MysqlDiffRenderContext,
        table: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} drops CHECK/EXCLUDE constraint '${op.constraint.name}' on " +
                "'$table' but the prior expression is not available; the renderer cannot " +
                "reconstruct the inverse ADD CONSTRAINT.",
            code = "CONSTRAINT_ROLLBACK_EXPRESSION_MISSING",
        )
        ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
    }

    /**
     * MySQL-side rollback predicate is CHECK-only: EXCLUDE has already
     * been short-circuited by [blockExcludeOnMysql] at the start of
     * [renderDropConstraint] (PostgreSQL-exclusive feature), so an
     * EXCLUDE op never reaches this gate. PostgreSQL handles both
     * CHECK and EXCLUDE in its sibling predicate; the two dialects
     * diverge here on purpose.
     */
    private fun isRollbackBlockedRawSql(c: ConstraintDefinition): Boolean =
        c.type == ConstraintType.CHECK && c.expression.isNullOrBlank()

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
        val cap = MysqlCheckEnforcementResolver.resolve(ctx.options.mysqlContext?.serverVersion)
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

    /**
     * F.5 Sub-Slice E.3 (2026-05-19): live-data preflight gate for
     * logical-add CHECK paths. Returns `true` when the gate emitted a
     * block. Mirrors the PG counterpart via the shared
     * [CheckPreflightGate].
     *
     * Order matters: this runs AFTER [gateMysqlCheck] succeeds, so the
     * operator never sees a preflight message they can't act on
     * (their server version must support enforced CHECK first;
     * otherwise the preflight is moot).
     */
    private fun blockOnCheckPreflight(
        op: DiffOperation.AddConstraint,
        ctx: MysqlDiffRenderContext,
    ): Boolean = when (val decision = CheckPreflightGate.decide(op.id, ctx.options.checkPreflights)) {
        CheckPreflightGate.Decision.Proceed -> false
        is CheckPreflightGate.Decision.Block -> {
            ctx.skip(op, decision.message, code = decision.code)
            ctx.addBlocker(decision.reason, operationIds = setOf(op.id))
            true
        }
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
        if (blockMissingPrefix(op, op.index, ctx, table)) return
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            if (ctx.indexTouchesGeometry(table, op.index)) {
                blockSpatialIndex(op, ctx, table)
                return
            }
            if (blockMissingPrefix(op, op.index, ctx, table)) return
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
            return
        }
        ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
    }

    /**
     * I-08: block an index on an unbounded TEXT/BLOB column without a prefix
     * length (ERROR 1170) instead of emitting invalid DDL. Returns true if blocked.
     */
    private fun blockMissingPrefix(
        op: DiffOperation,
        index: IndexDefinition,
        ctx: MysqlDiffRenderContext,
        table: String,
    ): Boolean {
        val offending = ctx.indexColumnNeedingPrefix(table, index) ?: return false
        ctx.skip(
            op,
            "Operation ${op.id} indexes TEXT/BLOB column `$table`.`$offending` without a prefix length; " +
                "MySQL requires one (e.g. `$offending(255)`, ERROR 1170). Add a prefix length and re-run.",
            code = "INDEX_PREFIX_MISSING",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return true
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
        if (blockNonPortableView(op, op.view, name, ctx)) return
        ctx.emit(op, ctx.sql.createViewSql(name, op.view))
    }

    fun renderReplaceView(op: DiffOperation.ReplaceView, ctx: MysqlDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        if (op.before.materialized || op.after.materialized || target.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        if (blockNonPortableView(op, target, name, ctx)) return
        ctx.emit(op, ctx.sql.replaceViewSql(name, target))
    }

    /**
     * I-09: cross-dialect view bodies MySQL cannot parse (foreign quoting /
     * dialect-specific functions) are blocked with E053 instead of emitting
     * invalid `CREATE VIEW` DDL. Returns true when the op was blocked.
     */
    private fun blockNonPortableView(
        op: DiffOperation,
        view: ViewDefinition,
        name: String,
        ctx: MysqlDiffRenderContext,
    ): Boolean {
        val query = view.query ?: return false
        val verdict = ViewQueryTransformer(DatabaseDialect.MYSQL).assessPortability(query, view.sourceDialect)
        if (verdict.portable) return false
        ctx.skip(
            op,
            "View '$name' body is not portable to MySQL (${verdict.reason}); d-migrate does not " +
                "translate view bodies between dialects. Rewrite the view for MySQL and re-run.",
            code = "E053",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return true
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
