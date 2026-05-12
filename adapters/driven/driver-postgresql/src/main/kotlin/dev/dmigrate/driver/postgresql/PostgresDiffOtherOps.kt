package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.driver.migration.MigrationBlockedReason

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
        val line = ctx.sql.constraintLine(op.constraint)
        if (line == null) {
            ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
    }

    fun renderDropConstraint(op: DiffOperation.DropConstraint, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            val line = ctx.sql.constraintLine(op.constraint)
            if (line == null) {
                ctx.skip(op, "Constraint type ${op.constraint.type} is not in the first matrix.")
                ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
                return
            }
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD $line;")
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT ${ctx.sql.quote(op.constraint.name)};")
    }

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
        // CREATE INDEX (non-CONCURRENTLY): SHARE lock — writes block,
        // reads proceed. Plan-2 §A.1.
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index), PostgresDiffRenderContext.POSTGRES_CREATE_INDEX_HINTS)
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            if (!guardSpatialIndex(op, op.index, ctx, table)) return
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index), PostgresDiffRenderContext.POSTGRES_CREATE_INDEX_HINTS)
            return
        }
        ctx.emit(op, "DROP INDEX ${ctx.sql.quote(ctx.sql.effectiveIndexName(table, op.index))};")
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
        ctx.emit(op, ctx.sql.replaceViewSql(name, target), PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: PostgresDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};", PostgresDiffRenderContext.POSTGRES_METADATA_HINTS)
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
