package dev.dmigrate.core.model

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Der neutrale Basistyp einer Domain und die Aufloesung einer Domain-Spalte (Issue #68). */
class DomainBaseTypesTest : FunSpec({

    fun domain(base: String?, precision: Int? = null, scale: Int? = null) =
        CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base, precision = precision, scale = scale)

    test("Basistyp je Schreibweise: Datei und Reader fuehren dieselbe Neutralform") {
        val cases = mapOf(
            domain("integer") to NeutralType.Integer,
            domain("int4") to NeutralType.Integer,
            domain("bigint") to NeutralType.BigInteger,
            domain(null) to NeutralType.Text(),
            domain("varchar") to NeutralType.Text(),
            domain("varchar(50)") to NeutralType.Text(50),
            domain("character varying(50)") to NeutralType.Text(50),
            domain("char(5)") to NeutralType.Char(5),
            domain("numeric(10,2)") to NeutralType.Decimal(10, 2),
            domain("decimal", 10, 2) to NeutralType.Decimal(10, 2),
            domain("uuid") to NeutralType.Uuid,
            domain("boolean") to NeutralType.BooleanType,
            domain("timestamptz(3)") to NeutralType.DateTime(timezone = true),
            // `real` und `float` sind in der Neutralform derselbe Typ: double.
            domain("real") to NeutralType.Float(),
            domain("float") to NeutralType.Float(),
        )
        for ((def, expected) in cases) {
            withClue("${def.baseType} ${def.precision}") { DomainBaseTypes.baseTypeOf(def) shouldBe expected }
        }
    }

    test("Array-Basistyp: int4[], INTEGER[] und integer[] sind dasselbe") {
        for (base in listOf("integer[]", "int4[]", "INTEGER[]", "int[]", "integer[][]")) {
            withClue(base) { DomainBaseTypes.baseTypeOf(domain(base)) shouldBe NeutralType.Array("integer") }
        }
        DomainBaseTypes.baseTypeOf(domain("text[]")) shouldBe NeutralType.Array("text")
    }

    test("kein neutrales Gegenstueck: null (varchar(n)[], numeric ohne precision, bit(n), inet, Domain)") {
        for (base in listOf("varchar(50)[]", "numeric", "decimal", "bit(5)", "inet", "interval", "money", "d1")) {
            withClue(base) { DomainBaseTypes.baseTypeOf(domain(base)) shouldBe null }
        }
    }

    test("effectiveType loest nur eine Domain auf, eine Stufe, sonst unveraendert") {
        val types = mapOf(
            "dd" to domain("integer"),
            "outer" to domain("dd"),
            "en" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a")),
        )
        DomainBaseTypes.effectiveType(NeutralType.Enum(refType = "dd"), types) shouldBe NeutralType.Integer
        DomainBaseTypes.effectiveType(NeutralType.Enum(refType = "outer"), types) shouldBe NeutralType.Enum(refType = "outer")
        DomainBaseTypes.effectiveType(NeutralType.Enum(refType = "en"), types) shouldBe NeutralType.Enum(refType = "en")
        DomainBaseTypes.effectiveType(NeutralType.Enum(refType = "missing"), types) shouldBe NeutralType.Enum(refType = "missing")
        DomainBaseTypes.effectiveType(NeutralType.Integer, types) shouldBe NeutralType.Integer
    }
})
