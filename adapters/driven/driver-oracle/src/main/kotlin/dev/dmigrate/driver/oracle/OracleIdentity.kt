package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType

/**
 * Wann eine Spalte in Oracle eine Identity-Spalte wird — und in welchem Modus.
 *
 * Beide Schreibweisen zaehlen: `generation: identity` auf einem numerischen
 * Typ und `identifier` mit `auto_increment`. Sie ergeben dasselbe DDL
 * (`GENERATED … AS IDENTITY`), und wer nur den Typ fragt, uebersieht die
 * Form, die jeder Reverse liefert.
 *
 * Die Quelle der Wahrheit ist
 * [OracleColumnConstraintHelper.generateColumnSql]; diese Stelle bildet
 * dieselbe Entscheidung fuer die Aufrufer nach, die kein DDL schreiben
 * (Neubau-Planer und -Renderer).
 */
internal object OracleIdentity {

    fun isIdentity(type: NeutralType, col: ColumnDefinition?): Boolean =
        (col?.generation is ColumnGeneration.Identity && supportsIdentity(type)) ||
            (type as? NeutralType.Identifier)?.autoIncrement == true

    fun isIdentity(col: ColumnDefinition): Boolean = isIdentity(col.type, col)

    /**
     * Der Modus, in dem die Spalte am Ende stehen soll. Die
     * `auto_increment`-Schreibweise nennt keinen; sie entspricht `ALWAYS`,
     * wie der Spalten-Helfer es rendert.
     */
    fun modeOf(col: ColumnDefinition): IdentityMode =
        (col.generation as? ColumnGeneration.Identity)?.mode ?: IdentityMode.ALWAYS

    /** Oracle traegt IDENTITY nur auf ganzzahligen NUMBER-Spalten. */
    private fun supportsIdentity(type: NeutralType): Boolean = when (type) {
        is NeutralType.Integer, is NeutralType.SmallInt, is NeutralType.BigInteger -> true
        is NeutralType.Decimal -> type.scale == 0
        else -> false
    }
}
