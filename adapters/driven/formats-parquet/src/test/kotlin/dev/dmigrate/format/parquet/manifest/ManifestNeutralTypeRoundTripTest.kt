package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [ChunkSchemaToManifest] (schreibt) und [ManifestNeutralTypeToCore]
 * (liest) sind ein Uebersetzungspaar fuer AP7 §5.2 — jede `NeutralType`-
 * Variante steht in beiden als eigener `when`-Zweig, und die beiden
 * Aufzaehlungen liefen bislang auseinander, ohne dass es auffiel: kein
 * Round-Trip-Test deckte je alle Varianten ab, nur die vier bis sechs, die
 * ein Bundle-Test zufaellig benutzte.
 *
 * Dieser Test iteriert ueber **jede** der 21 `NeutralType`-Varianten und
 * fand dabei live einen echten Fund: [NeutralType.FullText] wurde
 * geschrieben, aber beim Lesen nirgends erkannt — ein Bundle-Export mit
 * einer FullText-Spalte scheiterte beim naechsten Lesen des eigenen
 * Manifests mit `MANIFEST_FIELD_INVALID`. Behoben in
 * [ManifestNeutralTypeToCore]; dieser Test ist der Regressionsschutz.
 */
class ManifestNeutralTypeRoundTripTest : FunSpec({

    fun roundTrip(type: NeutralType): NeutralType {
        val schema = ChunkSchema(
            table = "t",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("v", nullable = true, neutralType = type)),
        )
        val manifestColumn = ChunkSchemaToManifest.toManifestColumns(schema).single()
        return ManifestNeutralTypeToCore.convert(manifestColumn.neutralType!!)
    }

    val allVariants = listOf(
        NeutralType.Identifier(autoIncrement = true),
        NeutralType.Identifier(autoIncrement = false),
        NeutralType.Text(maxLength = 200),
        NeutralType.Text(maxLength = null),
        NeutralType.Char(length = 3),
        NeutralType.Integer,
        NeutralType.SmallInt,
        NeutralType.BigInteger,
        NeutralType.Float(FloatPrecision.SINGLE),
        NeutralType.Float(FloatPrecision.DOUBLE),
        NeutralType.Decimal(precision = 12, scale = 2),
        NeutralType.BooleanType,
        NeutralType.DateTime(timezone = true),
        NeutralType.DateTime(timezone = false),
        NeutralType.Date,
        NeutralType.Time,
        NeutralType.Uuid,
        NeutralType.Json,
        NeutralType.Xml,
        NeutralType.Binary,
        NeutralType.Email,
        NeutralType.Enum(values = listOf("A", "B"), refType = "status_enum"),
        NeutralType.Enum(values = null, refType = null),
        NeutralType.Array(elementType = "text"),
        NeutralType.Geometry(geometryType = GeometryType.of("point"), srid = 4326),
        NeutralType.Geometry(geometryType = GeometryType.GEOMETRY, srid = null),
        NeutralType.FullText,
    )

    test("jede NeutralType-Variante uebersteht den Manifest-Rundlauf unveraendert") {
        for (type in allVariants) {
            roundTrip(type) shouldBe type
        }
    }

    test("ein unbekannter kind-String wirft ParquetManifestParseException statt still zu degradieren") {
        shouldThrow<ParquetManifestParseException> {
            ManifestNeutralTypeToCore.convert(ManifestNeutralType("NoSuchKind"))
        }
    }

    test("Decimal ohne precision-Attribut wirft MANIFEST_FIELD_MISSING") {
        val ex = shouldThrow<ParquetManifestParseException> {
            ManifestNeutralTypeToCore.convert(ManifestNeutralType("Decimal", mapOf("scale" to 2)))
        }
        ex.message!! shouldBe "MANIFEST_FIELD_MISSING: Decimal.precision"
    }

    test("Decimal ohne scale-Attribut wirft MANIFEST_FIELD_MISSING") {
        val ex = shouldThrow<ParquetManifestParseException> {
            ManifestNeutralTypeToCore.convert(ManifestNeutralType("Decimal", mapOf("precision" to 10)))
        }
        ex.message!! shouldBe "MANIFEST_FIELD_MISSING: Decimal.scale"
    }

    test("Char ohne length-Attribut wirft MANIFEST_FIELD_MISSING") {
        val ex = shouldThrow<ParquetManifestParseException> {
            ManifestNeutralTypeToCore.convert(ManifestNeutralType("Char"))
        }
        ex.message!! shouldBe "MANIFEST_FIELD_MISSING: Char.length"
    }

    test("Array ohne erkennbares element.kind faellt auf 'unknown' zurueck") {
        ManifestNeutralTypeToCore.convert(ManifestNeutralType("Array")) shouldBe
            NeutralType.Array(elementType = "unknown")
    }

    test("Geometry ohne geometryType-Attribut faellt auf den Default zurueck") {
        ManifestNeutralTypeToCore.convert(ManifestNeutralType("Geometry")) shouldBe
            NeutralType.Geometry(geometryType = GeometryType.of(null))
    }
})
