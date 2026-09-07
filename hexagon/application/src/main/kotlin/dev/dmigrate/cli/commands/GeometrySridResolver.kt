package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader

/**
 * Bestimmt je Tabelle und Geometriespalte das Bezugssystem, das die Quelle
 * fuehrt, damit der Import es setzen kann, wo das Ziel es nicht traegt.
 *
 * Zwei Herkuenfte, in dieser Reihenfolge: das gelesene Quellschema, und —
 * fuer Spalten, die dort keine SRID tragen — die Werte selbst
 * ([DataReader.geometrySrids]). SQL Server braucht den zweiten Weg, weil die
 * SRID dort Eigenschaft des Werts ist; die uebrigen Dialekte fuehren sie an
 * der Spalte und liefern aus dem Wert-Weg nichts.
 */
internal object GeometrySridResolver {

    /**
     * @param tables Tabellen, die uebertragen werden — nur sie werden geprobt.
     * @throws TransferPreflightException wenn eine Spalte Werte in mehr als
     *   einem Bezugssystem fuehrt. Das Ziel traegt eine SRID je Spalte; jede
     *   Wahl waere fuer einen Teil der Werte falsch, und zwar unsichtbar.
     */
    fun resolve(
        schema: SchemaDefinition,
        reader: DataReader,
        pool: ConnectionPool,
        tables: Collection<String>,
    ): Map<String, Map<String, Int>> {
        val fromSchema = TransferExecutionContext.geometrySridsOf(schema)
        val probed = tables.mapNotNull { table ->
            val missing = columnsWithoutSrid(schema, table, fromSchema[table].orEmpty().keys)
            if (missing.isEmpty()) return@mapNotNull null
            val found = reader.geometrySrids(pool, table, missing).mapValues { (column, srids) ->
                srids.singleOrNull() ?: throw TransferPreflightException(mixedSridMessage(table, column, srids))
            }
            if (found.isEmpty()) null else table to found
        }.toMap()

        if (probed.isEmpty()) return fromSchema
        return (fromSchema.keys + probed.keys).associateWith { table ->
            fromSchema[table].orEmpty() + probed[table].orEmpty()
        }
    }

    private fun columnsWithoutSrid(
        schema: SchemaDefinition,
        table: String,
        known: Set<String>,
    ): List<String> {
        val definition = schema.tables[table] ?: return emptyList()
        return definition.columns.filter { (name, column) ->
            column.type is NeutralType.Geometry && name !in known
        }.keys.toList()
    }

    private fun mixedSridMessage(table: String, column: String, srids: List<Int>): String =
        "Geometry column '$column' of table '$table' carries values in more than one spatial " +
            "reference system (${srids.joinToString(", ")}). The target holds one SRID per column, " +
            "so every choice would silently misplace part of the values. Split the column by SRID " +
            "or transform the values to a single system before transferring."
}
