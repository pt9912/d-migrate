package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TargetColumn

/**
 * Baut die INSERT-/MERGE-Anweisung einer Oracle-Import-Session -- reine
 * String-Erzeugung, damit sie ohne JDBC-Attrappen prüfbar ist.
 *
 * Oracle kennt kein `INSERT IGNORE`/`ON CONFLICT`: `skip` und `update` laufen
 * über `MERGE INTO ... USING (SELECT ? AS c0, ... FROM DUAL) src`. Anders als
 * MSSQLs `MERGE ... OUTPUT $action` hat Oracles `MERGE` keine
 * Aktions-Rückgabe -- die Zeilen-Buchführung nutzt deshalb zwei
 * unterschiedliche Wege (siehe [OracleTableImportSession]):
 * - `skip` (nur `WHEN NOT MATCHED THEN INSERT`, kein `WHEN MATCHED`-Zweig):
 *   die per-Statement betroffene Zeilenzahl aus `executeBatch()` ist exakt
 *   1 (eingefügt) oder 0 (schon vorhanden, übersprungen) -- dieselbe
 *   Unterscheidung wie PostgreSQLs `ON CONFLICT DO NOTHING`.
 * - `update` (`WHEN MATCHED THEN UPDATE` UND `WHEN NOT MATCHED THEN INSERT`):
 *   beide Zweige melden 1 betroffene Zeile, aus der Batch-Zählung allein
 *   nicht unterscheidbar -- die Session bucht das als `rowsUnknown`, statt
 *   eine Kennzahl zu erfinden oder auf Batching zu verzichten.
 */
internal object OracleInsertSql {

    fun build(
        table: OracleQualifiedTableName,
        columns: List<TargetColumn>,
        primaryKeyColumns: List<String>,
        onConflict: OnConflict,
    ): String {
        require(columns.isNotEmpty()) {
            "Import into '${table.quotedPath()}' requires at least one column"
        }
        return when (onConflict) {
            OnConflict.ABORT -> {
                val columnList = columns.joinToString(", ") { OracleIdentifiers.quote(it.name) }
                val placeholders = columns.joinToString(", ") { "?" }
                "INSERT INTO ${table.quotedPath()} ($columnList) VALUES ($placeholders)"
            }
            OnConflict.SKIP, OnConflict.UPDATE -> {
                require(primaryKeyColumns.isNotEmpty()) {
                    "onConflict=${onConflict.name.lowercase()} needs primary key columns for the MERGE predicate"
                }
                merge(table, columns, primaryKeyColumns, onConflict)
            }
        }
    }

    private fun merge(
        table: OracleQualifiedTableName,
        columns: List<TargetColumn>,
        primaryKeyColumns: List<String>,
        onConflict: OnConflict,
    ): String {
        val pkSet = primaryKeyColumns.toSet()
        // Positionelle Aliase (c0, c1, ...) statt Zielspaltennamen: eine Zielspalte
        // koennte ein Oracle-reserviertes Wort sein, das als Quell-Alias in der
        // DUAL-Projektion Probleme machen wuerde -- die Aliase sind rein intern.
        val aliasOf = columns.mapIndexed { index, col -> col.name to "c$index" }.toMap()
        val srcColumns = columns.mapIndexed { index, _ -> "? AS ${OracleIdentifiers.quote("c$index")}" }
            .joinToString(", ")
        val onClause = primaryKeyColumns.joinToString(" AND ") {
            "tgt.${OracleIdentifiers.quote(it)} = src.${OracleIdentifiers.quote(aliasOf.getValue(it))}"
        }
        val columnList = columns.joinToString(", ") { OracleIdentifiers.quote(it.name) }
        val insertValues = columns.joinToString(", ") { "src.${OracleIdentifiers.quote(aliasOf.getValue(it.name))}" }
        val updateColumns = columns.filterNot { it.name in pkSet }
        return buildString {
            append("MERGE INTO ${table.quotedPath()} tgt ")
            append("USING (SELECT $srcColumns FROM DUAL) src ")
            append("ON ($onClause) ")
            if (onConflict == OnConflict.UPDATE && updateColumns.isNotEmpty()) {
                val assignments = updateColumns.joinToString(", ") {
                    "tgt.${OracleIdentifiers.quote(it.name)} = src.${OracleIdentifiers.quote(aliasOf.getValue(it.name))}"
                }
                append("WHEN MATCHED THEN UPDATE SET $assignments ")
            }
            append("WHEN NOT MATCHED THEN INSERT ($columnList) VALUES ($insertValues)")
        }
    }
}
