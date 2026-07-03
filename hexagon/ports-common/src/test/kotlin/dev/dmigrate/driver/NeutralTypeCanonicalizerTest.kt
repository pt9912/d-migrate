package dev.dmigrate.driver

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NeutralTypeCanonicalizerTest : FunSpec({

    test("IDENTITY returns every type unchanged") {
        val samples = listOf(
            NeutralType.Identifier(autoIncrement = true),
            NeutralType.Text(maxLength = 50),
            NeutralType.Char(length = 10),
            NeutralType.SmallInt,
            NeutralType.Float(FloatPrecision.SINGLE),
            NeutralType.Decimal(10, 2),
            NeutralType.DateTime(timezone = true),
            NeutralType.Enum(values = listOf("red", "green")),
            NeutralType.Geometry(GeometryType.of("point"), srid = 4326),
            NeutralType.FullText,
        )
        for (type in samples) {
            NeutralTypeCanonicalizer.IDENTITY.canonicalize(type) shouldBe type
        }
    }
})
