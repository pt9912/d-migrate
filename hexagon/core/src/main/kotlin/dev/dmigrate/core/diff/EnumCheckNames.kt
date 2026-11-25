package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * Der Name des Wertevorrat-CHECKs einer Inline-Enum im ziel-bewussten Vergleich (`values_constraint`, ADR 0069).
 *
 * Die Ist-Seite fuehrt den CHECK entweder als Zeile unter dem Namen, den der Server vergeben hat, oder (Datei) als
 * `values_constraint` am Typ. Die Soll-Seite zaehlt nur, wenn sie einen Namen **setzt**: ohne ihn bleibt der Name
 * ohne Wirkung, wie vor dem ADR.
 */
internal object EnumCheckNames {

    /**
     * Der Name des Wertevorrat-CHECKs je Spalte von [table]: der `values_constraint` einer Inline-Enum, sonst der
     * Name der gefalteten CHECK-Zeile ([EnumCheckFolding]), die den Wertevorrat der Spalte aufzaehlt.
     */
    fun current(table: TableDefinition, folded: Set<ConstraintDefinition>): Map<String, String> {
        val names = mutableMapOf<String, String>()
        for ((constraint, hit) in EnumCheckProjection.foldable(table)) {
            if (constraint in folded) names[hit.first] = constraint.name
        }
        for ((column, definition) in table.columns) {
            (definition.type as? NeutralType.Enum)?.valuesConstraintName?.let { names[column] = it }
        }
        return names
    }

    /** Ob die Soll-Seite einen Namen setzt, den die Ist-Seite ([current]) anders fuehrt. */
    fun renamed(current: String?, desired: NeutralType): Boolean {
        val wanted = (desired as? NeutralType.Enum)?.valuesConstraintName ?: return false
        return current != wanted
    }
}
