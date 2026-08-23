package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Ein `Enum(refType)` traegt seine Werte nicht selbst — sie stehen im Schema.
 * T-SQL hat keinen Enum-Typ, der Reverse kann den `refType` also nie
 * zurueckgeben; ohne Aufloesung meldete der Post-Compare Drift auf einem
 * verlustfreien Round-Trip.
 */
class MssqlNeutralTypeCanonicalizerRefTypeTest : FunSpec({

    val canonicalizer = MssqlNeutralTypeCanonicalizer

    test("without schema context a refType stays as it is — the conservative default") {
        val type = NeutralType.Enum(refType = "mood")
        canonicalizer.canonicalize(type) shouldBe type
    }

    test("with the custom type at hand it folds to what the column helper writes") {
        // `NVARCHAR(<laengster Wert>)` — genau das, was der Reverse zurueckgibt.
        val types = mapOf(
            "mood" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("red", "green")),
        )
        canonicalizer.canonicalize(NeutralType.Enum(refType = "mood"), types) shouldBe
            canonicalizer.canonicalize(NeutralType.Enum(values = listOf("red", "green")))
    }

    test("a domain folds to its base type") {
        val types = mapOf(
            "postal" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "varchar", precision = 20),
        )
        canonicalizer.canonicalize(NeutralType.Enum(refType = "postal"), types) shouldBe
            canonicalizer.canonicalize(NeutralType.Text(20))
    }

    test("an unknown refType is not guessed at") {
        val type = NeutralType.Enum(refType = "nowhere")
        canonicalizer.canonicalize(type, mapOf("other" to CustomTypeDefinition(kind = CustomTypeKind.ENUM))) shouldBe
            type
    }

    test("a composite type has no column form and stays untouched") {
        val type = NeutralType.Enum(refType = "addr")
        canonicalizer.canonicalize(type, mapOf("addr" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE))) shouldBe
            type
    }
})
