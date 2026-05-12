package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for table / column / primary-key DDL
 * (MySQL flavor). The shape mirrors `PostgresDiffTableOps`; the
 * differences are in how MySQL spells column-altering ops:
 *
 * - `AlterColumnType` uses `MODIFY COLUMN` with the new type.
 * - `AlterColumnNullability` is currently blocked: MySQL's
 *   `MODIFY COLUMN` requires the full type, which the standalone
 *   nullability op doesn't carry. A future planner refinement
 *   could attach the current type to the op.
 * - `AlterColumnDefault` uses `ALTER col SET/DROP DEFAULT`
 *   (MySQL ≥ 8.0).
 */
internal object MysqlDiffTableOps {

    fun renderCreateTable(op: DiffOperation.CreateTable, ctx: MysqlDiffRenderContext) {
        val tableName = op.objectRef.rootName
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
            return
        }
        if (op.table.indices.any { it.referencesGeometry(op.table) }) {
            blockSpatialIndex(op, ctx, tableName)
            return
        }
        val lines = mutableListOf<String>()
        for ((colName, col) in op.table.columns.entries.sortedBy { it.key }) {
            lines += "    " + ctx.sql.columnLine(colName, col)
        }
        if (op.table.primaryKey.isNotEmpty()) {
            lines += "    PRIMARY KEY (" + op.table.primaryKey.joinToString(", ") { ctx.sql.quote(it) } + ")"
        }
        for (c in op.table.constraints.sortedBy { it.name }) {
            ctx.sql.constraintLine(c)?.let { lines += "    $it" }
        }
        val text = buildString {
            append("CREATE TABLE ").append(ctx.sql.quote(tableName)).append(" (\n")
            append(lines.joinToString(",\n"))
            append("\n);")
        }
        ctx.emit(op, text)
        for (idx in op.table.indices) {
            ctx.emit(op, ctx.sql.createIndexSql(tableName, idx))
        }
    }

    fun renderDropTable(op: DiffOperation.DropTable, ctx: MysqlDiffRenderContext) {
        val tableName = op.objectRef.rootName
        val text = if (ctx.direction == MysqlRenderDirection.DOWN) {
            "-- DropTable is NOT_REVERSIBLE; refusing to render an inverse."
        } else {
            "DROP TABLE ${ctx.sql.quote(tableName)};"
        }
        ctx.emit(op, text)
    }

    fun renderAddColumn(op: DiffOperation.AddColumn, ctx: MysqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column)};")
    }

    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: MysqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    fun renderAlterColumnType(op: DiffOperation.AlterColumnType, ctx: MysqlDiffRenderContext) {
        if (!ctx.sql.isSafeImplicitCast(op.before, op.after)) {
            ctx.skip(op, "AlterColumnType from ${op.before} to ${op.after} requires an explicit conversion.")
            ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
            return
        }
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetType = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        // MySQL note: MODIFY COLUMN replaces the *whole* column spec; nullability and default
        // are not carried over. The first matrix accepts this caveat — only ops that change
        // the type alone use this path. NULL/DEFAULT changes go through their dedicated ops.
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} MODIFY COLUMN ${ctx.sql.quote(column)} ${ctx.sql.toSql(targetType)};",
        )
    }

    fun renderAlterColumnNullability(op: DiffOperation.AlterColumnNullability, ctx: MysqlDiffRenderContext) {
        // MySQL has no SET/DROP NOT NULL — MODIFY COLUMN needs the full type, which
        // this op doesn't carry. Surface as DIALECT_UNSUPPORTED until the planner can
        // attach the current type to nullability ops.
        ctx.skip(op, "MySQL requires the column type to change nullability; not in the first matrix.")
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    fun renderAlterColumnDefault(op: DiffOperation.AlterColumnDefault, ctx: MysqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val target = if (ctx.direction == MysqlRenderDirection.UP) op.after else op.before
        val text = if (target == null) {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER ${ctx.sql.quote(column)} DROP DEFAULT;"
        } else {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER ${ctx.sql.quote(column)} " +
                "SET DEFAULT ${ctx.sql.toDefaultSql(target, NeutralType.Text())};"
        }
        ctx.emit(op, text)
    }

    fun renderAddPrimaryKey(op: DiffOperation.AddPrimaryKey, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP PRIMARY KEY;")
            return
        }
        val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD PRIMARY KEY ($cols);")
    }

    fun renderDropPrimaryKey(op: DiffOperation.DropPrimaryKey, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == MysqlRenderDirection.DOWN) {
            val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD PRIMARY KEY ($cols);")
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP PRIMARY KEY;")
    }

    private fun dev.dmigrate.core.model.IndexDefinition.referencesGeometry(table: TableDefinition): Boolean =
        columnNames.any { name -> table.columns[name]?.type is NeutralType.Geometry }

    private fun blockSpatialIndex(op: DiffOperation, ctx: MysqlDiffRenderContext, tableName: String) {
        ctx.skip(
            op,
            "Operation ${op.id} would create table `$tableName` with an index on a geometry column. " +
                "MySQL requires SPATIAL INDEX semantics, which the neutral index model cannot express yet.",
            code = "SPATIAL_INDEX_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }
}
