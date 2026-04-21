package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteTypeMapperTest : FunSpec({

    val mapper = SqliteTypeMapper()

    // -- toSql --

    test("Identifier with autoIncrement contains PRIMARY KEY AUTOINCREMENT") {
        mapper.toSql(NeutralType.Identifier(autoIncrement = true)) shouldContain "PRIMARY KEY AUTOINCREMENT"
    }

    test("Text with maxLength maps to TEXT") {
        mapper.toSql(NeutralType.Text(maxLength = 255)) shouldBe "TEXT"
    }

    test("Text without maxLength maps to TEXT") {
        mapper.toSql(NeutralType.Text()) shouldBe "TEXT"
    }

    test("Char maps to TEXT") {
        mapper.toSql(NeutralType.Char(2)) shouldBe "TEXT"
    }

    test("Integer maps to INTEGER") {
        mapper.toSql(NeutralType.Integer) shouldBe "INTEGER"
    }

    test("SmallInt maps to INTEGER") {
        mapper.toSql(NeutralType.SmallInt) shouldBe "INTEGER"
    }

    test("BigInteger maps to INTEGER") {
        mapper.toSql(NeutralType.BigInteger) shouldBe "INTEGER"
    }

    test("Float SINGLE maps to REAL") {
        mapper.toSql(NeutralType.Float(FloatPrecision.SINGLE)) shouldBe "REAL"
    }

    test("Float DOUBLE maps to REAL") {
        mapper.toSql(NeutralType.Float(FloatPrecision.DOUBLE)) shouldBe "REAL"
    }

    test("Decimal maps to REAL") {
        mapper.toSql(NeutralType.Decimal(10, 2)) shouldBe "REAL"
    }

    test("BooleanType maps to INTEGER") {
        mapper.toSql(NeutralType.BooleanType) shouldBe "INTEGER"
    }

    test("DateTime maps to TEXT") {
        mapper.toSql(NeutralType.DateTime()) shouldBe "TEXT"
    }

    test("Date maps to TEXT") {
        mapper.toSql(NeutralType.Date) shouldBe "TEXT"
    }

    test("Time maps to TEXT") {
        mapper.toSql(NeutralType.Time) shouldBe "TEXT"
    }

    test("Uuid maps to TEXT") {
        mapper.toSql(NeutralType.Uuid) shouldBe "TEXT"
    }

    test("Json maps to TEXT") {
        mapper.toSql(NeutralType.Json) shouldBe "TEXT"
    }

    test("Xml maps to TEXT") {
        mapper.toSql(NeutralType.Xml) shouldBe "TEXT"
    }

    test("Binary maps to BLOB") {
        mapper.toSql(NeutralType.Binary) shouldBe "BLOB"
    }

    test("Email maps to TEXT") {
        mapper.toSql(NeutralType.Email) shouldBe "TEXT"
    }

    test("Enum maps to TEXT") {
        mapper.toSql(NeutralType.Enum()) shouldBe "TEXT"
    }

    test("Array maps to TEXT") {
        mapper.toSql(NeutralType.Array("text")) shouldBe "TEXT"
    }

    // -- toDefaultSql --

    test("BooleanLiteral true renders 1") {
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(true), NeutralType.BooleanType) shouldBe "1"
    }

    test("FunctionCall current_timestamp contains datetime") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_timestamp"), NeutralType.DateTime()) shouldContain "datetime"
    }

    test("FunctionCall gen_uuid contains hex") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("gen_uuid"), NeutralType.Uuid) shouldContain "hex"
    }

    test("SequenceNextVal throws defensive error") {
        val ex = io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            mapper.toDefaultSql(DefaultValue.SequenceNextVal("my_seq"), NeutralType.Integer)
        }
        ex.message shouldContain "not supported"
    }

    // -- dialect --

    test("dialect is SQLITE") {
        mapper.dialect shouldBe DatabaseDialect.SQLITE
    }

    // -- geometry (Spatial Phase 1) --

    test("geometry maps to GEOMETRY (placeholder, actual DDL via AddGeometryColumn)") {
        mapper.toSql(NeutralType.Geometry()) shouldBe "GEOMETRY"
    }
})
