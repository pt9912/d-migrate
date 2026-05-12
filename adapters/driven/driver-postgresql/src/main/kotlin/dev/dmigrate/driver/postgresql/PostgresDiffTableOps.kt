package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.migration.MigrationBlockedReason

/**
 * Per-operation renderers for table / column / primary-key DDL.
 * Stateless: takes a [PostgresDiffRenderContext] (which carries
 * direction + SQL builders) and writes statements / diagnostics
 * back into it.
 */
internal object PostgresDiffTableOps {

    fun renderCreateTable(op: DiffOperation.CreateTable, ctx: PostgresDiffRenderContext) {
        val tableName = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
            return
        }
        if (op.table.hasGeometryColumns() &&
            !ctx.requireExtension(op, POSTGIS_EXTENSION, "geometry columns on table `$tableName`")
        ) {
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

    fun renderDropTable(op: DiffOperation.DropTable, ctx: PostgresDiffRenderContext) {
        val tableName = op.objectRef.rootName
        val text = if (ctx.direction == PostgresRenderDirection.DOWN) {
            // DropTable is NOT_REVERSIBLE — render-down is filtered upstream;
            // the placeholder keeps the emit path total.
            "-- DropTable is NOT_REVERSIBLE; refusing to render an inverse."
        } else {
            "DROP TABLE ${ctx.sql.quote(tableName)};"
        }
        ctx.emit(op, text)
    }

    fun renderAddColumn(op: DiffOperation.AddColumn, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        if (op.column.type is NeutralType.Geometry &&
            !ctx.requireExtension(op, POSTGIS_EXTENSION, "geometry column `$table.$column`")
        ) {
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column)};")
    }

    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    fun renderAlterColumnType(op: DiffOperation.AlterColumnType, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetType = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        if (targetType is NeutralType.Geometry &&
            !ctx.requireExtension(op, POSTGIS_EXTENSION, "geometry type `$table.$column`")
        ) {
            return
        }
        val usingExpression = if (ctx.sql.isSafeImplicitCast(op.before, op.after)) {
            null
        } else {
            PostgresUsingOverlayResolver.resolve(op, ctx) ?: return
        }
        val usingClause = usingExpression?.let { " USING $it" }.orEmpty()
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                "TYPE ${ctx.sql.toSql(targetType)}$usingClause;",
        )
    }

    fun renderAlterColumnNullability(op: DiffOperation.AlterColumnNullability, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val targetRequired = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        val verb = if (targetRequired) "SET NOT NULL" else "DROP NOT NULL"
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} $verb;")
    }

    fun renderAlterColumnDefault(op: DiffOperation.AlterColumnDefault, ctx: PostgresDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val target = if (ctx.direction == PostgresRenderDirection.UP) op.after else op.before
        val text = if (target == null) {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} DROP DEFAULT;"
        } else {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER COLUMN ${ctx.sql.quote(column)} " +
                "SET DEFAULT ${ctx.sql.toDefaultSql(target, NeutralType.Text())};"
        }
        ctx.emit(op, text)
    }

    fun renderAddPrimaryKey(op: DiffOperation.AddPrimaryKey, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            emitDropPkAdvisory(op, ctx)
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT IF EXISTS ${ctx.sql.quote(table + "_pkey")};")
            return
        }
        val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD PRIMARY KEY ($cols);")
    }

    fun renderDropPrimaryKey(op: DiffOperation.DropPrimaryKey, ctx: PostgresDiffRenderContext) {
        val table = op.objectRef.rootName
        if (ctx.direction == PostgresRenderDirection.DOWN) {
            val cols = op.columns.joinToString(", ") { ctx.sql.quote(it) }
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD PRIMARY KEY ($cols);")
            return
        }
        emitDropPkAdvisory(op, ctx)
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP CONSTRAINT IF EXISTS ${ctx.sql.quote(table + "_pkey")};")
    }

    /**
     * The first-matrix DropPrimaryKey path assumes the auto-named PK
     * constraint follows PostgreSQL's `<table>_pkey` convention. If
     * the source schema explicitly named its PK (e.g. `CONSTRAINT
     * pk_users PRIMARY KEY (...)`), the `IF EXISTS` swallows the
     * mismatch and the PK survives; the next ADD will fail. Surface
     * an advisory diagnostic so operators can verify before running.
     *
     * TODO Phase F: enrich `DropPrimaryKey` with the explicit
     * constraint name (requires extending the schema model to track
     * PK constraint names; see Plan §11.2).
     */
    private fun emitDropPkAdvisory(op: DiffOperation, ctx: PostgresDiffRenderContext) {
        ctx.addInfoDiagnostic(
            code = "PG_PK_NAME_CONVENTION",
            operationId = op.id,
            message = "DropPrimaryKey for `${op.objectRef.rootName}` assumes the auto-named " +
                "constraint `${op.objectRef.rootName}_pkey`. Verify the source schema's actual " +
                "PK constraint name; a non-conventional name will let the IF EXISTS swallow the " +
                "mismatch and leave the PK in place.",
        )
    }

    private fun TableDefinition.hasGeometryColumns(): Boolean =
        columns.values.any { it.type is NeutralType.Geometry }

    private const val POSTGIS_EXTENSION = "postgis"
}
