package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DomainTypesTest : FunSpec({

    test("neutrale Namen und PostgreSQL-Katalognamen loesen sich auf") {
        val expected = mapOf(
            "integer" to NeutralType.Integer,
            "int4" to NeutralType.Integer,
            "bigint" to NeutralType.BigInteger,
            "int8" to NeutralType.BigInteger,
            "smallint" to NeutralType.SmallInt,
            "boolean" to NeutralType.BooleanType,
            "real" to NeutralType.Float(FloatPrecision.SINGLE),
            "double precision" to NeutralType.Float(),
            "uuid" to NeutralType.Uuid,
            "jsonb" to NeutralType.Json,
            "xml" to NeutralType.Xml,
            "bytea" to NeutralType.Binary,
            "date" to NeutralType.Date,
            "time without time zone" to NeutralType.Time,
            "timestamp" to NeutralType.DateTime(),
            "timestamptz" to NeutralType.DateTime(timezone = true),
            "text" to NeutralType.Text(),
            "char" to NeutralType.Char(1),
        )
        for ((name, type) in expected) {
            DomainTypes.resolveBaseType(name, null, null) shouldBe type
        }
    }

    test("Praezision und Skala kommen aus den Feldern oder aus Klammerwerten") {
        DomainTypes.resolveBaseType("decimal", 10, 2) shouldBe NeutralType.Decimal(10, 2)
        DomainTypes.resolveBaseType("DECIMAL(12,3)", null, null) shouldBe NeutralType.Decimal(12, 3)
        DomainTypes.resolveBaseType("numeric(8)", null, null) shouldBe NeutralType.Decimal(8, 0)
        DomainTypes.resolveBaseType("VARCHAR(20)", null, null) shouldBe NeutralType.Text(20)
        DomainTypes.resolveBaseType("char", 4, null) shouldBe NeutralType.Char(4)
        // Explizite Felder gehen den Klammerwerten vor.
        DomainTypes.resolveBaseType("decimal(5,1)", 9, 4) shouldBe NeutralType.Decimal(9, 4)
    }

    test("jeder Basistyp, den der PostgreSQL-Reader mit Laenge schreibt, ergibt einen neutralen Typ (#58)") {
        DomainTypes.resolveBaseType("varchar(50)", null, null) shouldBe NeutralType.Text(50)
        DomainTypes.resolveBaseType("varchar", null, null) shouldBe NeutralType.Text()
        DomainTypes.resolveBaseType("char(5)", null, null) shouldBe NeutralType.Char(5)
        DomainTypes.resolveBaseType("timestamp(3)", null, null) shouldBe NeutralType.DateTime()
        DomainTypes.resolveBaseType("timestamptz(3)", null, null) shouldBe NeutralType.DateTime(timezone = true)
        DomainTypes.resolveBaseType("time(3)", null, null) shouldBe NeutralType.Time
    }

    test("Array, timetz, bit und varbit haben kein neutrales Gegenstueck, wie zuvor (#58)") {
        for (array in listOf("timetz", "timetz(3)", "bit(5)", "varbit(7)", "integer[]", "varchar(50)[]", "decimal(10,2)[]", "_int4")) {
            DomainTypes.resolveBaseType(array, null, null) shouldBe null
        }
    }

    test("ein unbekannter Basistyp und ein decimal ohne Praezision haben kein Gegenstueck") {
        DomainTypes.resolveBaseType("money", null, null) shouldBe null
        DomainTypes.resolveBaseType("decimal", null, null) shouldBe null
    }

    test("VALUE wird ausserhalb von String-Literalen ersetzt") {
        DomainTypes.substituteValueToken("VALUE >= 0", "\"n\"") shouldBe "\"n\" >= 0"
        DomainTypes.substituteValueToken("value <> 'VALUE'", "\"c\"") shouldBe "\"c\" <> 'VALUE'"
        DomainTypes.substituteValueToken("VALUE <> 'it''s VALUE' AND VALUE > 1", "x") shouldBe
            "x <> 'it''s VALUE' AND x > 1"
        // Ein nicht geschlossenes Literal wird bis zum Ende uebernommen.
        DomainTypes.substituteValueToken("VALUE = 'open", "x") shouldBe "x = 'open"
        DomainTypes.substituteValueToken("REVALUE > 0", "x") shouldBe "REVALUE > 0"
    }
})
