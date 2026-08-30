package dev.dmigrate.driver.sqlite

import java.sql.Connection

/**
 * SpatiaLites `geometry_columns` — die Autoritaet darueber, welche Spalte
 * wirklich eine Geometriespalte ist.
 *
 * SQLite erzwingt keine Typen: eine Spalte darf `POINT` heissen und Text
 * enthalten. Auf den deklarierten Namen allein zu bauen hiesse, so eine Spalte
 * durch `ST_AsBinary` zu schicken — das liefert `NULL`, und die Daten waeren
 * ohne Fehlermeldung weg. Umgekehrt gibt es die Tabelle nur mit geladener
 * Extension; ohne sie ist die Antwort leer, und beide Pfade lassen die Spalte
 * unberuehrt.
 */
internal object SqliteGeometryCatalog {

    /** Name → SRID der registrierten Geometriespalten einer Tabelle. */
    fun registeredColumns(conn: Connection, table: SqliteQualifiedTableName): Map<String, Int> =
        runCatching {
            val catalog = "${table.schemaOrMain()}.geometry_columns"
            conn.prepareStatement(
                "SELECT f_geometry_column, srid FROM $catalog WHERE lower(f_table_name) = lower(?)",
            ).use { ps ->
                ps.setString(1, table.table)
                ps.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) put(rs.getString("f_geometry_column").lowercase(), rs.getInt("srid"))
                    }
                }
            }
        }.getOrDefault(emptyMap())
}
