package dev.dmigrate.core.model

import dev.dmigrate.core.diff.SchemaComparator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Neutralform des Domain-Basistyps mit Laenge und Array (Issue #58, ADR 0065). */
class CustomTypeBaseTypeTest : FunSpec({

    fun same(a: String, b: String): Boolean {
        fun schema(base: String) = SchemaDefinition(
            name = "S", version = "1",
            customTypes = mapOf("d" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base)),
        )
        return SchemaComparator().compare(schema(a), schema(b)).customTypesChanged.isEmpty()
    }

    test("Typen mit Laenge tragen sie in der Neutralform, ohne Klammer gilt der Standard") {
        val expected = mapOf(
            "varchar(50)" to "varchar(50)", "character varying(50)" to "varchar(50)", "VARCHAR(50)" to "varchar(50)",
            "varchar" to "text", "text" to "text",
            "char(5)" to "char(5)", "character(5)" to "char(5)", "bpchar(5)" to "char(5)",
            "char" to "char(1)", "character" to "char(1)", "char(1)" to "char(1)",
            "timestamp" to "timestamp(6)", "timestamp(3)" to "timestamp(3)",
            "timestamp(3) without time zone" to "timestamp(3)", "timestamp with time zone" to "timestamptz(6)",
            "timestamp(3) with time zone" to "timestamptz(3)", "time(3)" to "time(3)",
            "timetz" to "timetz(6)", "time with time zone" to "timetz(6)", "time(3) with time zone" to "timetz(3)",
            "bit(5)" to "bit(5)", "bit varying(7)" to "varbit(7)", "bit varying" to "varbit",
        )
        for ((written, canonical) in expected) {
            withClue(written) { CustomTypeBaseType.canonical(written) shouldBe canonical }
        }
    }

    test("Array: ein [] am Typnamen, die Dimension zaehlt nicht") {
        CustomTypeBaseType.canonical("integer[]") shouldBe "integer[]"
        CustomTypeBaseType.canonical("int4[][]") shouldBe "integer[]"
        CustomTypeBaseType.canonical("INTEGER [3]") shouldBe "integer[]"
        CustomTypeBaseType.canonical("character varying(50)[]") shouldBe "varchar(50)[]"
        CustomTypeBaseType.canonical("numeric(10,2)[]") shouldBe "decimal[]"
    }

    test("Typen ohne Laenge behalten die Neutralform von zuvor") {
        CustomTypeBaseType.canonical("bigint") shouldBe "biginteger"
        CustomTypeBaseType.canonical("numeric(10,2)") shouldBe "decimal"
        CustomTypeBaseType.canonical("jsonb") shouldBe "json"
        CustomTypeBaseType.canonical(null) shouldBe null
    }

    test("gleiche Domain: Schreibweisen von Datei und Reader") {
        same("varchar(50)", "character varying(50)") shouldBe true
        same("varchar", "text") shouldBe true
        same("char", "char(1)") shouldBe true
        same("integer[]", "int4[]") shouldBe true
        same("integer[]", "integer[][]") shouldBe true
        same("varchar(50)[]", "character varying(50)[]") shouldBe true
        same("numeric(10,2)[]", "decimal(10,2)[]") shouldBe true
        same("timestamp", "timestamp(6)") shouldBe true
    }

    test("echte Aenderung: Laenge, Array, char gegen varchar, text gegen varchar(n)") {
        same("varchar(50)", "varchar(254)") shouldBe false
        same("varchar(50)", "text") shouldBe false
        same("varchar(50)", "varchar") shouldBe false
        same("char(5)", "varchar(5)") shouldBe false
        same("char(5)", "char(6)") shouldBe false
        same("integer", "integer[]") shouldBe false
        same("integer[]", "bigint[]") shouldBe false
        same("varchar(50)[]", "varchar(60)[]") shouldBe false
        same("numeric(10,2)[]", "numeric(12,2)[]") shouldBe false
        same("timestamp(3)", "timestamp") shouldBe false
        same("bit(5)", "bit(6)") shouldBe false
        same("timetz(3)", "timetz(6)") shouldBe false
        same("timetz", "timetz(6)") shouldBe true
    }

    test("Typen ohne Laenge: eine einseitige Klammer bleibt unbekannt, nicht verschieden") {
        same("integer(11)", "integer") shouldBe true
        same("integer(11)", "integer(10)") shouldBe false
    }

    test("precision und scale gelten nur bei decimal, nicht bei einem Array") {
        fun def(base: String) = CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = base, precision = 5)
        CustomTypeBaseType.precision(def("numeric(10,2)")) shouldBe 5
        CustomTypeBaseType.precision(def("numeric(10,2)[]")) shouldBe null
        CustomTypeBaseType.precision(def("varchar(50)")) shouldBe null
        CustomTypeBaseType.parameters("numeric(10,2)[]") shouldBe listOf(10, 2)
    }
})

private inline fun withClue(clue: String, block: () -> Unit) = io.kotest.assertions.withClue(clue, block)
