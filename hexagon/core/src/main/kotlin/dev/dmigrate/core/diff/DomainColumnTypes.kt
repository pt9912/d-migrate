package dev.dmigrate.core.diff

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * Der projizierte Typ der Domain-Spalten einer Tabelle, so wie ihn der Reverse des Ziels liest.
 *
 * Eine Domain liegt in SQLite als ihr Basistyp an der Spalte; der Reverse liest `Integer`, `Float`, `Binary`, nie
 * `Enum(refType)`. Die kontextfreie Projektion macht aus `Enum(refType)` `Text()` und stellte es gegen den
 * Basistyp — ein Unterschied, den es nicht gibt ([TargetProjection.typeWithCustomTypes]).
 *
 * Eine Spalte, die auch die Gegenseite als `ref_type` fuehrt, fehlt im Ergebnis: dort deckt der Vergleich der Custom
 * Types einen Basistypwechsel ab (`AlterCustomType`), und ein zweites `ColumnDiff` aenderte Operations-IDs.
 *
 * Nur fuer die Vergleichsentscheidung: das Ergebnis fliesst nicht in geplante Operationen oder erzeugte DDL.
 */
internal object DomainColumnTypes {

    fun projected(
        table: TableDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
        counterpart: TableDefinition,
        project: (NeutralType, Map<String, CustomTypeDefinition>) -> NeutralType,
    ): Map<String, NeutralType> {
        if (customTypes.isEmpty()) return emptyMap()
        return table.columns.mapNotNull { (name, column) ->
            val ref = (column.type as? NeutralType.Enum)?.refType
            val isDomain = ref != null && customTypes[ref]?.kind == CustomTypeKind.DOMAIN
            val counterpartIsRef = (counterpart.columns[name]?.type as? NeutralType.Enum)?.refType != null
            if (isDomain && !counterpartIsRef) name to project(column.type, customTypes) else null
        }.toMap()
    }
}
