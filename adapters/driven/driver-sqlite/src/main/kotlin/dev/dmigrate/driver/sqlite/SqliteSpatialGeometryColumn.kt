package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType

/**
 * `SELECT AddGeometryColumn(…)` — **einmal** fuer beide Pfade.
 *
 * Der Generate-Pfad ([SqliteTableDdlSupport]) und der Diff-Pfad
 * ([SqliteSpatialDiffOps]) schrieben den Aufruf je selbst. Seit die Signatur
 * ein siebtes Stueck traegt (`not_null`), waere das die Art Abweichung, die
 * erst an einem echten Server auffiele: dieselbe Spalte einmal `NOT NULL` und
 * einmal nullbar.
 *
 * **Signatur** (SpatiaLite 5.1.0):
 * `AddGeometryColumn(table, column, srid, type, dimension [, not_null])`.
 * Das sechste Argument ist optional; ohne es entsteht eine nullbare Spalte.
 * Mit `1` legt SpatiaLite `"<spalte>" <TYP> NOT NULL DEFAULT ''` an
 * (gemessen) — der Default ist SpatiaLites Fuellwert, kein Anwender-Default,
 * und der Reverse verwirft ihn deshalb ([SqliteTypeMapping]).
 */
internal object SqliteSpatialGeometryColumn {

    /**
     * Der Aufruf fuer eine Spalte. [required] entscheidet ueber das sechste
     * Argument; eine nullbare Spalte behaelt die fuenfstellige Form, damit
     * bestehende DDL-Goldens und bestehende Datenbanken unveraendert bleiben.
     */
    fun addSql(table: String, column: String, definition: ColumnDefinition): String {
        val geometry = definition.type as NeutralType.Geometry
        val geometryType = geometry.geometryType.schemaName.uppercase()
        val srid = geometry.srid ?: 0
        val notNull = if (definition.required) ", 1" else ""
        return "SELECT AddGeometryColumn('${table.sqlString()}', '${column.sqlString()}', " +
            "$srid, '$geometryType', 'XY'$notNull);"
    }

    fun discardSql(table: String, column: String): String =
        "SELECT DiscardGeometryColumn('${table.sqlString()}', '${column.sqlString()}');"

    private fun String.sqlString(): String = replace("'", "''")
}
