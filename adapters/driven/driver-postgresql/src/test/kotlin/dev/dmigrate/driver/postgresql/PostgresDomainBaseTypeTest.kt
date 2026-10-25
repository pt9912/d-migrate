package dev.dmigrate.driver.postgresql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** `format_type` -> Neutralform des Domain-Basistyps (Issue #58); die Eingaben sind gemessen (P0). */
class PostgresDomainBaseTypeTest : FunSpec({

    val cases = mapOf(
        "character varying(50)" to "varchar(50)",
        "character varying" to "text",
        "character(5)" to "char(5)",
        "character(1)" to "char(1)",
        "text" to "text",
        "integer" to "integer",
        "bigint" to "biginteger",
        "smallint" to "smallint",
        "real" to "float",
        "double precision" to "float",
        "boolean" to "boolean",
        "numeric" to "decimal",
        "integer[]" to "integer[]",
        "text[]" to "text[]",
        "numeric(10,2)[]" to "decimal(10,2)[]",
        "character varying(50)[]" to "varchar(50)[]",
        "timestamp(3) without time zone" to "timestamp(3)",
        "timestamp without time zone" to "timestamp",
        "timestamp(3) with time zone" to "timestamptz(3)",
        "timestamp with time zone" to "timestamptz",
        "time(3) without time zone" to "time(3)",
        "time with time zone" to "timetz",
        "bit(5)" to "bit(5)",
        "bit varying(7)" to "varbit(7)",
        "uuid" to "uuid",
    )
    for ((formatType, expected) in cases) {
        test("$formatType -> $expected") {
            PostgresDomainBaseType.map(formatType).baseType shouldBe expected
        }
    }

    test("numeric(p,s) ohne Array traegt Genauigkeit und Skala in den Feldern") {
        PostgresDomainBaseType.map("numeric(10,2)") shouldBe PostgresDomainBaseType.Mapped("decimal", 10, 2)
        PostgresDomainBaseType.map("numeric(10,0)") shouldBe PostgresDomainBaseType.Mapped("decimal", 10, 0)
    }

    test("ein Array traegt keine Genauigkeit in den Feldern") {
        PostgresDomainBaseType.map("numeric(10,2)[]") shouldBe PostgresDomainBaseType.Mapped("decimal(10,2)[]")
    }
})
