package dev.dmigrate.core.diff

import dev.dmigrate.core.model.CustomTypeDefinition

/**
 * `DEFAULT`, `NOT NULL` und Kollation einer Domain, wie Datei und Reader sie verschieden
 * schreiben (Issue #59).
 *
 * Nur PostgreSQL liest und rendert sie; jeder andere Dialekt ignoriert die Felder
 * (`supportsCustomTypes = false`, der Abdruck hasht benannte Typen dort gar nicht).
 *
 * * Der **Default** wird wie der einer Spalte verglichen ([ColumnDefaultProjection.same]).
 * * `NOT NULL` ist ein Wahrheitswert.
 * * Der **Kollationsname** ist ein Bezeichner mit Gross-/Kleinschreibung; ein Punkt gehoert zum
 *   Namen (`en_US.utf8`). Die Datei darf `C` oder `"C"` schreiben, der Katalog liefert `C`. Eine
 *   Kollation ausserhalb von `pg_catalog` steht gequotet und qualifiziert (`"app"."ci"`); das
 *   bleibt unveraendert.
 */
object DomainTraits {

    private val SINGLE_QUOTED = Regex("^\"([^\"]*)\"$")

    /**
     * Der Kollationsname; ein einzelner gequoteter Bezeichner (`"C"`) ohne Anfuehrungszeichen, eine
     * gequotete Qualifizierung (`"app"."ci"`) unveraendert. `null` ohne Kollation.
     */
    fun collation(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val name = SINGLE_QUOTED.find(trimmed)?.groupValues?.get(1) ?: trimmed
        return name.takeIf { it.isNotEmpty() }
    }

    fun collation(type: CustomTypeDefinition): String? = collation(type.collation)
}
