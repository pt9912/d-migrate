package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.SpatialProfile
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
            ctx.emit(op, "DROP TABLE ${ctx.sql.quote(tableName)};")
            return
        }
        if (op.table.hasGeometryColumns() &&
            !guardSpatiaLite(op, ctx, "geometry columns on table `$tableName`")
        ) {
            return
        }
        if (op.table.hasGeometryColumns()) {
            val blocked = spatialMetadataBlock(op.table)
            if (blocked != null) {
                blockSpatialMetadata(op, ctx, tableName, blocked)
                return
            }
        }
        val lines = mutableListOf<String>()
        val effectiveColumns = if (op.table.hasGeometryColumns()) {
            op.table.columns.filterValues { it.type !is NeutralType.Geometry }
        } else {
            op.table.columns
        }
        if (effectiveColumns.isEmpty()) {
            blockSpatialMetadata(op, ctx, tableName, "geometry-only table requires a non-spatial base column")
            return
        }
        for ((colName, col) in effectiveColumns.entries.sortedBy { it.key }) {
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
        for ((colName, col) in op.table.columns.entries.sortedBy { it.key }) {
            if (col.type is NeutralType.Geometry) {
                ctx.emit(op, addGeometryColumnSql(tableName, colName, col))
            }
        }
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
            if (op.column.type is NeutralType.Geometry) {
                if (!guardSpatiaLite(op, ctx, "geometry column `$table.$column`")) return
                ctx.emit(op, discardGeometryColumnSql(table, column))
                return
            }
            // 0.9.7 G5: drop the sequence-support trigger pair before
            // dropping the column itself, mirror of UP-side emit.
            SqliteDiffSequenceOps.dropTriggerPairIfBound(op, ctx, table, column, op.column.default)
            ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} DROP COLUMN ${ctx.sql.quote(column)};")
            return
        }
        if (op.column.type is NeutralType.Geometry &&
            !guardSpatiaLite(op, ctx, "geometry column `$table.$column`")
        ) {
            return
        }
        if (op.column.type is NeutralType.Geometry) {
            val blocked = geometryColumnMetadataBlock(column, op.column)
            if (blocked != null) {
                blockSpatialMetadata(op, ctx, table, blocked)
                return
            }
            ctx.emit(op, addGeometryColumnSql(table, column, op.column))
            return
        }
        ctx.emit(op, "ALTER TABLE ${ctx.sql.quote(table)} ADD COLUMN ${ctx.sql.columnLine(column, op.column)};")
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
            if (!guardSpatiaLite(op, ctx, "geometry column `$table.$column`")) return
            ctx.emit(op, discardGeometryColumnSql(table, column))
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
            if (ctx.indexTouchesGeometry(table, op.index)) {
                disableSpatialIndex(op, ctx, table, op.index)
                return
            }
            ctx.emit(op, ctx.sql.dropIndexSql(table, op.index))
            return
        }
        if (ctx.indexTouchesGeometry(table, op.index)) {
            createSpatialIndex(op, ctx, table, op.index)
            return
        }
        ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
    }

    fun renderDropIndex(op: DiffOperation.DropIndex, ctx: SqliteDiffRenderContext) {
        val table = op.objectRef.path[0]
        if (ctx.direction == SqliteRenderDirection.DOWN) {
            if (ctx.indexTouchesGeometry(table, op.index)) {
                createSpatialIndex(op, ctx, table, op.index)
                return
            }
            ctx.emit(op, ctx.sql.createIndexSql(table, op.index))
            return
        }
        if (ctx.indexTouchesGeometry(table, op.index)) {
            disableSpatialIndex(op, ctx, table, op.index)
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

    private fun TableDefinition.hasGeometryColumns(): Boolean =
        columns.values.any { it.type is NeutralType.Geometry }

    private fun guardSpatiaLite(op: DiffOperation, ctx: SqliteDiffRenderContext, detail: String): Boolean {
        if (ctx.options.spatialProfile != SpatialProfile.SPATIALITE) {
            ctx.skip(
                op,
                "Operation ${op.id} requires SQLite spatial profile SPATIALITE for $detail; " +
                    "current profile is ${ctx.options.spatialProfile.name}.",
                code = "SPATIAL_PROFILE_REQUIRED",
            )
            ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
            return false
        }
        return ctx.requireExtension(op, SPATIALITE_EXTENSION, detail)
    }

    private fun spatialMetadataBlock(table: TableDefinition): String? {
        val geometryColumnNames = table.columns.filterValues { it.type is NeutralType.Geometry }.keys
        for ((columnName, column) in table.columns) {
            if (column.type is NeutralType.Geometry) {
                geometryColumnMetadataBlock(columnName, column)?.let { return it }
                if (columnName in table.primaryKey) {
                    return "geometry column `$columnName` participates in the primary key"
                }
            }
        }
        table.constraints.firstConstraintGeometryColumn(geometryColumnNames)?.let { column ->
            return "table-level constraint references geometry column `$column`"
        }
        // VA4: ein Index auf einer Geometriespalte blockt nicht mehr — er wird als
        // SpatiaLite `CreateSpatialIndex` emittiert (createSpatialIndex/renderAddIndex).
        return null
    }

    private fun geometryColumnMetadataBlock(columnName: String, column: ColumnDefinition): String? = when {
        column.required -> "geometry column `$columnName` is NOT NULL"
        column.unique -> "geometry column `$columnName` is UNIQUE"
        column.default != null -> "geometry column `$columnName` has a DEFAULT"
        column.references != null -> "geometry column `$columnName` has a foreign key reference"
        else -> null
    }

    private fun List<ConstraintDefinition>.firstConstraintGeometryColumn(geometryColumnNames: Set<String>): String? =
        firstNotNullOfOrNull { constraint -> constraint.columns.orEmpty().firstOrNull { it in geometryColumnNames } }

    private fun blockSpatialMetadata(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        reason: String,
    ) {
        ctx.skip(
            op,
            "Operation ${op.id} cannot render SpatiaLite metadata for `$table`: $reason.",
            code = "SPATIAL_METADATA_UNSUPPORTED",
        )
        ctx.addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    /**
     * VA4: ein Index auf einer Geometriespalte → SpatiaLite `CreateSpatialIndex`
     * (R*Tree), statt zu blocken. Nur unter `--spatial-profile spatialite` +
     * verfügbarer Extension (`guardSpatiaLite`).
     */
    private fun createSpatialIndex(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        if (!guardSpatiaLite(op, ctx, "spatial index on `$table`")) return
        val geomColumn = ctx.geometryIndexColumn(table, index) ?: return
        ctx.emit(op, "SELECT CreateSpatialIndex('${table.sqlString()}', '${geomColumn.sqlString()}');")
    }

    /** VA4: Gegenstück zu [createSpatialIndex] für den DOWN/Drop-Pfad. */
    private fun disableSpatialIndex(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        if (!guardSpatiaLite(op, ctx, "spatial index drop on `$table`")) return
        val geomColumn = ctx.geometryIndexColumn(table, index) ?: return
        ctx.emit(op, "SELECT DisableSpatialIndex('${table.sqlString()}', '${geomColumn.sqlString()}');")
    }

    private fun addGeometryColumnSql(table: String, column: String, definition: ColumnDefinition): String {
        val geometry = definition.type as NeutralType.Geometry
        val geometryType = geometry.geometryType.schemaName.uppercase()
        val srid = geometry.srid ?: 0
        return "SELECT AddGeometryColumn('${table.sqlString()}', '${column.sqlString()}', $srid, '$geometryType', 'XY');"
    }

    private fun discardGeometryColumnSql(table: String, column: String): String =
        "SELECT DiscardGeometryColumn('${table.sqlString()}', '${column.sqlString()}');"

    private fun String.sqlString(): String = replace("'", "''")

    private const val SPATIALITE_EXTENSION = "spatialite"
}
