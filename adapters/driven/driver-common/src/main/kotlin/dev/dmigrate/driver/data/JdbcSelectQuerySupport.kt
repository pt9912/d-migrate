package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.core.model.GeometryType
import java.sql.ResultSetMetaData

/**
 * Ergebnis von [AbstractJdbcDataReader.buildSelectQuery]: das finale
 * SELECT-Statement plus die flach aggregierte Parameter-Liste in korrekter
 * `?`-Bind-Reihenfolge. Top-Level statt nested, damit Treiber-Module
 * (PG/MySQL/SQLite) den Rückgabetyp in eigenen `buildSelectQuery`-Overrides
 * benutzen können; `AbstractJdbcDataReader.buildSelectQuery` ist protected,
 * aber der Typ ist public, weil Kotlin-Module den eigenen `protected`-Scope
 * nicht über Modul-Kanten hinweg vererben.
 */
data class SelectQuery(val sql: String, val params: List<Any?>)

/**
 * Internes SQL-Fragment mit Bind-Params. [DataFilter.ParameterizedClause]
 * trägt seine positional gebundenen Werte mit.
 */
internal data class WhereFragment(val sql: String, val params: List<Any?>)

/**
 * VA1b (Spatial-Slice): eine Spalte aus der Metadaten-Vorabfrage —
 * Name (in DB-Reihenfolge) + ob es eine Geometriespalte ist. Letzteres
 * wird typeName-basiert ermittelt (wie VA1a, [GeometryType.KNOWN_VALUES]),
 * damit die Read-Projektion Geometriespalten dialekt-spezifisch wrappen kann.
 */
data class ProbedColumn(val name: String, val isGeometry: Boolean)

internal object JdbcSelectQuerySupport {

    /**
     * LF-013 / LN-006 / LN-012: erzeugt den Composite-Marker-Filter fuer
     * `(markerColumn, tieBreakers...) > (lastMarkerValue, lastTieBreakerValues...)`
     * in lexikografischer Reihenfolge. Ohne Tie-Breaker entartet der
     * Ausdruck zu einem strikten `markerColumn > ?`.
     */
    fun buildMarkerFragment(
        marker: ResumeMarker,
        position: ResumeMarker.Position,
        quoteIdentifier: (String) -> String,
    ): WhereFragment {
        val markerColumn = quoteIdentifier(marker.markerColumn)
        if (marker.tieBreakerColumns.isEmpty()) {
            return WhereFragment("$markerColumn > ?", listOf(position.lastMarkerValue))
        }
        val tieCascade = buildTieCascade(
            marker.tieBreakerColumns.map(quoteIdentifier),
            position.lastTieBreakerValues,
        )
        val sql = "$markerColumn > ? OR ($markerColumn = ? AND (${tieCascade.sql}))"
        val params = listOf<Any?>(position.lastMarkerValue, position.lastMarkerValue) + tieCascade.params
        return WhereFragment(sql, params)
    }

    fun projection(
        filter: DataFilter?,
        quoteIdentifier: (String) -> String,
    ): String {
        val columns = collectColumnSubset(filter)
        return if (columns == null) "*" else columns.joinToString(", ") { quoteIdentifier(it) }
    }

    /**
     * VA1b: liest aus [ResultSetMetaData] die Spaltennamen (in Reihenfolge) und
     * markiert jede als Geometrie, wenn ihr `getColumnTypeName` in
     * [GeometryType.KNOWN_VALUES] liegt (case-insensitiv, wie VA1a). Pure Funktion
     * über die Metadaten — die Query-Ausführung liegt im Reader (Glue).
     */
    fun probedColumnsFromMetaData(metaData: ResultSetMetaData): List<ProbedColumn> {
        val count = metaData.columnCount
        val cols = ArrayList<ProbedColumn>(count)
        for (i in 1..count) {
            val name = metaData.getColumnLabel(i)
            val typeName = runCatching { metaData.getColumnTypeName(i) }.getOrNull()?.lowercase()
            cols += ProbedColumn(name, typeName != null && typeName in GeometryType.KNOWN_VALUES)
        }
        return cols
    }

    /**
     * VA1b: geometrie-bewusste Projektion. Wird nur gebraucht, wenn die
     * Vorabfrage mindestens eine Geometriespalte gefunden hat. Geometriespalten
     * werden via [geometryExpression] gewrappt (z. B. `ST_AsEWKB("g") AS "g"`),
     * alle anderen bleiben unverändert. Respektiert einen
     * [DataFilter.ColumnSubset] (dessen Spalten-Reihenfolge, wie [projection]);
     * ohne Subset gilt die DB-Spaltenreihenfolge aus [probedColumns]. Das Alias
     * (`AS <col>`) erhält den ursprünglichen Spaltennamen, damit der Chunk-Header
     * im Haupt-Stream identisch bleibt.
     */
    fun geometryAwareProjection(
        filter: DataFilter?,
        probedColumns: List<ProbedColumn>,
        quoteIdentifier: (String) -> String,
        geometryExpression: (String) -> String,
    ): String {
        val isGeometryByName = probedColumns.associate { it.name to it.isGeometry }
        val names = collectColumnSubset(filter) ?: probedColumns.map { it.name }
        return names.joinToString(", ") { name ->
            val quoted = quoteIdentifier(name)
            if (isGeometryByName[name] == true) "${geometryExpression(quoted)} AS $quoted" else quoted
        }
    }

    /**
     * Sammelt alle WHERE-Fragmente aus dem Filter-Baum in Traversierungsreihenfolge.
     * [DataFilter.Compound] liefert seine Parts links-nach-rechts; das garantiert
     * eine deterministische `?`-Positionierung in der finalen SQL-Form.
     */
    fun collectWhereFragments(filter: DataFilter?): List<WhereFragment> = when (filter) {
        null -> emptyList()
        is DataFilter.ColumnSubset -> emptyList()
        is DataFilter.ParameterizedClause -> listOf(WhereFragment(filter.sql, filter.params))
        is DataFilter.Compound -> filter.parts.flatMap { collectWhereFragments(it) }
    }

    private fun buildTieCascade(
        columns: List<String>,
        values: List<Any?>,
    ): WhereFragment {
        require(columns.size == values.size) {
            "tie-cascade cols/values size mismatch: ${columns.size} vs ${values.size}"
        }
        require(columns.isNotEmpty()) { "tie-cascade requires at least one column" }
        if (columns.size == 1) {
            return WhereFragment("${columns[0]} > ?", listOf(values[0]))
        }
        val rest = buildTieCascade(columns.drop(1), values.drop(1))
        val sql = "${columns[0]} > ? OR (${columns[0]} = ? AND (${rest.sql}))"
        val params = listOf<Any?>(values[0], values[0]) + rest.params
        return WhereFragment(sql, params)
    }

    private fun collectColumnSubset(filter: DataFilter?): List<String>? = when (filter) {
        null -> null
        is DataFilter.ColumnSubset -> filter.columns
        is DataFilter.ParameterizedClause -> null
        is DataFilter.Compound -> filter.parts.firstNotNullOfOrNull { collectColumnSubset(it) }
    }
}
