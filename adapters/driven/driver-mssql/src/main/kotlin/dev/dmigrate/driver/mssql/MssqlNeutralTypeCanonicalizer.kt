package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer
import dev.dmigrate.driver.metadata.SchemaReaderUtils

/**
 * MSSQL neutral-type canonicaliser as the live composition of the driver's
 * own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. [toColumnInput] bridges the rendered T-SQL spelling
 * into the `sys.columns` shape [MssqlTypeMapping.mapColumn] consumes — the
 * only real work there is the catalog's byte-counted `max_length`
 * (`NVARCHAR(n)` stores `2n`, `(MAX)` stores `-1`). Reverse notes are
 * discarded (type-only projection), EXCEPT the unknown-type fallback (R301):
 * an unbridged spelling returns the input unchanged, so a gap surfaces as
 * loud drift instead of a silent false type-equivalence.
 *
 * What folds, and why it is honest for T-SQL: `json`, arrays, `fulltext`,
 * unbounded/overlong text and `char` above 4000 all render as
 * `NVARCHAR(MAX)` and read back as `text` (W136/W137/W132); `email` is
 * `NVARCHAR(254)`; a `decimal` above precision 38 is clamped (W139); an
 * `identifier` without `autoIncrement` is a plain `INT`.
 *
 * Two deliberate divergences from the other three drivers:
 *
 * - **[NeutralType.Geometry] is NOT carved out.** PG/MySQL/SQLite keep it
 *   identity because their reverse reconstructs subtype and SRID from
 *   dialect metadata. SQL Server cannot: subtype and SRID are properties of
 *   the VALUE, not of the column, so the catalog yields only `geometry` or
 *   `geography` (R345). Folding is therefore the storage reality — and it
 *   keeps the distinction that DOES survive, because the generate direction
 *   picks `geography` exactly for geodetic SRIDs
 *   ([MssqlTypeMapper.GEODETIC_SRID_RANGE]). Carving it out would report
 *   drift on every geometry column of a lossless round trip. The SRID
 *   fidelity gap itself is tracked in `spec/type-mapping.md`.
 * - **Enum columns are rendered by the column helper, not the type mapper**
 *   (`NVARCHAR(<longest value>)` + CHECK). [renderedColumnType] applies the
 *   shared [MssqlTypeMapper.enumWidth] rule so the projection matches the
 *   column the generator actually writes.
 *
 * **Ein `refType`-Enum wird aufgeloest, sobald das Schema mitkommt.** PG und
 * MySQL lassen ihn stehen, weil sie einen echten Custom Type emittieren, den
 * ihr Reverse rekonstruiert — dort ist Identitaet die genaue Projektion. T-SQL
 * hat diesen Weg nicht: [MssqlColumnConstraintHelper] degradiert ein
 * `refType`-Enum zu `NVARCHAR(width)` + CHECK und eine `refType`-Domain zu
 * ihrem Basistyp, der Reverse kann den `refType` also nie zurueckgeben.
 * Identitaet meldete deshalb Drift auf einem verlustfreien Round-Trip.
 *
 * Die Breite steht in den Werten des Custom Types und der Domain-Weg in seinem
 * Basistyp — beides sieht eine `(NeutralType) -> NeutralType`-Projektion nicht.
 * Die Ueberladung mit Schema-Kontext sieht es (`canonicalize(type,
 * customTypes)`); ohne Kontext bleibt es beim konservativen Default, der nie
 * einen Typ wegfaltet.
 */
internal object MssqlNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = MssqlTypeMapper()

    override fun canonicalize(type: NeutralType): NeutralType = canonicalize(type, emptyMap())

    /**
     * Mit den Custom Types des Schemas laesst sich ein `refType` aufloesen —
     * ohne sie bleibt er stehen (der konservative Default des Ports).
     *
     * Aufgeloest wird in die Form, die der Spalten-Helfer schreibt: ein Enum in
     * `NVARCHAR(<laengster Wert>)`, eine Domain in ihren Basistyp. Danach
     * greift dieselbe Round-Trip-Projektion wie fuer jeden anderen Typ.
     */
    override fun canonicalize(
        type: NeutralType,
        customTypes: Map<String, CustomTypeDefinition>,
    ): NeutralType = when {
        type is NeutralType.Enum && type.refType != null ->
            canonicalize(resolveRefType(type, customTypes), customTypes)
        else -> {
            val mapped = MssqlTypeMapping.mapColumn(
                columnName = "",
                input = toColumnInput(renderedColumnType(type)),
            )
            if (mapped.note?.code == "R301") type else mapped.type
        }
    }

    /**
     * Der Typ, in den der Spalten-Helfer einen `refType` **tatsaechlich**
     * aufloest.
     *
     * Nie `null`: `MssqlColumnConstraintHelper.enumColumn` kennt kein
     * "unbekannt bleiben". Findet sie keine Werteliste — der Custom Type fehlt
     * im Schema, oder er ist ein `COMPOSITE` ohne `values` —, faellt sie auf
     * `plainColumn` zurueck, also ungebundenes `NVARCHAR(MAX)`. Ein wertloser
     * `Enum` projiziert ueber [renderedColumnType] genau darauf.
     *
     * Lieferte diese Stelle stattdessen `null`, bliebe `Enum(refType = …)`
     * stehen, waehrend der Reverse `Text(null)` liest: eine Drift im
     * Post-Compare fuer eine Migration, die genau das Verlangte getan hat.
     */
    private fun resolveRefType(
        type: NeutralType.Enum,
        customTypes: Map<String, CustomTypeDefinition>,
    ): NeutralType {
        val custom = type.refType?.let { customTypes[it] }
        if (custom?.kind == CustomTypeKind.DOMAIN) {
            // Wie `domainColumn`: ohne Basistyp gilt `text`, und was der
            // Resolver nicht kennt, wird NVARCHAR(MAX) (E053).
            val base = custom.baseType ?: "text"
            return MssqlColumnTypeResolver(typeMapper)
                .resolveDomainBaseType(base, custom.precision, custom.scale)
                ?: NeutralType.Text()
        }
        return NeutralType.Enum(values = custom?.values ?: type.values)
    }

    /** Die Spalte, die der Generator schreibt — fuer Enums der Spalten-Helfer, sonst der Typmapper. */
    private fun renderedColumnType(type: NeutralType): String {
        val enumValues = (type as? NeutralType.Enum)?.values
        return if (enumValues != null) {
            typeMapper.unicodeText(MssqlTypeMapper.enumWidth(enumValues))
        } else {
            typeMapper.toSql(type)
        }
    }

    private fun toColumnInput(rendered: String): MssqlTypeMapping.ColumnInput {
        // Die IDENTITY-Klausel zuerst abtrennen: `(1,1)` wuerde sonst als
        // Praezision/Skala des Basistyps gelesen.
        val isIdentity = IDENTITY_CLAUSE.containsMatchIn(rendered)
        val declaration = IDENTITY_CLAUSE.replace(rendered, "").trim().uppercase()
        val typeName = declaration.substringBefore('(').trim()
        val (precision, scale) = SchemaReaderUtils.parenPrecisionScale(declaration)
        return MssqlTypeMapping.ColumnInput(
            typeName = typeName,
            maxLength = catalogMaxLength(typeName, declaration),
            precision = precision,
            scale = scale,
            isIdentity = isIdentity,
        )
    }

    /**
     * `sys.columns.max_length` fuer die gerenderte Deklaration: in BYTES,
     * `-1` fuer `(MAX)`. Unicode-Typen zaehlen zwei Bytes je Zeichen — genau
     * die Umrechnung, die [MssqlTypeMapping] wieder rueckgaengig macht.
     */
    private fun catalogMaxLength(typeName: String, declaration: String): Int? = when {
        !declaration.contains('(') -> null
        MAX_LENGTH_TOKEN.containsMatchIn(declaration) -> CATALOG_MAX_SENTINEL
        else -> SchemaReaderUtils.parenLength(declaration)
            ?.let { if (typeName in UNICODE_TYPES) it * BYTES_PER_UNICODE_CHAR else it }
    }

    private val IDENTITY_CLAUSE = Regex("""(?i)\s*IDENTITY\s*\(\s*\d+\s*,\s*\d+\s*\)""")
    private val MAX_LENGTH_TOKEN = Regex("""\(\s*MAX\s*\)""")
    private val UNICODE_TYPES = setOf("NVARCHAR", "NCHAR")
    private const val BYTES_PER_UNICODE_CHAR = 2
    private const val CATALOG_MAX_SENTINEL = -1
}
