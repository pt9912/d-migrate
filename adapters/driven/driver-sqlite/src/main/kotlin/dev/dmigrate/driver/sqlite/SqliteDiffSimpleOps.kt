package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation

/**
 * Per-operation renderers for SQLite operations that *don't* need a
 * table rebuild — the D.4.a first-matrix scope. Anything that
 * requires `ALTER COLUMN` / constraint reshape / PK reshape goes
 * through `SqliteDiffRenderContext.deferToRebuild` (D.4.b).
 */
internal object SqliteDiffSimpleOps {

    fun renderCreateTable(op: DiffOperation.CreateTable, ctx: SqliteDiffRenderContext) {
        val tableName = op.objectRef.rootName
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
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

    fun renderDropTable(op: DiffOperation.DropTable, ctx: SqliteDiffRenderContext) {
        val tableName = op.objectRef.rootName
        val text = if (ctx.direction == SqliteRenderDirection.DOWN) {
            "-- DropTable is NOT_REVERSIBLE; refusing to render an inverse."
        } else {
            "DROP TABLE ${ctx.sql.quote(tableName)};"
        }
        ctx.emit(op, text)
    }

    fun renderAddColumn(op: DiffOperation.AddColumn, ctx: SqliteDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            // SQLite ≥ 3.35.0 supports DROP COLUMN. The runner enforces the version policy.
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column)};")
    }

    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: SqliteDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        // SQLite ≥ 3.35.0 supports DROP COLUMN; the planner has already filtered FK-bearing cases.
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
    }

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: SqliteDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.dropIndexSql(op.index, table))
            return
        }
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: SqliteDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
            return
        }
        ctx.emit(op, ctx.sql.dropIndexSql(op.index, table))
    }

    fun renderCreateView(op: DiffOperation.CreateView, ctx: SqliteDiffRenderContext) {
        val name = op.objectRef.rootName
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};")
            return
        }
        ctx.emit(op, ctx.sql.createViewSql(name, op.view))
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: SqliteDiffRenderContext) {
        val name = op.objectRef.rootName
        ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};")
    }

    /**
     * SQLite has no `CREATE OR REPLACE VIEW`. The renderer emits two
     * statements (DROP + CREATE) both tagged with the same op-id, so
     * the runner can still attribute failures back to the
     * `ReplaceView` operation. Plan §6.4 / Plan §6.1.
     */
    fun renderReplaceView(op: DiffOperation.ReplaceView, ctx: SqliteDiffRenderContext) {
        val name = op.objectRef.rootName
        val target = if (ctx.direction == SqliteRenderDirection.UP) op.after else op.before
        ctx.emit(op, "DROP VIEW IF EXISTS ${ctx.sql.quote(name)};")
        ctx.emit(op, ctx.sql.createViewSql(name, target))
    }
}
