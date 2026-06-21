package dev.dmigrate.driver.data

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Types

class JdbcToNeutralTypeMapperTest : FunSpec({

    test("BIT/BOOLEAN -> BooleanType") {
        JdbcToNeutralTypeMapper.map(Types.BIT, null, null, null) shouldBe NeutralType.BooleanType
        JdbcToNeutralTypeMapper.map(Types.BOOLEAN, null, null, null) shouldBe NeutralType.BooleanType
    }

    test("TINYINT/SMALLINT -> SmallInt") {
        JdbcToNeutralTypeMapper.map(Types.TINYINT, null, null, null) shouldBe NeutralType.SmallInt
        JdbcToNeutralTypeMapper.map(Types.SMALLINT, null, null, null) shouldBe NeutralType.SmallInt
    }

    test("INTEGER -> Integer") {
        JdbcToNeutralTypeMapper.map(Types.INTEGER, null, null, null) shouldBe NeutralType.Integer
    }

    test("INTEGER + autoIncrement=true -> Identifier(autoIncrement=true)") {
        JdbcToNeutralTypeMapper.map(Types.INTEGER, null, null, null, isAutoIncrement = true) shouldBe
            NeutralType.Identifier(autoIncrement = true)
    }

    test("BIGINT -> BigInteger") {
        JdbcToNeutralTypeMapper.map(Types.BIGINT, null, null, null) shouldBe NeutralType.BigInteger
    }

    test("REAL -> Float(SINGLE)") {
        JdbcToNeutralTypeMapper.map(Types.REAL, null, null, null) shouldBe
            NeutralType.Float(FloatPrecision.SINGLE)
    }

    test("FLOAT/DOUBLE -> Float(DOUBLE)") {
        JdbcToNeutralTypeMapper.map(Types.FLOAT, null, null, null) shouldBe
            NeutralType.Float(FloatPrecision.DOUBLE)
        JdbcToNeutralTypeMapper.map(Types.DOUBLE, null, null, null) shouldBe
            NeutralType.Float(FloatPrecision.DOUBLE)
    }

    test("DECIMAL/NUMERIC -> Decimal(precision, scale) mit Defaults") {
        JdbcToNeutralTypeMapper.map(Types.DECIMAL, null, 10, 2) shouldBe
            NeutralType.Decimal(precision = 10, scale = 2)
        JdbcToNeutralTypeMapper.map(Types.NUMERIC, null, null, null) shouldBe
            NeutralType.Decimal(precision = 38, scale = 0)
    }

    test("CHAR -> Char(length)") {
        JdbcToNeutralTypeMapper.map(Types.CHAR, null, 10, null) shouldBe NeutralType.Char(length = 10)
        // ohne precision: length = 1
        JdbcToNeutralTypeMapper.map(Types.CHAR, null, null, null) shouldBe NeutralType.Char(length = 1)
    }

    test("VARCHAR/LONGVARCHAR/NVARCHAR -> Text") {
        JdbcToNeutralTypeMapper.map(Types.VARCHAR, null, 255, null) shouldBe
            NeutralType.Text(maxLength = 255)
        JdbcToNeutralTypeMapper.map(Types.LONGVARCHAR, null, null, null) shouldBe NeutralType.Text()
        JdbcToNeutralTypeMapper.map(Types.NVARCHAR, null, 0, null) shouldBe NeutralType.Text()
        JdbcToNeutralTypeMapper.map(Types.LONGNVARCHAR, null, null, null) shouldBe NeutralType.Text()
    }

    test("BINARY/VARBINARY/LONGVARBINARY -> Binary") {
        JdbcToNeutralTypeMapper.map(Types.BINARY, null, null, null) shouldBe NeutralType.Binary
        JdbcToNeutralTypeMapper.map(Types.VARBINARY, null, null, null) shouldBe NeutralType.Binary
        JdbcToNeutralTypeMapper.map(Types.LONGVARBINARY, null, null, null) shouldBe NeutralType.Binary
    }

    test("DATE/TIME/TIMESTAMP/TIMESTAMP_WITH_TIMEZONE") {
        JdbcToNeutralTypeMapper.map(Types.DATE, null, null, null) shouldBe NeutralType.Date
        JdbcToNeutralTypeMapper.map(Types.TIME, null, null, null) shouldBe NeutralType.Time
        JdbcToNeutralTypeMapper.map(Types.TIME_WITH_TIMEZONE, null, null, null) shouldBe NeutralType.Time
        JdbcToNeutralTypeMapper.map(Types.TIMESTAMP, null, null, null) shouldBe
            NeutralType.DateTime(timezone = false)
        JdbcToNeutralTypeMapper.map(Types.TIMESTAMP_WITH_TIMEZONE, null, null, null) shouldBe
            NeutralType.DateTime(timezone = true)
    }

    test("ARRAY -> Array(element type)") {
        JdbcToNeutralTypeMapper.map(Types.ARRAY, "int4", null, null) shouldBe
            NeutralType.Array(elementType = "int4")
        JdbcToNeutralTypeMapper.map(Types.ARRAY, null, null, null) shouldBe
            NeutralType.Array(elementType = "unknown")
    }

    test("OTHER dispatcht ueber sqlTypeName") {
        JdbcToNeutralTypeMapper.map(Types.OTHER, "uuid", null, null) shouldBe NeutralType.Uuid
        JdbcToNeutralTypeMapper.map(Types.OTHER, "UUID", null, null) shouldBe NeutralType.Uuid
        JdbcToNeutralTypeMapper.map(Types.OTHER, "json", null, null) shouldBe NeutralType.Json
        JdbcToNeutralTypeMapper.map(Types.OTHER, "jsonb", null, null) shouldBe NeutralType.Json
        JdbcToNeutralTypeMapper.map(Types.OTHER, "xml", null, null) shouldBe NeutralType.Xml
        JdbcToNeutralTypeMapper.map(Types.OTHER, null, null, null) shouldBe NeutralType.Text()
    }

    // Der Mapper erkennt Geometrie NICHT (mehr) typeName-basiert: native PG-Typen
    // (point/polygon/…) heißen wie OGC-Subtypen, sind aber kein WKB. Die
    // Geometrie-Markierung ist dialekt-bewusst im Reader (probedColumns/
    // JdbcChunkSequence), nicht hier. Der Mapper bleibt rein JDBC-basiert.
    test("geometry-/PG-native typeNames fallen auf das reine JDBC-Mapping zurück") {
        // PostGIS geometry meldet Types.OTHER → unbekannter OTHER-Typ → Text
        JdbcToNeutralTypeMapper.map(Types.OTHER, "geometry", null, null) shouldBe NeutralType.Text()
        JdbcToNeutralTypeMapper.map(Types.OTHER, "point", null, null) shouldBe NeutralType.Text()
        // MySQL geometry meldet Types.BINARY → Binary (rein per JDBC-Code)
        JdbcToNeutralTypeMapper.map(Types.BINARY, "GEOMETRY", null, null) shouldBe NeutralType.Binary
        JdbcToNeutralTypeMapper.map(Types.BINARY, "VARBINARY", null, null) shouldBe NeutralType.Binary
    }

    test("Unbekannte JDBC-Typen fallen auf Text zurueck") {
        JdbcToNeutralTypeMapper.map(Types.STRUCT, null, null, null) shouldBe NeutralType.Text()
        JdbcToNeutralTypeMapper.map(Types.REF, null, null, null) shouldBe NeutralType.Text()
    }
})
