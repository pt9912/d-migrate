package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.EnumCheckProjection
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.metadata.EnumValueCheck

/**
 * Namenskonvention fuer die Constraints, die d-migrate in SQL Server anlegt.
 *
 * T-SQL macht Defaults, UNIQUE, CHECK und Primaerschluessel zu eigenstaendigen,
 * benannten Objekten. Der Generate-Pfad benennt sie deshalb explizit statt SQL
 * Server raten zu lassen ([ADR 0047]) — und **nur** dadurch findet der
 * Diff-Pfad sie spaeter wieder: `ALTER TABLE … ALTER COLUMN` scheitert, solange
 * ein Default-Constraint an der Spalte haengt, und um ihn zu loesen braucht es
 * seinen Namen.
 *
 * Die Konvention steht deshalb hier und nicht als Literal in den beiden
 * Pfaden: eine Abweichung zwischen Generate und Diff waere kein Kompilierfehler,
 * sondern ein Fehlschlag zur Laufzeit gegen eine echte Datenbank.
 *
 * Grenze: ein Schema, das **nicht** d-migrate angelegt hat, traegt die
 * automatisch vergebenen Namen von SQL Server (`DF__tabelle__spalte__1A2B3C4D`
 * mit zufaelligem Suffix). Die treffen diese Konvention nicht.
 */
internal object MssqlConstraintNames {

    fun default(table: String, column: String): String = "df_${table}_$column"

    fun unique(table: String, column: String): String = "uq_${table}_$column"

    fun check(table: String, column: String): String = "ck_${table}_$column"

    /**
     * Der Name des CHECKs, der den Wertevorrat von [column] auf der Seite durchsetzt, die ihn gerade traegt
     * ([bearing], gelesen aus [schema]) — der Name, unter dem er **geloest** werden muss. In dieser Reihenfolge: der
     * `values_constraint` der Inline-Enum (ADR 0069), der Name der gelesenen CHECK-Zeile, die den Wertevorrat der
     * Spalte aufzaehlt (eine Datenbank, die ihn nicht nach der Konvention benannt hat), sonst die Konvention.
     */
    fun valueCheck(table: String, column: String, bearing: ColumnDefinition?, schema: SchemaDefinition?): String =
        bearing?.type?.let(EnumValueCheck::explicitName)
            ?: schema?.tables?.get(table)?.constraints?.firstOrNull {
                it.type == ConstraintType.CHECK && EnumCheckProjection.valuesOf(it.expression, column) != null
            }?.name
            ?: check(table, column)

    fun primaryKey(table: String): String = "pk_$table"

    /** Fuer ein `references` an der Spalte — im Modell kein Constraint, in der Datenbank einer. */
    fun foreignKey(table: String, column: String): String = "fk_${table}_$column"
}
