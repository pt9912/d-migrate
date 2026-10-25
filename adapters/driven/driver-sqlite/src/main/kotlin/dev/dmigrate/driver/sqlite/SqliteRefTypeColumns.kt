package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.metadata.DomainTypes
import dev.dmigrate.driver.metadata.EnumValueCheck

/**
 * Eine Spalte mit `ref_type`, aufgeloest gegen die Custom Types ihres Schemas.
 *
 * SQLite hat kein Typ-Objekt: der benannte Typ lebt an der Spalte. Eine ENUM
 * wird `TEXT` + `CHECK (spalte IN …)`, eine DOMAIN ihr Basistyp + ihr `CHECK`
 * (`VALUE` -> Spalte). Der `CHECK` steht **unbenannt** am Spaltentext, wie ihn
 * `schema generate` schreibt: der Reverse liest einen benannten zurueck, und das
 * Soll (`ref_type`) kennt dafuer keinen Gegenpart -- jeder Folgelauf meldete
 * Drift.
 *
 * Eine Stelle fuer `schema generate`, `CREATE TABLE`/`ADD COLUMN` des Diffs und
 * den Tabellen-Neubau, damit die drei Pfade dieselbe Spalte schreiben.
 */
internal object SqliteRefTypeColumns {

    /** [column] ist die Spalte, wie sie gerendert wird; [check] ihre `CHECK (…)`-Klausel, falls es eine gibt. */
    data class Resolved(val column: ColumnDefinition, val check: String?)

    fun resolve(
        name: String,
        col: ColumnDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
        quote: (String) -> String,
    ): Resolved {
        val type = col.type as? NeutralType.Enum ?: return Resolved(col, null)
        val custom = type.refType?.let { customTypes[it] } ?: return Resolved(col, null)
        return when (custom.kind) {
            CustomTypeKind.ENUM -> Resolved(
                col,
                custom.values?.takeIf { it.isNotEmpty() }?.let { EnumValueCheck.clause(name, it, quote) },
            )
            CustomTypeKind.DOMAIN -> resolveDomain(name, col, custom, quote)
            CustomTypeKind.COMPOSITE -> Resolved(col, null)
        }
    }

    private fun resolveDomain(
        name: String,
        col: ColumnDefinition,
        domain: CustomTypeDefinition,
        quote: (String) -> String,
    ): Resolved {
        val base = baseType(domain) ?: return Resolved(col, null)
        val check = domain.check?.let { "CHECK (${DomainTypes.substituteValueToken(it, quote(name))})" }
        return Resolved(col.copy(type = base), check)
    }

    /** Der neutrale Basistyp einer Domain, oder `null`, wo es keinen gibt (E053 beim Aufrufer). */
    fun baseType(domain: CustomTypeDefinition): NeutralType? =
        DomainTypes.resolveBaseType(domain.baseType ?: "text", domain.precision, domain.scale)

    /** Die Tabelle mit allen Domain-Spalten auf ihrem Basistyp -- fuer den Typvergleich der Spaltenabbildung. */
    fun withDomainBaseTypes(
        table: dev.dmigrate.core.model.TableDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
    ): dev.dmigrate.core.model.TableDefinition = table.copy(
        columns = table.columns.mapValues { (name, col) -> resolve(name, col, customTypes) { it }.column },
    )

    /**
     * Die Spalten, die auf eine DOMAIN zeigen. Ein Wechsel ihres Basistyps meldet der Vergleich nicht als
     * `AlterColumnType` (die Spalte bleibt `ref_type`), sondern als `AlterCustomType` am Typ; der Neubau muss
     * ihn deshalb selbst an den aufgeloesten Basistypen pruefen (Issue #54, Review).
     */
    fun domainColumns(
        table: dev.dmigrate.core.model.TableDefinition,
        customTypes: Map<String, CustomTypeDefinition>,
    ): Set<String> = table.columns.filter { (_, col) ->
        val refType = (col.type as? NeutralType.Enum)?.refType
        refType != null && customTypes[refType]?.kind == CustomTypeKind.DOMAIN
    }.keys
}
