package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.NeutralType

/**
 * Die `CHECK`-Klausel, mit der ein Dialekt ohne eigenen Enum-Typ den
 * Wertevorrat einer Spalte durchsetzt.
 *
 * Eine Stelle statt einer je Dialekt und Pfad: die Klausel muss von
 * `schema generate` und `schema migrate` **wortgleich** kommen, sonst
 * unterscheiden sich zwei Datenbanken desselben Schemas darin, wie sie
 * entstanden sind. Und sie muss die Form treffen, aus der der Vergleich den
 * Wertevorrat wieder herausliest.
 *
 * Die Reihenfolge der Werte bleibt, wie sie deklariert ist — sie wird nicht
 * sortiert. MySQLs nativer `ENUM` hat Ordinal-Semantik; dass der Vergleich sie
 * fuer den Abgleich zweier Darstellungen ignoriert, ist seine Sache, nicht die
 * des Renderers.
 */
object EnumValueCheck {

    /**
     * Der Wertevorrat, wenn [type] ein Enum ist, das seine Werte selbst fuehrt
     * — sonst `null`. Ein `refType`-Enum verweist auf einen eigenen Typ und
     * wird nicht ueber einen CHECK durchgesetzt.
     */
    fun inlineValues(type: NeutralType): List<String>? =
        (type as? NeutralType.Enum)?.takeIf { it.refType == null }?.values?.takeIf { it.isNotEmpty() }

    /**
     * Der ausdruecklich gesetzte Name des Wertevorrat-CHECKs (`values_constraint`, ADR 0069) — sonst `null`.
     * Nur eine Inline-Enum traegt ihn; ein `refType`-Enum hat keinen CHECK.
     */
    fun explicitName(type: NeutralType): String? =
        (type as? NeutralType.Enum)?.takeIf { it.refType == null }?.valuesConstraintName

    /** `CHECK (<spalte> IN ('a', 'b'))`, mit dem Quoting des Dialekts. */
    fun clause(column: String, values: List<String>, quote: (String) -> String): String {
        val allowed = values.joinToString(", ") { "'${it.replace("'", "''")}'" }
        return "CHECK (${quote(column)} IN ($allowed))"
    }

    /**
     * [clause] unter dem ausdruecklich gesetzten Namen des Typs, wo er einen traegt — sonst unbenannt. Fuer PostgreSQL:
     * dort vergibt sonst der Server den Namen.
     */
    fun clauseNamedIfSet(type: NeutralType, column: String, values: List<String>, quote: (String) -> String): String =
        explicitName(type)?.let { "CONSTRAINT ${quote(it)} " }.orEmpty() + clause(column, values, quote)

    /**
     * Der Constraint-Name fuer den Wertevorrat einer Spalte.
     *
     * Gebraucht, wo ein Dialekt einen unbenannten Constraint nicht
     * zurueckliest: das neutrale Modell fuehrt Constraints unter ihrem Namen,
     * ein namenloser hat dort keinen Platz — und was nicht zurueckkommt, kann
     * der Vergleich nicht sehen.
     */
    fun name(table: String, column: String): String = "ck_${table}_$column"

    /** Der Name des CHECKs: der gesetzte `values_constraint` des Typs, sonst die Konvention [name]. */
    fun name(table: String, column: String, type: NeutralType): String = explicitName(type) ?: name(table, column)

    /** [clause] mit vorangestelltem Namen: der gesetzte `values_constraint` von [type], sonst die Konvention. */
    fun namedClause(
        table: String,
        column: String,
        values: List<String>,
        quote: (String) -> String,
        type: NeutralType? = null,
    ): String = "CONSTRAINT ${quote(type?.let { name(table, column, it) } ?: name(table, column))} " +
        clause(column, values, quote)
}
