package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * Loest den Wertevorrat einer `ref_type`-Enum-Spalte aus den Custom Types auf.
 *
 * Ein `Enum(refType = r)` traegt seine Werte nicht selbst; sie stehen in
 * `custom_types[r]`. [EnumCheckProjection.declaredVocabulary] liest nur
 * `Enum.values` und sieht fuer eine solche Spalte nichts. Ein Dialekt, dessen
 * Reverse den Wertevorrat als CHECK zurueckgibt (SQLite: unbenannter CHECK an
 * der Spalte), hat auf der Ist-Seite ein Vokabular und auf der Soll-Seite keins
 * — Vergleich und Abdruck meldeten bei jedem Lauf Drift.
 *
 * Die Funktion ersetzt `Enum(refType = r)` durch `Enum(values = …)`, wenn
 * `custom_types[r]` ein ENUM ist. DOMAIN und COMPOSITE bleiben unberuehrt, ebenso
 * eine Spalte, deren Typ nicht aufloesbar ist. Sie ist **nur** fuer Abdruck und
 * Wertevorrats-Vergleich gedacht: das Ergebnis darf nicht in geplante
 * Operationen oder erzeugte DDL fliessen.
 */
object RefTypeVocabulary {

    fun resolve(
        tables: Map<String, TableDefinition>,
        customTypes: Map<String, CustomTypeDefinition>,
    ): Map<String, TableDefinition> =
        if (customTypes.isEmpty()) tables else tables.mapValues { (_, table) -> resolve(table, customTypes) }

    /** Wie [resolve] fuer eine Tabelle; ohne die Spalten, die [skip] ausnimmt. */
    fun resolve(
        table: TableDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
        skip: (String) -> Boolean = { false },
    ): TableDefinition {
        if (customTypes.isEmpty()) return table
        var changed = false
        val columns = table.columns.mapValues { (name, column) ->
            val resolved = if (skip(name)) null else resolvedColumn(column, customTypes)
            if (resolved != null) changed = true
            resolved ?: column
        }
        return if (changed) table.copy(columns = columns) else table
    }

    private fun resolvedColumn(
        column: ColumnDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
    ): ColumnDefinition? {
        val type = column.type as? NeutralType.Enum ?: return null
        val ref = type.refType ?: return null
        if (type.values != null) return null
        val definition = customTypes[ref]?.takeIf { it.kind == CustomTypeKind.ENUM } ?: return null
        val values = definition.values?.takeIf { it.isNotEmpty() } ?: return null
        return column.copy(type = NeutralType.Enum(values = values))
    }
}
