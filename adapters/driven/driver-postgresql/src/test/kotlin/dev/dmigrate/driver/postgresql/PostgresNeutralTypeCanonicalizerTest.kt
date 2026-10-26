package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the PostgreSQL canonicalisation (live composition `reverse(toSql(t))`
 * with a mechanical DDL→information_schema spelling bridge) against the AP0
 * edge table of the post-compare canonicalisation slice: 2 belegte Kanten
 * (`email`→`text(254)`, inline-`enum`→`text`); everything else round-trips
 * as a fixpoint.
 */
class PostgresNeutralTypeCanonicalizerTest : FunSpec({

    val canon = PostgresNeutralTypeCanonicalizer

    val fixpoints = listOf<NeutralType>(
        NeutralType.Text(),
        NeutralType.Text(maxLength = 50),
        NeutralType.Char(length = 10),
        NeutralType.Integer,
        NeutralType.SmallInt,
        NeutralType.BigInteger,
        NeutralType.Float(FloatPrecision.SINGLE),
        NeutralType.Float(),
        NeutralType.Decimal(10, 2),
        NeutralType.BooleanType,
        NeutralType.DateTime(),
        NeutralType.DateTime(timezone = true),
        NeutralType.Date,
        NeutralType.Time,
        NeutralType.Uuid,
        NeutralType.Json,
        NeutralType.Xml,
        NeutralType.Binary,
        // Review-Härtung R1: FullText round-trippt auf PG treu als tsvector —
        // Fixpunkt der Komposition, kein Carve-out nötig.
        NeutralType.FullText,
        // Arrays round-trippen treu — fuer **jede** Elementart, die der
        // Reverse benennt. Vorher rendete der Generator nur vier davon, und
        // `array(biginteger)` projizierte still auf `array(text)`.
        NeutralType.Array(elementType = "text"),
        NeutralType.Array(elementType = "integer"),
        NeutralType.Array(elementType = "biginteger"),
        NeutralType.Array(elementType = "boolean"),
        NeutralType.Array(elementType = "uuid"),
        NeutralType.Array(elementType = "float"),
        NeutralType.Array(elementType = "decimal"),
        NeutralType.Array(elementType = "json"),
    )

    val edges = mapOf<NeutralType, NeutralType>(
        NeutralType.Email to NeutralType.Text(maxLength = 254),
        NeutralType.Enum(values = listOf("red", "green")) to NeutralType.Text(),
        // Review-Härtung R1: identifier OHNE auto_increment rendert plain
        // INTEGER (kein SERIAL) — die Komposition muss zu Integer falten.
        NeutralType.Identifier() to NeutralType.Integer,
        // Eine Elementart, die der Reverse **nicht** benennt (`date[]` liest
        // `text`, N1), flacht real auf `TEXT[]` ab — dort ist `text` die
        // gelesene Wahrheit, keine Projektionsluecke.
        NeutralType.Array(elementType = "date") to NeutralType.Array(elementType = "text"),
    )

    test("faithful round-trip types are fixpoints") {
        for (type in fixpoints) {
            canon.canonicalize(type) shouldBe type
        }
    }

    test("canonical projection matches the AP0 edge table") {
        for ((type, canonical) in edges) {
            canon.canonicalize(type) shouldBe canonical
        }
    }

    test("identity carve-outs: geometry, auto-increment identifier, refType enum") {
        val geometry = NeutralType.Geometry(GeometryType.of("point"), srid = 4326)
        canon.canonicalize(geometry) shouldBe geometry
        val identifier = NeutralType.Identifier(autoIncrement = true)
        canon.canonicalize(identifier) shouldBe identifier
        val refEnum = NeutralType.Enum(refType = "mood")
        canon.canonicalize(refEnum) shouldBe refEnum
    }

    test("projection is idempotent") {
        for (type in fixpoints + edges.keys + NeutralType.Geometry()) {
            val once = canon.canonicalize(type)
            canon.canonicalize(once) shouldBe once
        }
    }

    // Eine Domain-Spalte faltet im Abdruck auf ihren Basistyp -- auf dieselbe Form, die der Reader vor dem
    // `ref_type` an der Datenbank-Spalte schrieb (`PostgresTypeMapping.mapColumn` aus den Spaltenangaben).
    fun domain(base: String, precision: Int? = null, scale: Int? = null) = mapOf(
        "dd" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale),
    )

    fun readerForm(dataType: String, udt: String, length: Int? = null, precision: Int? = null, scale: Int? = null) =
        PostgresTypeMapping.mapColumn(
            PostgresTypeMapping.ColumnInput(
                dataType = dataType, udtName = udt, isPkCol = false, isIdentity = false, identityGeneration = null,
                colDefault = null, generatedSequenceName = null, charMaxLen = length, numPrecision = precision,
                numScale = scale, tableName = "t", colName = "d",
            ),
        ).type

    val ref = NeutralType.Enum(refType = "dd")

    test("domain column folds onto the base type the reader formerly wrote for the database column") {
        val cases = listOf(
            Triple(domain("integer"), readerForm("integer", "int4"), "integer"),
            Triple(domain("biginteger"), readerForm("bigint", "int8"), "biginteger"),
            Triple(domain("text"), readerForm("text", "text"), "text"),
            Triple(domain("varchar(50)"), readerForm("character varying", "varchar", length = 50), "varchar(50)"),
            Triple(domain("decimal", 10, 2), readerForm("numeric", "numeric", precision = 10, scale = 2), "decimal"),
            Triple(domain("uuid"), readerForm("uuid", "uuid"), "uuid"),
            Triple(domain("boolean"), readerForm("boolean", "bool"), "boolean"),
            Triple(domain("integer[]"), readerForm("ARRAY", "_int4"), "integer[]"),
        )
        for ((customTypes, formerDatabaseForm, label) in cases) {
            withClue(label) {
                canon.canonicalize(ref, customTypes) shouldBe canon.canonicalize(formerDatabaseForm)
            }
        }
    }

    test("two spellings of the same domain base type fold onto the same type (file and reader write them differently)") {
        val spellings = listOf(
            "real" to "float4", "real" to "float", "bigint" to "int8", "int4[]" to "integer[]",
            "integer[][]" to "integer[]", "timestamp(3) with time zone" to "timestamptz(3)",
            "character varying(50)" to "varchar(50)", "varchar" to "text", "char" to "char(1)",
            "timestamp" to "timestamp(6)",
        )
        for ((written, read) in spellings) {
            withClue("$written = $read") {
                canon.canonicalize(ref, domain(written)) shouldBe canon.canonicalize(ref, domain(read))
            }
        }
        canon.canonicalize(ref, domain("numeric(10,2)")) shouldBe canon.canonicalize(ref, domain("decimal", 10, 2))
    }

    test("domain column without custom types, enum column and unresolvable base stay as they are") {
        canon.canonicalize(ref, emptyMap()) shouldBe ref
        canon.canonicalize(ref) shouldBe ref
        val enum = mapOf("dd" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a")))
        canon.canonicalize(ref, enum) shouldBe ref
        // Domain ueber einer Domain und `varchar(n)[]`: kein neutrales Gegenstueck, Rueckfall.
        canon.canonicalize(ref, domain("d1")) shouldBe ref
        canon.canonicalize(ref, domain("varchar(50)[]")) shouldBe ref
    }
})
