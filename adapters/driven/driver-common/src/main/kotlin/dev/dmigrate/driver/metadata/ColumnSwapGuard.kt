package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition

/**
 * `column-generation-diff-unmapped.md`: ob eine Spalte gefahrlos gedroppt und
 * mit derselben Deklaration neu angelegt werden kann (Spaltentausch mit
 * Datenkopie), auf dem Weg, den MySQL, Oracle und SQL Server fuer den
 * Kind-Wechsel (gewoehnlich ↔ berechnet) brauchen, weil keiner der drei ihn
 * in place kann.
 *
 * Die Frage ist dialektunabhaengig — sie fragt nur das neutrale Modell, nicht
 * den Server —, deshalb hier in `driver-common` statt dreimal je Treiber.
 * Was ein Dialekt beim Tausch selbst rendert (die Anweisungsfolge, die
 * Spaltendeklaration), bleibt bei ihm; dieser Wächter entscheidet nur, ob der
 * Tausch ueberhaupt sicher ist.
 *
 * Bewusst konservativ: eine Spalte mit Index, benannter Constraint,
 * Primaerschluessel-Mitgliedschaft oder Fremdschluessel-Bezug (ausgehend
 * **oder** eingehend) wird abgelehnt, statt den betroffenen Index/Constraint
 * abzubauen und danach neu anzulegen — das waere ein groesserer, eigener
 * Entwurf (Index-/FK-Wiederherstellung nach dem Tausch). Views, die die
 * Spalte lesen, kann das neutrale Modell nicht durchsuchen; wer eine
 * abhaengige Sicht hat, sieht das erst am Server — dieselbe Grenze, die die
 * bisherigen Blockmeldungen aller drei Dialekte schon benennen.
 */
object ColumnSwapGuard {

    sealed interface Result {
        data object Safe : Result
        data class Encumbered(val reason: String) : Result
    }

    fun check(schema: SchemaDefinition, table: String, column: String): Result {
        val tableDef = schema.tables[table]
            ?: return Result.Encumbered("table `$table` is not in the schema this direction reads")
        val col = tableDef.columns[column]
            ?: return Result.Encumbered("column `$table`.`$column` is not in the schema this direction reads")

        if (column in tableDef.primaryKey) {
            return Result.Encumbered("`$table`.`$column` is part of the primary key")
        }
        if (col.unique || col.uniqueConstraintName != null) {
            return Result.Encumbered("`$table`.`$column` carries a UNIQUE constraint")
        }
        if (col.references != null) {
            return Result.Encumbered("`$table`.`$column` carries an outgoing foreign key reference")
        }
        tableDef.indices.firstOrNull { index -> index.columns.any { it.name == column } }?.let { index ->
            return Result.Encumbered("`$table`.`$column` is indexed (`${index.name ?: "unnamed index"}`)")
        }
        tableDef.constraints.firstOrNull { column in it.columns.orEmpty() }?.let { constraint ->
            return Result.Encumbered("`$table`.`$column` carries constraint `${constraint.name}`")
        }
        inboundForeignKey(schema, table, column)?.let { fromTable ->
            return Result.Encumbered("`$table`.`$column` is referenced by a foreign key from `$fromTable`")
        }
        return Result.Safe
    }

    /** The name of the first OTHER table whose column or FK constraint references `table.column`, or null. */
    private fun inboundForeignKey(schema: SchemaDefinition, table: String, column: String): String? {
        for ((otherTable, otherDef) in schema.tables) {
            val inlineHit = otherDef.columns.values.any {
                it.references?.table == table && it.references?.column == column
            }
            if (inlineHit) return otherTable
            val constraintHit = otherDef.constraints.any {
                it.type == ConstraintType.FOREIGN_KEY &&
                    it.references?.table == table &&
                    column in (it.references?.columns.orEmpty())
            }
            if (constraintHit) return otherTable
        }
        return null
    }
}
