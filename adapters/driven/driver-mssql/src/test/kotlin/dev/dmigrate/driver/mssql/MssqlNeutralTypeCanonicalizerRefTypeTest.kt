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
 *
 * Die Projektion bildet ab, was `MssqlColumnConstraintHelper.enumColumn`
 * **wirklich** tut — und die kennt kein „unbekannt bleiben": findet sie keine
 * Werteliste, schreibt sie ein ungebundenes `NVARCHAR(MAX)`.
 */
class MssqlNeutralTypeCanonicalizerRefTypeTest : FunSpec({

    val canonicalizer = MssqlNeutralTypeCanonicalizer

    test("without schema context a refType folds to the unbounded column the helper writes") {
        // Ohne Schema gibt es keine Werte, also auch keine Breite. Den
        // `refType` stehen zu lassen hiesse, eine Drift gegen `Text(null)` zu
        // behaupten, die der Server nicht hat.
        canonicalizer.canonicalize(NeutralType.Enum(refType = "mood")) shouldBe
            canonicalizer.canonicalize(NeutralType.Enum())
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

    test("a domain without a base type folds like the helper's `text` default") {
        val types = mapOf("bare" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN))
        canonicalizer.canonicalize(NeutralType.Enum(refType = "bare"), types) shouldBe
            canonicalizer.canonicalize(NeutralType.Text())
    }

    test("an unknown refType folds to the unbounded column, not to itself") {
        val types = mapOf("other" to CustomTypeDefinition(kind = CustomTypeKind.ENUM))
        canonicalizer.canonicalize(NeutralType.Enum(refType = "nowhere"), types) shouldBe
            canonicalizer.canonicalize(NeutralType.Enum())
    }

    test("a composite type has no column form — the helper writes NVARCHAR(MAX), so does the projection") {
        val types = mapOf("addr" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE))
        canonicalizer.canonicalize(NeutralType.Enum(refType = "addr"), types) shouldBe
            canonicalizer.canonicalize(NeutralType.Enum())
    }

    test("values on the type itself win when the schema knows nothing") {
        // `enumColumn` prueft `type.values` als letzten Schritt vor dem
        // Rueckfall; die Projektion muss denselben Schritt kennen.
        val bounded = NeutralType.Enum(refType = "nowhere", values = listOf("a", "bb"))
        canonicalizer.canonicalize(bounded, emptyMap()) shouldBe
            canonicalizer.canonicalize(NeutralType.Enum(values = listOf("a", "bb")))
    }
})
