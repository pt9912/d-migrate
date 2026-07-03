package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the MySQL canonicalisation (live composition `reverse(toSql(t))`)
 * against the AP0 edge table of the post-compare canonicalisation slice:
 * 5 belegte Kanten (`datetime(tz)`, `xml`, `email`, inline-`enum`,
 * `array`→`json`) plus the emergent `char(36)`→`uuid` edge; everything else
 * round-trips as a fixpoint.
 */
class MysqlNeutralTypeCanonicalizerTest : FunSpec({

    val canon = MysqlNeutralTypeCanonicalizer

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
        NeutralType.Date,
        NeutralType.Time,
        NeutralType.Uuid,
        NeutralType.Json,
        NeutralType.Binary,
        NeutralType.Identifier(autoIncrement = true),
    )

    val edges = mapOf<NeutralType, NeutralType>(
        NeutralType.DateTime(timezone = true) to NeutralType.DateTime(),
        NeutralType.Xml to NeutralType.Text(),
        NeutralType.Email to NeutralType.Text(maxLength = 254),
        NeutralType.Enum(values = listOf("red", "green")) to NeutralType.Text(),
        NeutralType.Array(elementType = "text") to NeutralType.Json,
        // Emergente Kante der Komposition: MySQL-Reverse hebt CHAR(36) zu Uuid (R310).
        NeutralType.Char(length = 36) to NeutralType.Uuid,
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

    test("identity carve-outs: geometry, fulltext, refType enum") {
        val geometry = NeutralType.Geometry(GeometryType.of("point"), srid = 4326)
        canon.canonicalize(geometry) shouldBe geometry
        canon.canonicalize(NeutralType.FullText) shouldBe NeutralType.FullText
        val refEnum = NeutralType.Enum(refType = "mood")
        canon.canonicalize(refEnum) shouldBe refEnum
    }

    test("projection is idempotent") {
        for (type in fixpoints + edges.keys + NeutralType.Geometry() + NeutralType.FullText) {
            val once = canon.canonicalize(type)
            canon.canonicalize(once) shouldBe once
        }
    }
})
