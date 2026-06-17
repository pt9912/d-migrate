package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.CheckPreflightGate
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ViewQueryTransformer
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier

/**
 * Per-operation renderers for constraint / index / view / custom-
 * type DDL. Stateless: takes a [PostgresDiffRenderContext] and
 * writes statements / diagnostics back into it.
 */
internal object PostgresDiffOtherOps {

    fun renderAddConstraint(op: DiffOperation.AddConstraint, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT ${ctx.sql.quote(op.constraint.name)};")
            return
        }
        if (op.constraint.type == ConstraintType.EXCLUDE && blockUnsupportedExcludeOpClass(op, ctx)) return
        val line = ctx.sql.constraintLine(op.constraint)
        if (line == null) {
            ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        if (op.constraint.type == ConstraintType.CHECK && blockOnCheckPreflight(op, ctx)) return
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
    }

    /**
     * F.5 Sub-Slice F: surface
     * [PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE]
     * when an EXCLUDE element references an operator class, COLLATE
     * clause or ordering token the first F.5 tranche does not round-
     * trip. Returns `true` when a block was emitted.
     */
    private fun blockUnsupportedExcludeOpClass(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
    ): Boolean {
        val constraint = when (op) {
            is DiffOperation.AddConstraint -> op.constraint
            is DiffOperation.DropConstraint -> op.constraint
            else -> return false
        }
        val verdict = ExcludeOperatorClassGate.verdict(constraint.expression)
        if (verdict !is ExcludeOperatorClassGate.Verdict.Blocked) return false
        ctx.skip(
            op,
            "EXCLUDE constraint '${constraint.name}' on '${op.objectRef.path.firstOrNull() ?: ""}' " +
                "uses unsupported element '${verdict.offendingElement}': ${verdict.reason}. " +
                "Rewrite the constraint to use bare column names or parenthesised expressions " +
                "with the default operator class, or add it manually outside the migration.",
            code = PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE,
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return true
    }

    /**
     * F.5 Sub-Slice E.3 (2026-05-19): gate `AddConstraint(CHECK)` UP
     * emission against the live-data preflight declaration carried in
     * `DdlGenerationOptions.checkPreflights`. Returns `true` when the
     * gate emitted a block.
     *
     * The shared [CheckPreflightGate] resolves the per-status routing
     * (PASSED / NOT_RUN_* → proceed; FAILED → CHECK_PREFLIGHT_VIOLATIONS;
     * PROBE_RUNTIME_ERROR → CHECK_PREFLIGHT_RUNTIME_ERROR). Match key
     * is the operation id only; the gate planner emits exactly one
     * declaration per AddConstraint(CHECK) op.
     */
    private fun blockOnCheckPreflight(
        op: DiffOperation.AddConstraint,
        ctx: PostgresDiffRenderContext,
    ): Boolean = when (val decision = CheckPreflightGate.decide(op.id, ctx.options.checkPreflights)) {
        CheckPreflightGate.Decision.Proceed -> false
        is CheckPreflightGate.Decision.Block -> {
            ctx.skip(op, decision.message, code = decision.code)
            ctx.addBlocker(decision.reason, operationIds = setOf(op.id))
            true
        }
    }

    fun renderDropConstraint(op: DiffOperation.DropConstraint, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            if (op.constraint.type == ConstraintType.EXCLUDE &&
                blockUnsupportedExcludeOpClass(op, ctx)
            ) {
                return
            }
            val line = ctx.sql.constraintLine(op.constraint)
            if (line == null) {
                blockDropConstraintDown(op, ctx)
                return
            }
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT ${ctx.sql.quote(op.constraint.name)};")
    }

    /**
     * F.5 Sub-Slice F: the Down-pass of a `DropConstraint` can fail
     * for two distinct reasons that must surface as different
     * blockers in the report:
     *
     * - CHECK / EXCLUDE without a persisted expression — the renderer
     *   has no way to reconstruct the inverse `ADD CONSTRAINT … CHECK
     *   (expr)`, so the rollback is genuinely impossible
     *   (`ROLLBACK_NOT_POSSIBLE`). The `ConstraintReplaceContract`
     *   already classified the op as `NOT_REVERSIBLE`; the renderer
     *   matches that classification on the down side.
     * - Any other constraint type the dialect cannot render
     *   (`DIALECT_UNSUPPORTED_OPERATION`). UNIQUE / FOREIGN_KEY hit
     *   this when columns / references are missing; the inverse is
     *   not architecturally rolled back here, but the dialect simply
     *   does not know how to encode the constraint.
     */
    private fun blockDropConstraintDown(
        op: DiffOperation.DropConstraint,
        ctx: PostgresDiffRenderContext,
    ) {
        if (isRollbackBlockedRawSql(op.constraint)) {
            ctx.skip(
                op,
                "Operation ${op.id} drops CHECK/EXCLUDE constraint '${op.constraint.name}' on " +
                    "'${op.objectRef.path.firstOrNull() ?: ""}' but the prior expression is not " +
                    "available; the renderer cannot reconstruct the inverse ADD CONSTRAINT.",
                code = "CONSTRAINT_ROLLBACK_EXPRESSION_MISSING",
            )
            ctx.addBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE, operationIds = setOf(op.id))
            return
        }
        ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    private fun isRollbackBlockedRawSql(c: ConstraintDefinition): Boolean =
        (c.type == ConstraintType.CHECK || c.type == ConstraintType.EXCLUDE) &&
            c.expression.isNullOrBlank()

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            // DROP INDEX: AccessExclusiveLock on the index,
            // ShareUpdateExclusiveLock on the parent table. Use the
            // default TABLE_EXCLUSIVE hint (conservative).
            ctx.emit(
                op,
                "DROP INDEX ${ctx.sql.quote(ctx.sql.effectiveIndexName(table, op.index))};",
            )
            return
        }
        if (!guardSpatialIndex(op, op.index, ctx, table)) return
        if (!guardIndexOpClass(op, op.index, ctx, table)) return
        // CREATE INDEX (non-CONCURRENTLY): SHARE lock — writes block,
        // reads proceed. Plan-2 §A.1.
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index), PostgresDiffRenderContext.POSTGRES_CREATE_INDEX_HINTS)
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            if (!guardSpatialIndex(op, op.index, ctx, table)) return
            if (!guardIndexOpClass(op, op.index, ctx, table)) return
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index), PostgresDiffRenderContext.POSTGRES_CREATE_INDEX_HINTS)
            return
        }
        ctx.emit(op, "DROP INDEX ${ctx.sql.quote(ctx.sql.effectiveIndexName(table, op.index))};")
    }

    /**
     * I-08: block a GIN/GIST index whose column type has no default operator
     * class (e.g. tsvector degraded to text) instead of emitting invalid
     * `USING gist (text_col)` DDL. Returns false (and blocks) when unrenderable.
     */
    private fun guardIndexOpClass(
        op: DiffOperation,
        index: IndexDefinition,
        ctx: PostgresDiffRenderContext,
        table: String,
    ): Boolean {
        val offending = ctx.indexColumnMissingOpClass(table, index) ?: return true
        ctx.skip(
            op,
            "Operation ${op.id} creates a ${index.type.name} index on `$table`.`$offending`, but that " +
                "column type has no default ${index.type.name} operator class in PostgreSQL " +
                "(e.g. a tsvector column degraded to text). Restore the type or add an operator class.",
            code = "INDEX_OPCLASS_MISSING",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return false
    }

    private fun guardSpatialIndex(
        op: DiffOperation,
        index: IndexDefinition,
        ctx: PostgresDiffRenderContext,
        table: String,
    ): Boolean {
        if (!ctx.indexTouchesGeometry(table, index)) return true
        if (index.type != IndexType.GIST) {
            ctx.skip(
                op,
                "Operation ${op.id} targets a geometry-column index on `$table`, but index type " +
                    "${index.type.name} is not a supported PostgreSQL spatial index. Use GIST or block manually.",
                code = "SPATIAL_INDEX_UNSUPPORTED",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        return ctx.requireExtension(op, "postgis", "spatial index on `$table`")
    }

    fun renderCreateCustomType(op: DiffOperation.CreateCustomType, ctx: PostgresDiffRenderContext) {
        if (op.customType.kind != CustomTypeKind.ENUM) {
            ctx.skip(op, "CustomType kind ${op.customType.kind} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        val name = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            // DROP TYPE: catalog-only on pg_type, no user-table lock.
            ctx.emit(op, "DROP TYPE ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
            return
        }
        // CREATE TYPE: catalog-only on pg_type. Plan-2 §A.1.
        ctx.emit(op, ctx.sql.createEnumTypeSql(name, op.customType), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderDropCustomType(op: DiffOperation.DropCustomType, ctx: PostgresDiffRenderContext) {
        if (op.customType.kind != CustomTypeKind.ENUM) {
            ctx.skip(op, "CustomType kind ${op.customType.kind} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        val name = op.objectRef.rootName
        ctx.emit(op, "DROP TYPE ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderCreateView(op: DiffOperation.CreateView, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
            return
        }
        if (blockNonPortableView(op, op.view, name, ctx)) return
        // CREATE VIEW: catalog write, AccessShareLock on referenced
        // relations during planning, no user-table lock. Plan-2 §A.1.
        ctx.emit(op, ctx.sql.createViewSql(name, op.view), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderReplaceView(op: DiffOperation.ReplaceView, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        if (op.before.materialized || op.after.materialized || target.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        if (!guardViewSignatureCompatibility(op, ctx, name)) return
        if (blockNonPortableView(op, target, name, ctx)) return
        ctx.emit(op, ctx.sql.replaceViewSql(name, target), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    /**
     * I-09: cross-dialect view bodies that PostgreSQL cannot parse (foreign
     * quoting / dialect-specific functions) are blocked with E053 instead of
     * emitting invalid `CREATE VIEW` DDL. Returns true when the op was blocked.
     */
    private fun blockNonPortableView(
        op: DiffOperation,
        view: ViewDefinition,
        name: String,
        ctx: PostgresDiffRenderContext,
    ): Boolean {
        val query = view.query ?: return false
        val verdict = ViewQueryTransformer(DatabaseDialect.POSTGRESQL).assessPortability(query, view.sourceDialect)
        if (verdict.portable) return false
        ctx.skip(
            op,
            "View '$name' body is not portable to PostgreSQL (${verdict.reason}); d-migrate does not " +
                "translate view bodies between dialects. Rewrite the view for PostgreSQL and re-run.",
            code = "E053",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
        return true
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    /**
     * F.4 Sub-Slice A.2 Teil 2: PostgreSQL native view rename.
     * `ALTER VIEW <fromName> RENAME TO <toName>` — left is the existing
     * identity, right is the new visible name. Materialized views are
     * blocked at the Mapper/Planner phase via
     * `OBJECT_RENAME_UNSUPPORTED`, so a `RenameView` op here always
     * targets a regular (non-materialized) view.
     */
    fun renderRenameView(op: DiffOperation.RenameView, ctx: PostgresDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == PostgresRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            "ALTER VIEW ${ctx.sql.quote(oldName)} RENAME TO ${ctx.sql.quote(newName)};",
            PostgresDiffRenderContext.POSTGRES_METADATA_HINTS,
        )
    }

    private fun blockMaterializedView(
        op: DiffOperation,
        ctx: PostgresDiffRenderContext,
        name: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} targets materialized view '$name' for dialect postgresql " +
                "(materialized=true). Diff-based materialized-view migrations are blocked until " +
                "a dedicated refresh/staleness contract exists.",
            code = "MATERIALIZED_VIEW_DIFF_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    private fun guardViewSignatureCompatibility(
        op: DiffOperation.ReplaceView,
        ctx: PostgresDiffRenderContext,
        name: String,
    ): Boolean {
        val before = op.before.columns
        val after = op.after.columns
        if (before == null || after == null) {
            ctx.skip(
                op,
                "Operation ${op.id} replaces PostgreSQL view '$name' without visible view-column " +
                    "signature metadata. CREATE OR REPLACE VIEW is blocked until column count, order, " +
                    "names and visible types are known.",
                code = "VIEW_SIGNATURE_UNKNOWN",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        if (before != after) {
            ctx.skip(
                op,
                "Operation ${op.id} replaces PostgreSQL view '$name' with an incompatible visible " +
                    "signature. CREATE OR REPLACE VIEW is only renderable when view columns keep the " +
                    "same count, order, names and visible types.",
                code = "VIEW_SIGNATURE_INCOMPATIBLE",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        return true
    }
}
