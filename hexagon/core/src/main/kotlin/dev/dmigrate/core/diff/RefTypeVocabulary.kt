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
 * `custom_types[r]` ein ENUM ist — oder eine DOMAIN, deren CHECK eine Werteliste
 * ist (`VALUE IN ('a', 'b')`): der Reverse sieht dort denselben Wertelisten-CHECK
 * und kann ihn nicht von dem einer Enum unterscheiden. Jede andere DOMAIN und
 * COMPOSITE bleiben unberuehrt, ebenso eine Spalte, deren Typ nicht aufloesbar
 * ist.
 *
 * Eine aufgeloeste **Domain** behaelt ihren `refType`: der Kanonisierer braucht ihn,
 * um den Basistyp (`integer`, `varchar(20)`) zu finden, den der Reverse liest.
 *
 * Die Funktion ist **nur** fuer Abdruck und Wertevorrats-Vergleich gedacht: das
 * Ergebnis darf nicht in geplante Operationen oder erzeugte DDL fliessen.
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
        val definition = customTypes[ref] ?: return null
        val values = vocabularyOf(definition) ?: return null
        val resolved = if (definition.kind == CustomTypeKind.DOMAIN) {
            NeutralType.Enum(values = values, refType = ref)
        } else {
            NeutralType.Enum(values = values)
        }
        return column.copy(type = resolved)
    }

    /**
     * Der Wertevorrat, den [definition] an der Spalte als CHECK hinterlaesst —
     * `null`, wo es keinen gibt (COMPOSITE, eine Domain ohne Werteliste, eine Enum
     * ohne Werte).
     */
    fun vocabularyOf(definition: CustomTypeDefinition): List<String>? = when (definition.kind) {
        CustomTypeKind.ENUM -> definition.values?.takeIf { it.isNotEmpty() }
        CustomTypeKind.DOMAIN -> definition.check?.let { check ->
            EnumCheckProjection.valuesOf(substituteValueToken(check), VALUE_COLUMN)
        }
        CustomTypeKind.COMPOSITE -> null
    }

    private const val VALUE_COLUMN = "dm_value"
    private val VALUE_TOKEN = Regex("\\bVALUE\\b", RegexOption.IGNORE_CASE)

    /**
     * `VALUE` — in beliebiger Schreibweise, wie es auch der SQLite-Generator ersetzt —
     * durch einen festen Spaltennamen, ausserhalb von `'…'`-Literalen.
     */
    private fun substituteValueToken(check: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < check.length) {
            if (check[index] == '\'') {
                var end = index + 1
                while (end < check.length) {
                    if (check[end] == '\'' && check.getOrNull(end + 1) == '\'') end += 2
                    else if (check[end] == '\'') { end++; break } else end++
                }
                out.append(check, index, end)
                index = end
            } else {
                val next = check.indexOf('\'', index).let { if (it < 0) check.length else it }
                out.append(VALUE_TOKEN.replace(check.substring(index, next), VALUE_COLUMN))
                index = next
            }
        }
        return out.toString()
    }
}
