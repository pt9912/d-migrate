package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OracleTypeMapperTest : FunSpec({

    val mapper = OracleTypeMapper()

    test("dialect is ORACLE") {
        mapper.dialect shouldBe dev.dmigrate.driver.DatabaseDialect.ORACLE
    }

    test("integer family folds to NUMBER precisions symmetric to the reverse mapping") {
        mapper.toSql(NeutralType.SmallInt) shouldBe "NUMBER(4)"
        mapper.toSql(NeutralType.Integer) shouldBe "NUMBER(9)"
        mapper.toSql(NeutralType.BigInteger) shouldBe "NUMBER(18)"
        mapper.toSql(NeutralType.BooleanType) shouldBe "NUMBER(1)"
    }

    test("Identifier always folds to NUMBER(9) regardless of autoIncrement") {
        mapper.toSql(NeutralType.Identifier(autoIncrement = true)) shouldBe "NUMBER(9)"
        mapper.toSql(NeutralType.Identifier(autoIncrement = false)) shouldBe "NUMBER(9)"
    }

    test("Decimal maps directly, clamped to a maximum precision of 38") {
        mapper.toSql(NeutralType.Decimal(10, 2)) shouldBe "NUMBER(10,2)"
        mapper.toSql(NeutralType.Decimal(50, 10)) shouldBe "NUMBER(38,10)"
        mapper.isPrecisionClamped(NeutralType.Decimal(50, 10)) shouldBe true
        mapper.isPrecisionClamped(NeutralType.Decimal(38, 10)) shouldBe false
    }

    test("Text within 4000 chars maps to VARCHAR2; beyond widens to CLOB") {
        mapper.toSql(NeutralType.Text(100)) shouldBe "VARCHAR2(100)"
        mapper.toSql(NeutralType.Text(4000)) shouldBe "VARCHAR2(4000)"
        mapper.toSql(NeutralType.Text(4001)) shouldBe "CLOB"
        mapper.toSql(NeutralType.Text(null)) shouldBe "CLOB"
        mapper.isWidenedToClob(NeutralType.Text(4001)) shouldBe true
        mapper.isWidenedToClob(NeutralType.Text(4000)) shouldBe false
        // Kein deklariertes Laengenlimit ist von Anfang an unbegrenzt -- nichts
        // wurde geweitet, anders als eine explizit zu lange Laenge.
        mapper.isWidenedToClob(NeutralType.Text(null)) shouldBe false
    }

    test("Char within 2000 chars maps to CHAR; beyond widens to CLOB") {
        mapper.toSql(NeutralType.Char(10)) shouldBe "CHAR(10)"
        mapper.toSql(NeutralType.Char(2000)) shouldBe "CHAR(2000)"
        mapper.toSql(NeutralType.Char(2001)) shouldBe "CLOB"
        mapper.isWidenedToClob(NeutralType.Char(2001)) shouldBe true
    }

    test("Float precisions map to BINARY_FLOAT/BINARY_DOUBLE") {
        mapper.toSql(NeutralType.Float(FloatPrecision.SINGLE)) shouldBe "BINARY_FLOAT"
        mapper.toSql(NeutralType.Float(FloatPrecision.DOUBLE)) shouldBe "BINARY_DOUBLE"
    }

    test("DateTime maps to DATE without timezone, TIMESTAMP WITH TIME ZONE with") {
        mapper.toSql(NeutralType.DateTime(timezone = false)) shouldBe "DATE"
        mapper.toSql(NeutralType.DateTime(timezone = true)) shouldBe "TIMESTAMP WITH TIME ZONE"
    }

    test("Date maps to DATE; Time has no native type and falls back to VARCHAR2(8)") {
        mapper.toSql(NeutralType.Date) shouldBe "DATE"
        mapper.toSql(NeutralType.Time) shouldBe "VARCHAR2(8)"
    }

    test("Uuid maps to VARCHAR2(36)") {
        mapper.toSql(NeutralType.Uuid) shouldBe "VARCHAR2(36)"
    }

    test("Json and Xml map to native Oracle types, not a text fallback") {
        mapper.toSql(NeutralType.Json) shouldBe "JSON"
        mapper.toSql(NeutralType.Xml) shouldBe "XMLTYPE"
    }

    test("Binary maps to BLOB; FullText degrades to CLOB (no native vector type)") {
        mapper.toSql(NeutralType.Binary) shouldBe "BLOB"
        mapper.toSql(NeutralType.FullText) shouldBe "CLOB"
    }

    test("Email maps to VARCHAR2(254)") {
        mapper.toSql(NeutralType.Email) shouldBe "VARCHAR2(254)"
    }

    test("Array has no native type and degrades to JSON") {
        mapper.toSql(NeutralType.Array("text")) shouldBe "JSON"
    }

    test("Enum without values falls back to a generic VARCHAR2 (column helper renders the real width)") {
        mapper.toSql(NeutralType.Enum(values = listOf("a", "b"))) shouldBe "VARCHAR2(4000)"
    }

    test("Geometry maps to SDO_GEOMETRY (unreachable in practice: spatial tables are blocked earlier)") {
        mapper.toSql(NeutralType.Geometry(GeometryType("point"), 4326)) shouldBe "SDO_GEOMETRY"
    }

    test("isLargeObject flags CLOB/BLOB-rendered types as not key-eligible") {
        mapper.isLargeObject(NeutralType.Text(null)) shouldBe true
        mapper.isLargeObject(NeutralType.Text(4001)) shouldBe true
        mapper.isLargeObject(NeutralType.Text(100)) shouldBe false
        mapper.isLargeObject(NeutralType.Binary) shouldBe true
        mapper.isLargeObject(NeutralType.FullText) shouldBe true
        // refType present (possibly DOMAIN -> CLOB): stays conservatively LOB
        // without schema access to resolve it.
        mapper.isLargeObject(NeutralType.Enum(values = null, refType = "status_domain")) shouldBe true
        // No refType at all: plainColumn renders VARCHAR2(4000), not a LOB.
        mapper.isLargeObject(NeutralType.Enum(values = null, refType = null)) shouldBe false
        mapper.isLargeObject(NeutralType.Enum(values = listOf("a"))) shouldBe false
        mapper.isLargeObject(NeutralType.Integer) shouldBe false
    }

    test("toDefaultSql renders string/number/boolean literals") {
        mapper.toDefaultSql(DefaultValue.StringLiteral("it's fine"), NeutralType.Text(null)) shouldBe "'it''s fine'"
        mapper.toDefaultSql(DefaultValue.NumberLiteral(42L), NeutralType.Integer) shouldBe "42"
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(true), NeutralType.BooleanType) shouldBe "1"
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(false), NeutralType.BooleanType) shouldBe "0"
    }

    test("toDefaultSql renders current_timestamp as SYSTIMESTAMP for tz-aware columns, SYSDATE otherwise") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_timestamp"), NeutralType.DateTime(true)) shouldBe "SYSTIMESTAMP"
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_timestamp"), NeutralType.DateTime(false)) shouldBe "SYSDATE"
    }

    test("toDefaultSql renders current_date/current_time/gen_uuid") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_date"), NeutralType.Date) shouldBe "TRUNC(SYSDATE)"
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_time"), NeutralType.Time) shouldBe
            "TO_CHAR(SYSDATE, 'HH24:MI:SS')"
        mapper.toDefaultSql(DefaultValue.FunctionCall("gen_uuid"), NeutralType.Uuid) shouldBe "RAWTOHEX(SYS_GUID())"
    }

    test("toDefaultSql renders SequenceNextVal as quoted <seq>.NEXTVAL") {
        mapper.toDefaultSql(DefaultValue.SequenceNextVal("order_seq"), NeutralType.Integer) shouldBe "\"order_seq\".NEXTVAL"
    }

    test("toDefaultSql passes an unknown function through, adding parens only if missing") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("some_func"), NeutralType.Text(null)) shouldBe "some_func()"
        mapper.toDefaultSql(DefaultValue.FunctionCall("some_func()"), NeutralType.Text(null)) shouldBe "some_func()"
    }
})
