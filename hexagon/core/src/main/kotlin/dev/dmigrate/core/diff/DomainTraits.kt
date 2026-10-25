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
 * * Der **Kollationsname** ist ohne Anfuehrungszeichen und mit Gross-/Kleinschreibung gemeint:
 *   die Datei darf `C` oder `"C"` schreiben, der Katalog liefert `C`.
 */
object DomainTraits {

    /** Der Kollationsname ohne umschliessende Anfuehrungszeichen; `null` ohne Kollation. */
    fun collation(raw: String?): String? =
        raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }

    fun collation(type: CustomTypeDefinition): String? = collation(type.collation)
}
