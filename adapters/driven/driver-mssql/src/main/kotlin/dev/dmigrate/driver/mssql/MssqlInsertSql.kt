package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn

/**
 * Baut die INSERT-/MERGE-Anweisung einer MSSQL-Import-Session — reine
 * String-Erzeugung, damit sie ohne JDBC-Attrappen prüfbar ist.
 *
 * T-SQL hat kein `INSERT IGNORE`/`ON CONFLICT`: `skip` und `update` laufen
 * über `MERGE` mit einzeiliger `VALUES`-Quelle. `OUTPUT $action` macht
 * eingefügt/aktualisiert unterscheidbar (und liefert im `skip`-Fall gar keine
 * Zeile), sodass die Zeilen-Buchführung exakt bleibt statt geschätzt.
 */
internal object MssqlInsertSql {

    const val GEOGRAPHY_DEFAULT_SRID = 4326

    fun build(
        table: MssqlQualifiedTableName,
        columns: List<TargetColumn>,
        primaryKeyColumns: List<String>,
        onConflict: OnConflict,
        sourceSrids: Map<String, Int> = emptyMap(),
    ): String {
        require(columns.isNotEmpty()) {
            "Import into '${table.quotedPath()}' requires at least one column"
        }
        val columnList = columns.joinToString(", ") { MssqlIdentifiers.bracket(it.name) }
        val placeholders = columns.joinToString(", ") { placeholder(it, sourceSrids) }
        return when (onConflict) {
            OnConflict.ABORT -> "INSERT INTO ${table.quotedPath()} ($columnList) VALUES ($placeholders)"
            OnConflict.SKIP, OnConflict.UPDATE -> {
                require(primaryKeyColumns.isNotEmpty()) {
                    "onConflict=${onConflict.name.lowercase()} needs primary key columns for the MERGE predicate"
                }
                merge(table, columns, columnList, placeholders, primaryKeyColumns, onConflict)
            }
        }
    }

    private fun merge(
        table: MssqlQualifiedTableName,
        columns: List<TargetColumn>,
        columnList: String,
        placeholders: String,
        primaryKeyColumns: List<String>,
        onConflict: OnConflict,
    ): String {
        val pkSet = primaryKeyColumns.toSet()
        val onClause = primaryKeyColumns.joinToString(" AND ") {
            val col = MssqlIdentifiers.bracket(it)
            "tgt.$col = src.$col"
        }
        val insertValues = columns.joinToString(", ") { "src.${MssqlIdentifiers.bracket(it.name)}" }
        val updateColumns = columns.filterNot { it.name in pkSet }
        return buildString {
            append("MERGE INTO ${table.quotedPath()} AS tgt ")
            append("USING (VALUES ($placeholders)) AS src ($columnList) ")
            append("ON $onClause ")
            if (onConflict == OnConflict.UPDATE && updateColumns.isNotEmpty()) {
                val assignments = updateColumns.joinToString(", ") {
                    val col = MssqlIdentifiers.bracket(it.name)
                    "tgt.$col = src.$col"
                }
                append("WHEN MATCHED THEN UPDATE SET $assignments ")
            }
            append("WHEN NOT MATCHED THEN INSERT ($columnList) VALUES ($insertValues) ")
            // T-SQL verlangt das abschliessende Semikolon nach MERGE.
            append("OUTPUT \$action;")
        }
    }

    /**
     * `?` normal; Geometriespalten konstruieren aus dem gebundenen WKB wieder
     * `geometry`/`geography`. T-SQL kennt dafür nur die statische Methodenform
     * **mit** SRID. [TargetColumn.srid] ist auf SQL Server nie gesetzt — die
     * SRID ist dort Werteigenschaft, nicht Spaltenmetadatum (siehe
     * [MssqlDataReader]).
     *
     * Deshalb tritt [sourceSrids] ein: die SRID, die die **Quelle** an der
     * Spalte fuehrt. Erst wenn auch die schweigt, gilt der Typ-Default —
     * 0 (`geometry`) bzw. 4326 (`geography`, WGS 84, dieselbe Annahme wie
     * Reverse und Generate).
     */
    fun placeholder(column: TargetColumn, sourceSrids: Map<String, Int> = emptyMap()): String {
        val typeName = column.sqlTypeName?.lowercase()
        if (!isGeometryTypeName(typeName)) return "?"
        val srid = column.srid
            ?: sourceSrids[column.name]
            ?: if (typeName == "geography") GEOGRAPHY_DEFAULT_SRID else 0
        return "$typeName::STGeomFromWKB(?, $srid)"
    }

    fun isGeometryTypeName(typeNameLower: String?): Boolean =
        typeNameLower == "geometry" || typeNameLower == "geography"
}
