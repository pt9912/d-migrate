package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.GeometryType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MssqlTypeMapperTest : FunSpec({

    val mapper = MssqlTypeMapper()

    test("dialect is MSSQL") {
        mapper.dialect shouldBe DatabaseDialect.MSSQL
    }

    test("integer family and identifier") {
        mapper.toSql(NeutralType.Identifier(autoIncrement = true)) shouldBe "INT IDENTITY(1,1)"
        mapper.toSql(NeutralType.Identifier()) shouldBe "INT"
        mapper.toSql(NeutralType.Integer) shouldBe "INT"
        mapper.toSql(NeutralType.SmallInt) shouldBe "SMALLINT"
        mapper.toSql(NeutralType.BigInteger) shouldBe "BIGINT"
    }

    test("text family is Unicode-safe and widens beyond 4000 characters") {
        mapper.toSql(NeutralType.Text(100)) shouldBe "NVARCHAR(100)"
        mapper.toSql(NeutralType.Text(4000)) shouldBe "NVARCHAR(4000)"
        mapper.toSql(NeutralType.Text(4001)) shouldBe "NVARCHAR(MAX)"
        mapper.toSql(NeutralType.Text()) shouldBe "NVARCHAR(MAX)"
        mapper.toSql(NeutralType.Char(2)) shouldBe "NCHAR(2)"
        mapper.toSql(NeutralType.Char(5000)) shouldBe "NVARCHAR(MAX)"
        mapper.toSql(NeutralType.Email) shouldBe "NVARCHAR(254)"
        mapper.isWidenedToMax(NeutralType.Text(4001)) shouldBe true
        mapper.isWidenedToMax(NeutralType.Text(4000)) shouldBe false
        mapper.isWidenedToMax(NeutralType.Text()) shouldBe false
        mapper.isWidenedToMax(NeutralType.Char(4001)) shouldBe true
        mapper.isWidenedToMax(NeutralType.Integer) shouldBe false
    }

    test("numeric, boolean and temporal types") {
        mapper.toSql(NeutralType.Float(FloatPrecision.SINGLE)) shouldBe "REAL"
        mapper.toSql(NeutralType.Float(FloatPrecision.DOUBLE)) shouldBe "FLOAT"
        mapper.toSql(NeutralType.Decimal(10, 2)) shouldBe "DECIMAL(10,2)"
        mapper.toSql(NeutralType.BooleanType) shouldBe "BIT"
        mapper.toSql(NeutralType.DateTime(timezone = false)) shouldBe "DATETIME2"
        mapper.toSql(NeutralType.DateTime(timezone = true)) shouldBe "DATETIMEOFFSET"
        mapper.toSql(NeutralType.Date) shouldBe "DATE"
        mapper.toSql(NeutralType.Time) shouldBe "TIME"
    }

    test("decimal precision is clamped to 38") {
        mapper.toSql(NeutralType.Decimal(50, 10)) shouldBe "DECIMAL(38,10)"
        mapper.toSql(NeutralType.Decimal(50, 45)) shouldBe "DECIMAL(38,38)"
        mapper.isPrecisionClamped(NeutralType.Decimal(50, 10)) shouldBe true
        mapper.isPrecisionClamped(NeutralType.Decimal(38, 2)) shouldBe false
    }

    test("opaque and degraded types") {
        mapper.toSql(NeutralType.Uuid) shouldBe "UNIQUEIDENTIFIER"
        mapper.toSql(NeutralType.Json) shouldBe "NVARCHAR(MAX)"
        mapper.toSql(NeutralType.Xml) shouldBe "XML"
        mapper.toSql(NeutralType.Binary) shouldBe "VARBINARY(MAX)"
        mapper.toSql(NeutralType.Array("text")) shouldBe "NVARCHAR(MAX)"
        mapper.toSql(NeutralType.FullText) shouldBe "NVARCHAR(MAX)"
        mapper.toSql(NeutralType.Enum(values = listOf("a"))) shouldBe "NVARCHAR(MAX)"
    }

    test("geodetic SRIDs map to geography, planar and SRID-less geometries to geometry") {
        mapper.toSql(NeutralType.Geometry(GeometryType("point"), 4326)) shouldBe "geography"
        mapper.toSql(NeutralType.Geometry(GeometryType("polygon"), 4258)) shouldBe "geography"
        mapper.toSql(NeutralType.Geometry(GeometryType("point"), 3857)) shouldBe "geometry"
        mapper.toSql(NeutralType.Geometry(GeometryType("point"), 25832)) shouldBe "geometry"
        mapper.toSql(NeutralType.Geometry()) shouldBe "geometry"
        mapper.isGeodeticSrid(4326) shouldBe true
        mapper.isGeodeticSrid(3857) shouldBe false
        mapper.isGeodeticSrid(null) shouldBe false
    }

    test("defaults: literals, booleans, functions, sequences") {
        mapper.toDefaultSql(DefaultValue.StringLiteral("it's"), NeutralType.Text()) shouldBe "N'it''s'"
        mapper.toDefaultSql(DefaultValue.NumberLiteral(42), NeutralType.Integer) shouldBe "42"
        mapper.toDefaultSql(DefaultValue.NumberLiteral(1.5), NeutralType.Float()) shouldBe "1.5"
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(true), NeutralType.BooleanType) shouldBe "1"
        mapper.toDefaultSql(DefaultValue.BooleanLiteral(false), NeutralType.BooleanType) shouldBe "0"
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_timestamp"), NeutralType.DateTime()) shouldBe
            "CURRENT_TIMESTAMP"
        // DATETIMEOFFSET-Spalten brauchen den offset-tragenden Zeitstempel.
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_timestamp"), NeutralType.DateTime(timezone = true)) shouldBe
            "SYSDATETIMEOFFSET()"
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_date"), NeutralType.Date) shouldBe
            "CAST(GETDATE() AS DATE)"
        mapper.toDefaultSql(DefaultValue.FunctionCall("current_time"), NeutralType.Time) shouldBe
            "CAST(GETDATE() AS TIME)"
        mapper.toDefaultSql(DefaultValue.FunctionCall("gen_uuid"), NeutralType.Uuid) shouldBe "NEWID()"
        // Reverse-gelieferte Funktions-Defaults tragen ihre Klammern bereits.
        mapper.toDefaultSql(DefaultValue.FunctionCall("sysdatetimeoffset()"), NeutralType.DateTime(true)) shouldBe
            "sysdatetimeoffset()"
        mapper.toDefaultSql(DefaultValue.FunctionCall("newsequentialid"), NeutralType.Uuid) shouldBe
            "newsequentialid()"
        mapper.toDefaultSql(DefaultValue.SequenceNextVal("order_seq"), NeutralType.BigInteger) shouldBe
            "NEXT VALUE FOR [order_seq]"
    }
})
