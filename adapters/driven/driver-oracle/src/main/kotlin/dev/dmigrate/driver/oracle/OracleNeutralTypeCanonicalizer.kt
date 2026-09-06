package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer
import dev.dmigrate.driver.metadata.SchemaReaderUtils

/**
 * Oracle neutral-type canonicaliser as the live composition of the driver's
 * own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. [toColumnInput] bridges the rendered Oracle-DDL
 * spelling into the [OracleTypeMapping.ColumnInput] shape
 * [OracleTypeMapping.mapColumn] consumes -- the only real ambiguity there is
 * that `NUMBER(9)` and `VARCHAR2(4000)` share the same single-number-in-
 * parens spelling but mean precision vs. length; [toColumnInput] routes on
 * the type name, not on a generic "the number in parens" guess. Reverse
 * notes are discarded (type-only projection), EXCEPT the unknown-type
 * fallback (R301): an unbridged spelling returns the input unchanged, so a
 * gap in the bridge surfaces as loud drift instead of a silent false
 * type-equivalence.
 *
 * `Identifier(autoIncrement=true)` is deliberately NOT an identity carve-out
 * here (anders als bei PostgreSQL): Oracles [OracleTypeMapping.mapIdentity]
 * (Slice 1) faltet JEDE Identity-Spalte auf ihren Basistyp
 * (`smallint`/`integer`/`biginteger`/`decimal`) plus `generation:
 * identity` -- `NeutralType.Identifier` selbst kommt beim Reverse nie
 * zurueck. Die Komposition liefert das schon richtig (`toSql` rendert
 * `NUMBER(9)`, `mapNumberPrecision(9, null)` liest `integer`), ein
 * Fixpunkt-Carve-out waere hier schlicht falsch.
 *
 * Einziger Identity-Carve-out: [NeutralType.Enum] mit `refType` -- die
 * Aufloesung braucht die Custom Types des Schemas (siehe
 * [NeutralTypeCanonicalizer.canonicalize] mit Kontext-Parameter), eine
 * `(NeutralType) -> NeutralType`-Projektion sieht sie nicht.
 * [NeutralType.Geometry] bleibt ebenfalls Identitaet -- in der Praxis
 * unerreichbar (`canGenerateSpatial()` = `false` blockt jede Tabelle mit
 * Geometrie-Spalten vor der Generierung), aber definiert fuer
 * Vollstaendigkeit, analog den anderen vier Treibern.
 */
internal object OracleNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = OracleTypeMapper()

    override fun canonicalize(type: NeutralType): NeutralType = canonicalize(type, emptyMap())

    /**
     * Mit den Custom Types des Schemas laesst sich ein `refType` aufloesen;
     * ohne sie (leere Map, der 1-Parameter-Pfad) bleibt nur die Frage, ob
     * `type.values` selbst schon etwas hergibt.
     *
     * Aufgeloest wird in die Form, die [OracleColumnConstraintHelper.enumColumn]
     * tatsaechlich schreibt: eine `DOMAIN` faellt IMMER auf `CLOB`
     * (Oracle loest heute keine Basistypen fuer Domains auf, E053) --
     * unabhaengig vom deklarierten `baseType` der Domain, anders als bei
     * MSSQL. Fuer jeden anderen Fall gibt es dort **kein** "unbekannt
     * bleiben" -- `enumColumn` faellt letztlich immer auf `plainColumn`
     * zurueck (`VARCHAR2(4000)`, ungebunden), egal ob `refType` gar nicht
     * im Schema steht oder auf einen Custom Type ohne `values` zeigt (z. B.
     * `COMPOSITE`). [resolveRefType] bildet das nach: sie liefert nie
     * `null`, sondern im schlimmsten Fall einen wertelosen `Enum`, der ueber
     * [renderedColumnType] genau auf diese ungebundene Spalte projiziert.
     */
    override fun canonicalize(
        type: NeutralType,
        customTypes: Map<String, CustomTypeDefinition>,
    ): NeutralType = when {
        type is NeutralType.Geometry -> type
        type is NeutralType.Enum && type.refType != null ->
            canonicalize(resolveRefType(type, customTypes), customTypes)
        else -> {
            val mapped = OracleTypeMapping.mapColumn("", toColumnInput(renderedColumnType(type)))
            if (mapped.note?.code == "R301") type else mapped.type
        }
    }

    /** Der Typ, in den der Spalten-Helfer einen `refType` tatsaechlich aufloest. */
    private fun resolveRefType(
        type: NeutralType.Enum,
        customTypes: Map<String, CustomTypeDefinition>,
    ): NeutralType {
        val customType = type.refType?.let { customTypes[it] }
        if (customType?.kind == CustomTypeKind.DOMAIN) return NeutralType.Text(maxLength = null)
        return NeutralType.Enum(values = customType?.values ?: type.values)
    }

    /**
     * Die Spalte, die der Generator schreibt -- fuer wertebasierte Enums
     * [OracleColumnConstraintHelper.boundedEnumColumn]s `VARCHAR2(<Breite>)`
     * (via [OracleTypeMapper.enumWidth], geteilte Quelle), sonst der
     * Typmapper. Ein wertloser, refType-loser Enum faellt wie
     * `plainColumn` auf `typeMapper.toSql` (`VARCHAR2(4000)`) zurueck.
     */
    private fun renderedColumnType(type: NeutralType): String {
        val enumValues = (type as? NeutralType.Enum)?.values
        return if (enumValues != null) {
            "VARCHAR2(${OracleTypeMapper.enumWidth(enumValues)})"
        } else {
            typeMapper.toSql(type)
        }
    }

    /**
     * `NUMBER(9)` und `VARCHAR2(4000)` teilen dieselbe
     * Einzelzahl-in-Klammern-Schreibweise, meinen aber Praezision bzw.
     * Laenge -- deshalb routet diese Bruecke ueber den Typnamen, statt die
     * Zahl generisch zu raten.
     */
    private fun toColumnInput(rendered: String): OracleTypeMapping.ColumnInput {
        val upper = rendered.uppercase()
        val typeName = upper.substringBefore('(').trim()
        return if (typeName == "NUMBER") {
            val (precision, scale) = SchemaReaderUtils.parenPrecisionScale(upper)
            OracleTypeMapping.ColumnInput(
                typeName = typeName,
                length = null,
                precision = precision ?: SchemaReaderUtils.parenLength(upper),
                scale = scale,
                isIdentity = false,
                identityGeneration = null,
                identitySequenceName = null,
            )
        } else {
            OracleTypeMapping.ColumnInput(
                typeName = typeName,
                length = SchemaReaderUtils.parenLength(upper),
                precision = null,
                scale = null,
                isIdentity = false,
                identityGeneration = null,
                identitySequenceName = null,
            )
        }
    }
}
