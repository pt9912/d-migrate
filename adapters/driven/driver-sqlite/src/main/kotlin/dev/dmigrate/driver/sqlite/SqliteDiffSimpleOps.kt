package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.inOrdinalOrder
import dev.dmigrate.core.model.isSpatialGeometryIndex
import dev.dmigrate.driver.CoveringIndexDropNote
import dev.dmigrate.driver.asDiffDiagnostic
import dev.dmigrate.driver.migration.MigrationBlockedReason

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
            // ADR 0025 (Slice P4): an FTS5 virtual table + its three sync triggers are separate
            // schema objects — `DROP TABLE` of the base table leaves them orphaned — so tear them
            // down first for rollback symmetry with the UP-side expansion.
            for (idx in op.table.indices) {
                if (idx.type == IndexType.FULLTEXT) SqliteFullTextExpansion.emitDrop(op, ctx, tableName, idx)
            }
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
            return
        }
        if (SqliteSpatialDiffOps.hasGeometryColumns(op.table) &&
            !SqliteSpatialDiffOps.guardSpatiaLite(op, ctx, "geometry columns on table `$tableName`")
        ) {
            return
        }
        if (SqliteSpatialDiffOps.hasGeometryColumns(op.table)) {
            val blocked = SqliteSpatialDiffOps.spatialMetadataBlock(op.table)
            if (blocked != null) {
                SqliteSpatialDiffOps.blockSpatialMetadata(op, ctx, tableName, blocked)
                return
            }
        }
        val lines = mutableListOf<String>()
        val effectiveColumns = if (SqliteSpatialDiffOps.hasGeometryColumns(op.table)) {
            op.table.columns.filterValues { it.type !is NeutralType.Geometry }
        } else {
            op.table.columns
        }
        if (effectiveColumns.isEmpty()) {
            SqliteSpatialDiffOps.blockSpatialMetadata(op, ctx, tableName, "geometry-only table requires a non-spatial base column")
            return
        }
        val solePrimaryKey = op.table.primaryKey.singleOrNull()
        for ((colName, col) in effectiveColumns.inOrdinalOrder()) {
            val isSolePrimaryKey = solePrimaryKey == colName
            lines += "    " + ctx.sql.columnLine(colName, col, isSolePrimaryKey)
            if (SqliteCompositePkIdentity.isDroppedAutoincrement(col.type, isSolePrimaryKey)) {
                ctx.warning(op, SqliteCompositePkIdentity.message(colName), SqliteCompositePkIdentity.W_CODE)
            }
        }
        ctx.sql.primaryKeyClause(op.table)?.let { lines += "    $it" }
        for (c in op.table.constraints.sortedBy { it.name }) {
            ctx.sql.constraintLine(c)?.let { lines += "    $it" }
        }
        val text = buildString {
            append("CREATE TABLE ").append(ctx.sql.quote(tableName)).append(" (\n")
            append(lines.joinToString(",\n"))
            append("\n);")
        }
        ctx.emit(op, text)
        for ((colName, col) in op.table.columns.inOrdinalOrder()) {
            if (col.type is NeutralType.Geometry) {
                SqliteSpatialDiffOps.ensureSpatialMetadataBootstrap(op, ctx)
                ctx.emit(op, SqliteSpatialDiffOps.addGeometryColumnSql(tableName, colName, col))
            }
            SqliteEnumDegradation.warnIfEnum(op, ctx, colName, col)
        }
        for (idx in op.table.indices) {
            // ADR 0025 (Slice P4): a FULLTEXT index expands to an FTS5 virtual table + three sync
            // triggers (SqliteFullTextExpansion) — checked FIRST because it lists its source TEXT
            // columns, which the geometry router below must not see.
            if (idx.type == IndexType.FULLTEXT) {
                SqliteFullTextExpansion.emitCreate(op, ctx, tableName, idx)
                continue
            }
            // VA4/5d Befund 2: ein Index auf einer Geometriespalte muss auch im
            // CreateTable-Diff-Pfad als SpatiaLite `CreateSpatialIndex` (R*Tree)
            // emittiert werden — nicht als generischer `CREATE INDEX` (der auf eine
            // erst per AddGeometryColumn entstehende Spalte zeigte). Der
            // SPATIALITE-Profil-/Extension-Guard ist hier bereits durch den
            // hasGeometryColumns-Block oben (guardSpatiaLite) garantiert, sonst wäre
            // dieser Pfad nicht erreicht. ADR 0025: das geteilte Prädikat schließt FULLTEXT aus
            // (sonst würde ein FULLTEXT-Index über einer Geo-Quellspalte fälschlich als
            // Spatial-Index emittiert statt via createIndexSql mit W132 degradiert).
            if (idx.isSpatialGeometryIndex { op.table.columns[it]?.type }) {
                val geomCol = idx.columnNames.first { op.table.columns[it]?.type is NeutralType.Geometry }
                SqliteSpatialDiffOps.emitCreateSpatialIndex(op, ctx, tableName, geomCol)
            } else {
                ctx.emit(op, ctx.sql.createIndexSql(tableName, idx))
            }
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
            if (op.column.type is NeutralType.Geometry) {
                if (!SqliteSpatialDiffOps.guardSpatiaLite(op, ctx, "geometry column `$table.$column`")) return
                ctx.emit(op, SqliteSpatialDiffOps.discardGeometryColumnSql(table, column))
                return
            }
            // 0.9.7 G5: drop the sequence-support trigger pair before
            // dropping the column itself, mirror of UP-side emit.
            SqliteDiffSequenceOps.dropTriggerPairIfBound(op, ctx, table, column, op.column.default)
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        if (op.column.type is NeutralType.Geometry &&
            !SqliteSpatialDiffOps.guardSpatiaLite(op, ctx, "geometry column `$table.$column`")
        ) {
            return
        }
        if (op.column.type is NeutralType.Geometry) {
            val blocked = SqliteSpatialDiffOps.geometryColumnMetadataBlock(column, op.column)
            if (blocked != null) {
                SqliteSpatialDiffOps.blockSpatialMetadata(op, ctx, table, blocked)
                return
            }
            SqliteSpatialDiffOps.ensureSpatialMetadataBootstrap(op, ctx)
            ctx.emit(op, SqliteSpatialDiffOps.addGeometryColumnSql(table, column, op.column))
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column)};")
        SqliteEnumDegradation.warnIfEnum(op, ctx, column, op.column)
        // 0.9.7 G5: when the new column carries SequenceNextVal,
        // emit the `_bi`/`_ai` trigger pair against the sequence
        // declared in the target schema. action_required mode is a
        // CreateSequence-side concern; if the sequence isn't yet
        // declared `_bi` references a missing row at runtime, but
        // E058 + E060 preflights guard the rollback path.
        SqliteDiffSequenceOps.emitTriggerPairIfBound(op, ctx, table, column, op.column.default)
    }

    fun renderDropColumn(op: DiffOperation.DropColumn, ctx: SqliteDiffRenderContext) {
        val (table, column) = op.objectRef.path[0] to op.objectRef.path[1]
        if (op.column.type is NeutralType.Geometry) {
            if (!SqliteSpatialDiffOps.guardSpatiaLite(op, ctx, "geometry column `$table.$column`")) return
            ctx.emit(op, SqliteSpatialDiffOps.discardGeometryColumnSql(table, column))
            return
        }
        // 0.9.7 G5: drop the sequence-support trigger pair before
        // dropping the column itself. DOWN re-emits via the inverse
        // AddColumn-path.
        SqliteDiffSequenceOps.dropTriggerPairIfBound(op, ctx, table, column, op.column.default)
        // SQLite ≥ 3.35.0 supports DROP COLUMN; the planner has already filtered FK-bearing cases.
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
        // DOWN inverse: re-emit the trigger pair.
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            SqliteDiffSequenceOps.emitTriggerPairIfBound(op, ctx, table, column, op.column.default)
        }
    }


    /**
     * Plan-2 §F.4 second slice. SQLite ≥ 3.0 supports
     * `ALTER TABLE … RENAME TO …`; the runner enforces the version
     * policy elsewhere.
     */
    fun renderRenameTable(op: DiffOperation.RenameTable, ctx: SqliteDiffRenderContext) {
        val (oldName, newName) = if (ctx.direction == SqliteRenderDirection.UP) {
            op.fromName to op.toName
        } else {
            op.toName to op.fromName
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(oldName)} RENAME TO ${ctx.sql.quote(newName)};")
    }

    /**
     * Plan-2 §F.4 second slice. `ALTER TABLE … RENAME COLUMN …`
     * has been a first-class SQLite operation since 3.25.0. SQLite
     * automatically rewrites references in views and trigger bodies
     * starting from 3.26.0 (`PRAGMA legacy_alter_table = 0` in 3.25);
     * the renderer relies on the modern default behaviour.
     */
    fun renderRenameColumn(op: DiffOperation.RenameColumn, ctx: SqliteDiffRenderContext) {
        val table = op.objectRef.path[0]
        val (oldCol, newCol) = if (ctx.direction == SqliteRenderDirection.UP) {
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

    fun renderAddIndex(op: DiffOperation.AddIndex, ctx: SqliteDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            // ADR 0025 (Slice P4): the UP expanded the FULLTEXT index to an FTS5 virtual table +
            // three sync triggers, so the DOWN tears exactly those down (not a `DROP INDEX` — no
            // plain index was ever materialised).
            if (op.index.type == IndexType.FULLTEXT) {
                SqliteFullTextExpansion.emitDrop(op, ctx, table, op.index)
                return
            }
            if (ctx.indexTouchesGeometry(table, op.index)) {
                SqliteSpatialDiffOps.disableSpatialIndex(op, ctx, table, op.index)
                return
            }
            ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
            return
        }
        // ADR 0025 (Slice P4): expand a FULLTEXT index to an FTS5 virtual table + three sync
        // triggers (SqliteFullTextExpansion) BEFORE the geometry check — its source columns are
        // text, so the geometry routing must not see it.
        if (op.index.type == IndexType.FULLTEXT) {
            SqliteFullTextExpansion.emitCreate(op, ctx, table, op.index)
            return
        }
        if (ctx.indexTouchesGeometry(table, op.index)) {
            SqliteSpatialDiffOps.createSpatialIndex(op, ctx, table, op.index)
            return
        }
        noteCoveringDegradation(op, ctx, table, op.index)
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
    }

    /**
     * Was `schema generate` an dieser Stelle meldet, meldet `schema migrate`
     * auch: SQLite kennt weder INCLUDE-Spalten noch eine Steuerung der Ablage.
     * Ohne diesen Aufruf warnte nur der Generate-Pfad vor dem Verlust.
     */
    private fun noteCoveringDegradation(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        val name = index.name ?: table
        for (note in CoveringIndexDropNote.forDialect(index, name, "SQLite")) {
            ctx.addDiagnostic(note.asDiffDiagnostic(op.id))
        }
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: SqliteDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            // ADR 0025 (Slice P4): the UP dropped the FTS5 structure, so the DOWN rebuilds it.
            if (op.index.type == IndexType.FULLTEXT) {
                SqliteFullTextExpansion.emitCreate(op, ctx, table, op.index)
                return
            }
            if (ctx.indexTouchesGeometry(table, op.index)) {
                SqliteSpatialDiffOps.createSpatialIndex(op, ctx, table, op.index)
                return
            }
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
            return
        }
        // ADR 0025 (Slice P4): drop the FTS5 virtual table + three sync triggers (not a plain
        // DROP INDEX — no plain index exists for a FULLTEXT index).
        if (op.index.type == IndexType.FULLTEXT) {
            SqliteFullTextExpansion.emitDrop(op, ctx, table, op.index)
            return
        }
        if (ctx.indexTouchesGeometry(table, op.index)) {
            SqliteSpatialDiffOps.disableSpatialIndex(op, ctx, table, op.index)
            return
        }
        ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
    }

    fun renderCreateView(op: DiffOperation.CreateView, ctx: SqliteDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            ctx.emit(op, "DROP VIEW ${ctx.sql.quote(name)};")
            return
        }
        ctx.emit(op, ctx.sql.createViewSql(name, op.view))
    }

    fun renderDropView(op: DiffOperation.DropView, ctx: SqliteDiffRenderContext) {
        val name = op.objectRef.rootName
        if (op.view.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
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
        if (op.before.materialized || op.after.materialized || target.materialized) {
            blockMaterializedView(op, ctx, name)
            return
        }
        ctx.emit(op, "DROP VIEW IF EXISTS ${ctx.sql.quote(name)};")
        ctx.emit(op, ctx.sql.createViewSql(name, target))
    }

    private fun blockMaterializedView(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        name: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} targets materialized view '$name' for dialect sqlite " +
                "(materialized=true). Diff-based materialized-view migrations are blocked until " +
                "a dedicated emulation/refresh contract exists.",
            code = "MATERIALIZED_VIEW_DIFF_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }
}
