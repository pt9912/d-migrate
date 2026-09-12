package dev.dmigrate.driver

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Nimmt Funktions-Defaults heraus, die auf dem Ziel nicht gelten — und sagt es.
 *
 * **Warum es dieses Feld ueberhaupt trifft.** Die Spec fuehrt
 * `default: <funktion>` als **uebersetzten Namen** (`current_timestamp`,
 * `gen_uuid`), nicht als freien SQL-Text. Der Reverse haelt sich daran nur,
 * solange er die Funktion kennt: was er nicht kennt, legt er als
 * `DefaultValue.FunctionCall` mit dem **Servertext** ab. Aus einem
 * PostgreSQL-Reverse wird so `ARRAY['NEW'::order_status]` — und die Renderer
 * reichten das weiter, weil sie im Feld einen Namen erwarteten.
 *
 * Gemeldet von einem Konsumenten mit genau dieser Zeile, gemessen gegen alle
 * fuenf Ziele:
 *
 * ```
 * [status_history] NVARCHAR(MAX) NOT NULL
 *   CONSTRAINT [df_orders_status_history] DEFAULT ARRAY['NEW'::order_status]()
 * ```
 *
 * **Warum hier und nicht je Dialekt.** `DEFAULT` wird an einem Dutzend Stellen
 * gerendert — allein SQLite und PostgreSQL an je drei. Die Frage „gilt dieser
 * Text auf dem Ziel?" ist aber ueberall dieselbe, und
 * [AbstractDdlGenerator.generate] ist die eine Stelle, durch die jeder
 * generate-Lauf aller fuenf Dialekte geht. Das Schema wird dort einmal
 * gefiltert; danach kann kein Renderer den Text mehr sehen.
 *
 * **Beurteilt wird das Ziel, nicht die Herkunft** — dieselbe Linie wie in
 * [RawSqlExpressionPortability]: `::` ist in T-SQL ein Syntaxfehler,
 * gleichgueltig, wer es geschrieben hat. Ein Text ohne solche Marker bleibt
 * stehen; ihn zu beurteilen braeuchte einen Parser je Dialekt.
 */
object ForeignFunctionDefaultFilter {

    data class Result(val schema: SchemaDefinition, val notes: List<TransformationNote>)

    fun apply(schema: SchemaDefinition, target: DatabaseDialect): Result {
        val notes = mutableListOf<TransformationNote>()
        val tables = schema.tables.mapValues { (tableName, table) ->
            filterTable(tableName, table, target, notes)
        }
        return if (notes.isEmpty()) Result(schema, emptyList()) else Result(schema.copy(tables = tables), notes)
    }

    private fun filterTable(
        tableName: String,
        table: TableDefinition,
        target: DatabaseDialect,
        notes: MutableList<TransformationNote>,
    ): TableDefinition {
        var changed = false
        val columns = LinkedHashMap<String, ColumnDefinition>(table.columns.size)
        for ((colName, col) in table.columns) {
            val refusal = refusalFor(tableName, colName, col, target)
            if (refusal == null) {
                columns[colName] = col
            } else {
                notes += refusal
                columns[colName] = col.copy(default = null)
                changed = true
            }
        }
        return if (changed) table.copy(columns = columns) else table
    }

    private fun refusalFor(
        tableName: String,
        colName: String,
        col: ColumnDefinition,
        target: DatabaseDialect,
    ): TransformationNote? {
        val name = (col.default as? DefaultValue.FunctionCall)?.name ?: return null
        val verdict = RawSqlExpressionPortability.assess(name, target)
        if (verdict.portable) return null
        return RawSqlExpressionPortability.notPortableNote(
            "column", "$tableName.$colName", "function default", verdict.reason, target,
        )
    }
}
