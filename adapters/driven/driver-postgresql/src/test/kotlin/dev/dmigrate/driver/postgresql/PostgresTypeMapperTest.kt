package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PostgresTypeMapperTest : FunSpec({

    val mapper = PostgresTypeMapper()

    // -- toSql --

    test("Identifier with autoIncrement maps to SERIAL") {
        mapper.toSql(NeutralType.Identifier(autoIncrement = true)) shouldBe "SERIAL"
    }

    test("Identifier without autoIncrement maps to INTEGER") {
        mapper.toSql(NeutralType.Identifier(autoIncrement = false)) shouldBe "INTEGER"
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

    test("Integer maps to INTEGER") {
        mapper.toSql(NeutralType.Integer) shouldBe "INTEGER"
    }

    test("SmallInt maps to SMALLINT") {
        mapper.toSql(NeutralType.SmallInt) shouldBe "SMALLINT"
    }

    test("BigInteger maps to BIGINT") {
        mapper.toSql(NeutralType.BigInteger) shouldBe "BIGINT"
    }

    test("Float SINGLE maps to REAL") {
        mapper.toSql(NeutralType.Float(FloatPrecision.SINGLE)) shouldBe "REAL"
    }

    test("Float DOUBLE maps to DOUBLE PRECISION") {
        mapper.toSql(NeutralType.Float(FloatPrecision.DOUBLE)) shouldBe "DOUBLE PRECISION"
    }

    test("Decimal maps to DECIMAL with precision and scale") {
        mapper.toSql(NeutralType.Decimal(10, 2)) shouldBe "DECIMAL(10,2)"
    }

    test("BooleanType maps to BOOLEAN") {
        mapper.toSql(NeutralType.BooleanType) shouldBe "BOOLEAN"
    }

    test("DateTime with timezone maps to TIMESTAMP WITH TIME ZONE") {
        mapper.toSql(NeutralType.DateTime(timezone = true)) shouldBe "TIMESTAMP WITH TIME ZONE"
    }

    test("DateTime without timezone maps to TIMESTAMP") {
        mapper.toSql(NeutralType.DateTime(timezone = false)) shouldBe "TIMESTAMP"
    }

    test("Date maps to DATE") {
        mapper.toSql(NeutralType.Date) shouldBe "DATE"
    }

    test("Time maps to TIME") {
        mapper.toSql(NeutralType.Time) shouldBe "TIME"
    }

    test("Uuid maps to UUID") {
        mapper.toSql(NeutralType.Uuid) shouldBe "UUID"
    }

    test("Json maps to JSONB") {
        mapper.toSql(NeutralType.Json) shouldBe "JSONB"
    }

    test("Xml maps to XML") {
        mapper.toSql(NeutralType.Xml) shouldBe "XML"
    }

    test("Binary maps to BYTEA") {
        mapper.toSql(NeutralType.Binary) shouldBe "BYTEA"
    }

    test("Email maps to VARCHAR(254)") {
        mapper.toSql(NeutralType.Email) shouldBe "VARCHAR(254)"
    }

    test("Enum maps to TEXT") {
        mapper.toSql(NeutralType.Enum()) shouldBe "TEXT"
    }

    test("Array maps to element type with brackets") {
        mapper.toSql(NeutralType.Array("text")) shouldContain "[]"
    }

    // -- toDefaultSql --

    test("StringLiteral wraps value in single quotes") {
        mapper.toDefaultSql(DefaultValue.StringLiteral("hello"), NeutralType.Text()) shouldBe "'hello'"
    }

    test("StringLiteral escapes single quotes") {
        mapper.toDefaultSql(DefaultValue.StringLiteral("it's"), NeutralType.Text()) shouldBe "'it''s'"
    }

    test("NumberLiteral renders number as string") {
        mapper.toDefaultSql(DefaultValue.NumberLiteral(42), NeutralType.Integer) shouldBe "42"
    }

    test("BooleanLiteral true renders TRUE") {
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(true), NeutralType.BooleanType) shouldBe "TRUE"
    }

    test("BooleanLiteral false renders FALSE") {
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(false), NeutralType.BooleanType) shouldBe "FALSE"
    }

    test("FunctionCall current_timestamp renders CURRENT_TIMESTAMP") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_timestamp"), NeutralType.DateTime()) shouldBe "CURRENT_TIMESTAMP"
    }

    test("FunctionCall gen_uuid renders gen_random_uuid()") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("gen_uuid"), NeutralType.Uuid) shouldBe "gen_random_uuid()"
    }

    // -- dialect --

    test("dialect is POSTGRESQL") {
        mapper.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    // -- geometry (Spatial Phase 1) --

    test("geometry default maps to geometry(Geometry, 0)") {
        mapper.toSql(NeutralType.Geometry()) shouldBe "geometry(Geometry, 0)"
    }

    test("geometry point with srid maps to geometry(Point, 4326)") {
        mapper.toSql(NeutralType.Geometry(
            dev.dmigrate.core.model.GeometryType("point"), srid = 4326
        )) shouldBe "geometry(Point, 4326)"
    }

    test("geometry polygon without srid maps to geometry(Polygon, 0)") {
        mapper.toSql(NeutralType.Geometry(
            dev.dmigrate.core.model.GeometryType("polygon")
        )) shouldBe "geometry(Polygon, 0)"
    }

    test("geometry multipolygon maps PostGIS CamelCase") {
        mapper.toSql(NeutralType.Geometry(
            dev.dmigrate.core.model.GeometryType("multipolygon"), srid = 3857
        )) shouldBe "geometry(MultiPolygon, 3857)"
    }

    test("geometry multipoint maps PostGIS CamelCase") {
        mapper.toSql(NeutralType.Geometry(
            dev.dmigrate.core.model.GeometryType("multipoint"), srid = 4326
        )) shouldBe "geometry(MultiPoint, 4326)"
    }

    test("geometry multilinestring maps PostGIS CamelCase") {
        mapper.toSql(NeutralType.Geometry(
            dev.dmigrate.core.model.GeometryType("multilinestring"), srid = 4326
        )) shouldBe "geometry(MultiLineString, 4326)"
    }

    test("geometry geometrycollection maps PostGIS CamelCase") {
        mapper.toSql(NeutralType.Geometry(
            dev.dmigrate.core.model.GeometryType("geometrycollection"), srid = 4326
        )) shouldBe "geometry(GeometryCollection, 4326)"
    }

    test("FunctionCall unknown renders with parentheses") {
        mapper.toDefaultSql(DefaultValue.FunctionCall("custom_fn"), NeutralType.Text()) shouldBe "custom_fn()"
    }
})
