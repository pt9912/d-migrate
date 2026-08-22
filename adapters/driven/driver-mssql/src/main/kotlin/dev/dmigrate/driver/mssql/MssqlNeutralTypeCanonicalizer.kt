package dev.dmigrate.driver.mssql

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
 * An enum carrying a `refType` stays identity — but NOT for the reason the PG
 * and MySQL canonicalisers give. Those two carve it out because they emit a
 * real custom type (`CREATE TYPE … AS ENUM`) that their reverse reconstructs,
 * so identity is the accurate projection. T-SQL has no such path:
 * [MssqlColumnConstraintHelper] degrades a `refType` enum to
 * `NVARCHAR(width)` + CHECK and a `refType` domain to the domain's base type,
 * so the reverse can never return the `refType` and identity WILL report drift
 * on a lossless round trip.
 *
 * Folding it anyway is not the fix: the width comes from the custom type's
 * values and the domain path from its base type, and a `(NeutralType) ->
 * NeutralType` projection sees neither. Any fold chosen here would be wrong in
 * a different way. Identity is therefore the port's prescribed conservative
 * default — it never folds a type away, so the failure direction stays a loud
 * post-compare drift instead of a masked one. Closing it needs schema context
 * in the projection; tracked as a Slice-5 obligation in
 * `docs/planning/in-progress/mssql-dialect-scoping.md`.
 */
internal object MssqlNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = MssqlTypeMapper()

    override fun canonicalize(type: NeutralType): NeutralType = when {
        type is NeutralType.Enum && type.refType != null -> type
        else -> {
            val mapped = MssqlTypeMapping.mapColumn(
                columnName = "",
                input = toColumnInput(renderedColumnType(type)),
            )
            if (mapped.note?.code == "R301") type else mapped.type
        }
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
