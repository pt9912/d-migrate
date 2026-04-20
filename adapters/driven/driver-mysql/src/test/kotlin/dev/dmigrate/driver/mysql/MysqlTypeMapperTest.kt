package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MysqlTypeMapperTest : FunSpec({

    val mapper = MysqlTypeMapper()

    // -- toSql --

    test("Identifier with autoIncrement contains AUTO_INCREMENT") {
        mapper.toSql(NeutralType.Identifier(autoIncrement = true)) shouldContain "AUTO_INCREMENT"
    }

    test("Text with maxLength maps to VARCHAR") {
        mapper.toSql(NeutralType.Text(maxLength = 255)) shouldBe "VARCHAR(255)"
    }

    test("Text without maxLength maps to TEXT") {
        mapper.toSql(NeutralType.Text()) shouldBe "TEXT"
    }

    test("Char maps to CHAR with length") {
        mapper.toSql(NeutralType.Char(2)) shouldBe "CHAR(2)"
    }

    test("Integer maps to INT") {
        mapper.toSql(NeutralType.Integer) shouldBe "INT"
    }

    test("SmallInt maps to SMALLINT") {
        mapper.toSql(NeutralType.SmallInt) shouldBe "SMALLINT"
    }

    test("BigInteger maps to BIGINT") {
        mapper.toSql(NeutralType.BigInteger) shouldBe "BIGINT"
    }

    test("Float SINGLE maps to FLOAT") {
        mapper.toSql(NeutralType.Float(FloatPrecision.SINGLE)) shouldBe "FLOAT"
    }

    test("Float DOUBLE maps to DOUBLE") {
        mapper.toSql(NeutralType.Float(FloatPrecision.DOUBLE)) shouldBe "DOUBLE"
    }

    test("Decimal maps to DECIMAL with precision and scale") {
        mapper.toSql(NeutralType.Decimal(10, 2)) shouldBe "DECIMAL(10,2)"
    }

    test("BooleanType maps to TINYINT(1)") {
        mapper.toSql(NeutralType.BooleanType) shouldBe "TINYINT(1)"
    }

    test("DateTime maps to DATETIME") {
        mapper.toSql(NeutralType.DateTime()) shouldBe "DATETIME"
    }

    test("Date maps to DATE") {
        mapper.toSql(NeutralType.Date) shouldBe "DATE"
    }

    test("Time maps to TIME") {
        mapper.toSql(NeutralType.Time) shouldBe "TIME"
    }

    test("Uuid maps to CHAR(36)") {
        mapper.toSql(NeutralType.Uuid) shouldBe "CHAR(36)"
    }

    test("Json maps to JSON") {
        mapper.toSql(NeutralType.Json) shouldBe "JSON"
    }

    test("Xml maps to TEXT") {
        mapper.toSql(NeutralType.Xml) shouldBe "TEXT"
    }

    test("Binary maps to BLOB") {
        mapper.toSql(NeutralType.Binary) shouldBe "BLOB"
    }

    test("Email maps to VARCHAR(254)") {
        mapper.toSql(NeutralType.Email) shouldBe "VARCHAR(254)"
    }

    test("Enum maps to TEXT") {
        mapper.toSql(NeutralType.Enum()) shouldBe "TEXT"
    }

    test("Array maps to JSON") {
        mapper.toSql(NeutralType.Array("text")) shouldBe "JSON"
    }

    // -- toDefaultSql --

    test("BooleanLiteral true renders 1") {
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(true), NeutralType.BooleanType) shouldBe "1"
    }

    test("BooleanLiteral false renders 0") {
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(false), NeutralType.BooleanType) shouldBe "0"
    }

    test("FunctionCall gen_uuid contains UUID") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("gen_uuid"), NeutralType.Uuid) shouldContain "UUID"
    }

    test("SequenceNextVal throws defensive error") {
        val ex = io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            mapper.toDefaultSql(DefaultValue.SequenceNextVal("my_seq"), NeutralType.Integer)
        }
        ex.message shouldContain "helper_table"
    }

    // -- dialect --

    test("dialect is MYSQL") {
        mapper.dialect shouldBe DatabaseDialect.MYSQL
    }

    // -- geometry (Spatial Phase 1) --

    test("geometry default maps to GEOMETRY") {
        mapper.toSql(NeutralType.Geometry()) shouldBe "GEOMETRY"
    }

    test("geometry point maps to POINT") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("point"))) shouldBe "POINT"
    }

    test("geometry polygon maps to POLYGON") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("polygon"))) shouldBe "POLYGON"
    }

    test("geometry linestring maps to LINESTRING") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("linestring"))) shouldBe "LINESTRING"
    }

    test("geometry multipoint maps to MULTIPOINT") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("multipoint"))) shouldBe "MULTIPOINT"
    }

    test("geometry multilinestring maps to MULTILINESTRING") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("multilinestring"))) shouldBe "MULTILINESTRING"
    }

    test("geometry multipolygon maps to MULTIPOLYGON") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("multipolygon"))) shouldBe "MULTIPOLYGON"
    }

    test("geometry geometrycollection maps to GEOMETRYCOLLECTION") {
        mapper.toSql(NeutralType.Geometry(dev.dmigrate.core.model.GeometryType("geometrycollection"))) shouldBe "GEOMETRYCOLLECTION"
    }
})
