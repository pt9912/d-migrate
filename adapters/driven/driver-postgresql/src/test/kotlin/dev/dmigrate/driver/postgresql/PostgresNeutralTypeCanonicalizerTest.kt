package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
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
        // Arrays der vier generate-baren Element-Typen round-trippen treu.
        NeutralType.Array(elementType = "text"),
        NeutralType.Array(elementType = "integer"),
        NeutralType.Array(elementType = "boolean"),
        NeutralType.Array(elementType = "uuid"),
    )

    val edges = mapOf<NeutralType, NeutralType>(
        NeutralType.Email to NeutralType.Text(maxLength = 254),
        NeutralType.Enum(values = listOf("red", "green")) to NeutralType.Text(),
        // Review-Härtung R1: identifier OHNE auto_increment rendert plain
        // INTEGER (kein SERIAL) — die Komposition muss zu Integer falten.
        NeutralType.Identifier() to NeutralType.Integer,
        // Nicht generate-bare Array-Element-Typen flachen real auf TEXT[] ab.
        NeutralType.Array(elementType = "biginteger") to NeutralType.Array(elementType = "text"),
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
})
