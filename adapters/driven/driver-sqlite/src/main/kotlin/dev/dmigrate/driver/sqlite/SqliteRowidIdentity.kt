package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType

/**
 * Wann eine Spalte mit `generation: identity` auf SQLite ein
 * `INTEGER PRIMARY KEY AUTOINCREMENT` ist.
 *
 * SQLites AUTOINCREMENT ist ein einspaltiger rowid-Alias: er gilt nur fuer
 * eine `INTEGER`-Spalte, die **allein** den Primaerschluessel bildet. Das
 * neutrale Modell traegt dieselbe Zusage in zwei Formen — als Typ
 * `identifier` und als `integer`/`biginteger` mit `generation: identity` —,
 * und beide muessen auf SQLite dieselbe Spalte ergeben.
 *
 * Das Praedikat liegt hier **einmal**, weil drei Emitter es brauchen: der
 * Generate-Pfad ([SqliteColumnConstraintHelper]), der Diff-Pfad
 * ([SqliteDiffSqlBuilders]) und der Tabellen-Neubau
 * ([SqliteRebuildRenderer]). Vor dieser Zusammenfuehrung sah der Diff-Pfad
 * `generation` gar nicht an und legte die Spalte als blankes `INTEGER` mit
 * einer eigenen `PRIMARY KEY`-Klausel an — gueltiges DDL, angewandt, und das
 * AUTOINCREMENT still verloren.
 */
internal object SqliteRowidIdentity {

    /** Die Inline-Klausel, die beide Formen erzeugen. */
    const val CLAUSE: String = "INTEGER PRIMARY KEY AUTOINCREMENT"

    /** Die Typen, die SQLite als rowid-Alias fuehren kann. */
    fun supportsType(type: NeutralType): Boolean =
        type is NeutralType.Integer || type is NeutralType.BigInteger

    /** `true`, wenn die Spalte ihre Identity in `generation` traegt (nicht im Typ). */
    fun byGeneration(col: ColumnDefinition): Boolean =
        col.generation is ColumnGeneration.Identity && supportsType(col.type)

    /** `true`, wenn die Spalte in **einer** der beiden Formen ein Autowert-Schluessel ist. */
    fun inAnyForm(col: ColumnDefinition): Boolean =
        byGeneration(col) || (col.type as? NeutralType.Identifier)?.autoIncrement == true
}
