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
 * SpatiaLite-spezifische DDL-Helfer für den SQLite-Diff-Renderpfad — aus
 * [SqliteDiffSimpleOps] ausgegliedert (kohärentes Spatial-Cluster, hält beide
 * Objekte unter dem Detekt-`TooManyFunctions`-Budget). Aufgerufen von den
 * `render*`-Funktionen in [SqliteDiffSimpleOps]; Profil-/Extension-Gating über
 * [guardSpatiaLite].
 */
internal object SqliteSpatialDiffOps {

    private const val SPATIALITE_EXTENSION = "spatialite"

    fun hasGeometryColumns(table: TableDefinition): Boolean =
        table.columns.values.any { it.type is NeutralType.Geometry }

    fun guardSpatiaLite(op: DiffOperation, ctx: SqliteDiffRenderContext, detail: String): Boolean {
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

    fun spatialMetadataBlock(table: TableDefinition): String? {
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

    fun geometryColumnMetadataBlock(columnName: String, column: ColumnDefinition): String? = when {
        column.required -> "geometry column `$columnName` is NOT NULL"
        column.unique -> "geometry column `$columnName` is UNIQUE"
        column.default != null -> "geometry column `$columnName` has a DEFAULT"
        column.references != null -> "geometry column `$columnName` has a foreign key reference"
        else -> null
    }

    private fun List<ConstraintDefinition>.firstConstraintGeometryColumn(geometryColumnNames: Set<String>): String? =
        firstNotNullOfOrNull { constraint -> constraint.columns.orEmpty().firstOrNull { it in geometryColumnNames } }

    fun blockSpatialMetadata(
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
    fun createSpatialIndex(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        if (!guardSpatiaLite(op, ctx, "spatial index on `$table`")) return
        val geomColumn = ctx.geometryIndexColumn(table, index) ?: return
        emitCreateSpatialIndex(op, ctx, table, geomColumn)
    }

    /** Emit `SELECT CreateSpatialIndex('table', 'column');` — eine Quelle für beide
     *  Aufrufer (renderAddIndex via [createSpatialIndex], renderCreateTable direkt). */
    fun emitCreateSpatialIndex(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        geomColumn: String,
    ) {
        ctx.emit(op, "SELECT CreateSpatialIndex('${table.sqlString()}', '${geomColumn.sqlString()}');")
    }

    /** VA4: Gegenstück zu [createSpatialIndex] für den DOWN/Drop-Pfad. */
    fun disableSpatialIndex(
        op: DiffOperation,
        ctx: SqliteDiffRenderContext,
        table: String,
        index: IndexDefinition,
    ) {
        if (!guardSpatiaLite(op, ctx, "spatial index drop on `$table`")) return
        val geomColumn = ctx.geometryIndexColumn(table, index) ?: return
        ctx.emit(op, "SELECT DisableSpatialIndex('${table.sqlString()}', '${geomColumn.sqlString()}');")
    }

    /**
     * VA4/5d Befund 1: emittiert den SpatiaLite-Metadaten-Bootstrap genau einmal pro
     * UP-Render, VOR dem ersten `AddGeometryColumn`. Eine frische `.db` hat keine
     * `geometry_columns`/`spatial_ref_sys`-Metatabellen — `AddGeometryColumn` bräche
     * dort mit „unexpected metadata layout" ab. Die guarded CASE-Form ist idempotent
     * (no-op, wenn `CheckSpatialMetaData()` bereits Metadaten meldet) und läuft INNERHALB
     * der Runner-Transaktion: `InitSpatialMetaData()` ohne Transaktions-Argument öffnet
     * KEIN verschachteltes `BEGIN`. Der SQL-Text ist deterministisch (zustandsfrei),
     * also bleiben Dry-Run-Artefakte stabil. Nur erreichbar, nachdem `guardSpatiaLite`
     * (Profil SPATIALITE + Extension) für die Geometriespalte bereits bestanden hat.
     */
    fun ensureSpatialMetadataBootstrap(op: DiffOperation, ctx: SqliteDiffRenderContext) {
        if (ctx.direction != SqliteRenderDirection.UP) return
        if (ctx.spatialMetadataBootstrapEmitted) return
        ctx.emit(op, "SELECT CASE WHEN CheckSpatialMetaData() = 0 THEN InitSpatialMetaData() END;")
        ctx.spatialMetadataBootstrapEmitted = true
    }

    fun addGeometryColumnSql(table: String, column: String, definition: ColumnDefinition): String {
        val geometry = definition.type as NeutralType.Geometry
        val geometryType = geometry.geometryType.schemaName.uppercase()
        val srid = geometry.srid ?: 0
        return "SELECT AddGeometryColumn('${table.sqlString()}', '${column.sqlString()}', $srid, '$geometryType', 'XY');"
    }

    fun discardGeometryColumnSql(table: String, column: String): String =
        "SELECT DiscardGeometryColumn('${table.sqlString()}', '${column.sqlString()}');"

    private fun String.sqlString(): String = replace("'", "''")
}
