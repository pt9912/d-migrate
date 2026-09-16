package dev.dmigrate.core.diff

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * Die Spaltentypen **einer** Tabelle, gegen die `schema compare` einen Cast
 * in einem rohen Ausdruck entscheidet ([ColumnCasts]).
 *
 * Ein Name loest nur auf, wenn er genau einer Spalte gehoert. Unaufloesbar
 * sind ein Schluesselwort (`user` ist eine Funktion, keine Spalte) und ein
 * Name, den zwei Spalten ohne Ruecksicht auf die Schreibweise teilen: das
 * Geruest entpackt `"Status"` zu `Status`, und ob damit die Spalte `Status`
 * oder — unquotiert gelesen — `status` gemeint ist, weiss es nicht mehr.
 */
internal class ColumnTypes(private val columns: Map<String, NeutralType>) {

    private val caseCollisions: Set<String> =
        columns.keys.groupBy { it.lowercase() }.filterValues { it.size > 1 }.keys

    val isEmpty: Boolean get() = columns.isEmpty()

    /** Der Typ der Spalte [name] — `null`, wenn der Name keine eindeutige Spalte ist. */
    fun typeOf(name: String): NeutralType? = when {
        SqlKeywords.isKeyword(name) -> null
        name.lowercase() in caseCollisions -> null
        else -> columns[name]
    }

    companion object {
        /** Kein Tabellenkontext: kein Cast faellt. */
        val NONE = ColumnTypes(emptyMap())

        fun of(table: TableDefinition?): ColumnTypes =
            if (table == null) NONE else ColumnTypes(table.columns.mapValues { it.value.type })
    }
}

/** Die Spaltentypen beider Seiten eines Vergleichs. */
internal data class SideColumns(val current: ColumnTypes, val desired: ColumnTypes) {
    companion object {
        val NONE = SideColumns(ColumnTypes.NONE, ColumnTypes.NONE)

        fun of(current: TableDefinition, desired: TableDefinition): SideColumns =
            SideColumns(ColumnTypes.of(current), ColumnTypes.of(desired))
    }
}
