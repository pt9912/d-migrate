package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.isSpatialGeometryIndex
import dev.dmigrate.core.model.inOrdinalOrder
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
        // E.3 Sub-Slice F: if any column carries a SequenceNextVal
        // default the mode gate must clear first; emitting CREATE
        // TABLE before the gate would leave a half-rendered op on
        // the report.
        val sequenceColumns = sequenceDefaultColumns(op.table)
        if (sequenceColumns.isNotEmpty() && !MysqlDiffSequenceOps.requireHelperModeForColumnDefault(op, ctx)) {
            return
        }
        val lines = mutableListOf<String>()
        for ((colName, col) in op.table.columns.inOrdinalOrder()) {
            lines += "    " + ctx.sql.columnLine(colName, col)
        }
        if (op.table.primaryKey.isNotEmpty()) {
            // I-07: an AUTO_INCREMENT column must lead a composite PK (ERROR 1075).
            val ordered = MysqlPrimaryKeyOrdering.autoIncrementFirst(op.table.primaryKey, op.table.columns)
            ordered.reordered?.let { moved ->
                ctx.warning(
                    op,
                    "AUTO_INCREMENT column '$moved' was moved to the front of the composite PRIMARY KEY " +
                        "of '$tableName' because MySQL requires it to be the leading key column (ERROR 1075).",
                    code = "W118",
                )
            }
            lines += "    PRIMARY KEY (" + ordered.columns.joinToString(", ") { ctx.sql.quote(it) } + ")"
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
            // VA3: ein Index auf eine Geometriespalte → MySQL SPATIAL INDEX (statt
            // die ganze Tabelle zu blocken). Normalisiert auch dialektfremde Typen
            // (z. B. PostGIS-GIST) spaltenbasiert auf SPATIAL.
            if (idx.referencesGeometry(op.table)) {
                ctx.emit(op, ctx.sql.createIndexSql(tableName, idx.copy(type = IndexType.SPATIAL)))
                ctx.info(
                    op,
                    "Index on a geometry column of `$tableName` emitted as MySQL SPATIAL INDEX; " +
                        "MySQL requires the geometry column to be NOT NULL.",
                    "SPATIAL_INDEX_REQUIRES_NOT_NULL",
                )
            } else {
                ctx.emit(op, ctx.sql.createIndexSql(tableName, idx))
            }
        }
        // Now that the table exists, emit one BEFORE INSERT trigger
        // per `SequenceNextVal`-defaulted column. The trigger calls
        // `dmg_nextval('<sequence>')` when the inserted row leaves
        // the column NULL — the same contract the full-schema DDL
        // generator emits via `generateSupportTriggers`. Sequence-
        // phase Create ops run BEFORE this op in the topological
        // sort, so the helper-table row and the dmg_nextval routine
        // already exist.
        for ((colName, seqDefault) in sequenceColumns) {
            MysqlDiffSequenceOps.emitSupportTriggerForColumn(
                op, ctx, tableName, colName, seqDefault.sequenceName,
            )
        }
    }

    /**
     * E.3 Sub-Slice F: walks the table's columns once and returns
     * the `(columnName, SequenceNextVal)`-pairs that drive support-
     * trigger emission. Other defaults (literals, function calls,
     * etc.) flow through the regular `columnLine` path unchanged.
     */
    private fun sequenceDefaultColumns(
        table: TableDefinition,
    ): List<Pair<String, DefaultValue.SequenceNextVal>> {
        val result = mutableListOf<Pair<String, DefaultValue.SequenceNextVal>>()
        for ((name, col) in table.columns.inOrdinalOrder()) {
            val seq = col.default as? DefaultValue.SequenceNextVal ?: continue
            result += name to seq
        }
        return result
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
            // DROP COLUMN cascades any column-bound triggers
            // implicitly (MySQL drops triggers when their referenced
            // column disappears via DROP COLUMN). The canonical
            // support-trigger name is keyed by `(table, column)`,
            // so once the column is gone the trigger goes with it.
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        // E.3 Sub-Slice F: when the new column carries a
        // SequenceNextVal default the mode gate must clear first;
        // the column line itself drops the `DEFAULT` clause for that
        // case (see `MysqlDiffSqlBuilders.columnLine`).
        val seqDefault = op.column.default as? DefaultValue.SequenceNextVal
        if (seqDefault != null && !MysqlDiffSequenceOps.requireHelperModeForColumnDefault(op, ctx)) {
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column)};")
        if (seqDefault != null) {
            MysqlDiffSequenceOps.emitSupportTriggerForColumn(
                op, ctx, table, column, seqDefault.sequenceName,
            )
        }
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
        ctx.skip(
            op,
            "MySQL requires the column type to change nullability; not in the first matrix.",
            code = "MYSQL_NULLABILITY_REQUIRES_COLUMN_TYPE",
        )
        ctx.addBlocker(MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION, operationIds = setOf(op.id))
    }

    fun renderAlterColumnDefault(op: DiffOperation.AlterColumnDefault, ctx: MysqlDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        val (source, target) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.before to op.after
        } else {
            op.after to op.before
        }
        val sourceSeq = source as? DefaultValue.SequenceNextVal
        val targetSeq = target as? DefaultValue.SequenceNextVal
        // E.3 Sub-Slice F: any side that touches SequenceNextVal
        // needs the mode gate before any SQL is emitted, otherwise
        // a half-rendered ALTER would land in the report.
        if ((sourceSeq != null || targetSeq != null) &&
            !MysqlDiffSequenceOps.requireHelperModeForColumnDefault(op, ctx)
        ) {
            return
        }
        // Source was a sequence default: drop the bound trigger
        // before changing the column's DDL default.
        if (sourceSeq != null) {
            MysqlDiffSequenceOps.emitDropSupportTriggerForColumn(op, ctx, table, column)
        }
        // The column-level `ALTER … SET/DROP DEFAULT` only emits a
        // literal DEFAULT clause; SequenceNextVal targets reach the
        // sequence-trigger path below instead, so the column-level
        // statement just clears the default.
        val text = if (target == null || targetSeq != null) {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER ${ctx.sql.quote(column)} DROP DEFAULT;"
        } else {
            "ALTER TABLE ${ctx.sql.quote(table)} ALTER ${ctx.sql.quote(column)} " +
                "SET DEFAULT ${ctx.sql.toDefaultSql(target, NeutralType.Text())};"
        }
        ctx.emit(op, text)
        // Target is a sequence default: emit the bound trigger so
        // INSERTs that leave the column NULL pick up `dmg_nextval`.
        if (targetSeq != null) {
            MysqlDiffSequenceOps.emitSupportTriggerForColumn(
                op, ctx, table, column, targetSeq.sequenceName,
            )
        }
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

    /**
     * Plan-2 §F.4 second slice. Renders MySQL's `ALTER TABLE … RENAME TO …`
     * (since MySQL 8.0 the modern syntax is preferred over `RENAME TABLE`,
     * which would also work but loses the symmetric ALTER context).
     */
    fun renderRenameTable(op: DiffOperation.RenameTable, ctx: MysqlDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(oldName)} RENAME TO ${ctx.sql.quote(newName)};")
    }

    /**
     * Plan-2 §F.4 second slice. Renders `ALTER TABLE … RENAME COLUMN …`
     * (MySQL 8.0.3+). Older MySQL releases require `CHANGE COLUMN` with
     * the full column type — the mapper currently emits no `RenameColumn`
     * for those targets because the dialect renderer surface for MySQL
     * defaults to the modern syntax (matching the rest of the diff
     * generator).
     */
    fun renderRenameColumn(op: DiffOperation.RenameColumn, ctx: MysqlDiffRenderContext) {
        val table = op.objectRef.path[0]
        val (oldCol, newCol) = if (ctx.direction == MysqlRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(
            op,
            "ALTER TABLE ${ctx.sql.quote(table)} RENAME COLUMN ${ctx.sql.quote(oldCol)} " +
                "TO ${ctx.sql.quote(newCol)};",
        )
    }

    private fun dev.dmigrate.core.model.IndexDefinition.referencesGeometry(table: TableDefinition): Boolean =
        // ADR 0025: shared predicate (excludes FULLTEXT — createIndexSql has the native branch).
        isSpatialGeometryIndex { table.columns[it]?.type }
}
