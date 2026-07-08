package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Types

/**
 * Unit-Tests fuer [ImportTypeCompatibility]. Pure-Logic-Klasse — testet jeden
 * [NeutralType]-Zweig in `isTypeCompatible`, plus `describe` und `isMultiBit`.
 */
class ImportTypeCompatibilityTest : FunSpec({

    fun col(jdbcType: Int, sqlTypeName: String? = null): TargetColumn =
        TargetColumn(name = "c", nullable = true, jdbcType = jdbcType, sqlTypeName = sqlTypeName)

    fun assertCompat(type: NeutralType, target: TargetColumn, expected: Boolean) {
        ImportTypeCompatibility.isTypeCompatible(type, target) shouldBe expected
    }

    context("Identifier") {
        test("matches integer family") {
            for (t in listOf(Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL)) {
                assertCompat(NeutralType.Identifier(), col(t), true)
            }
        }
        test("rejects non-integer") {
            assertCompat(NeutralType.Identifier(), col(Types.VARCHAR), false)
        }
    }

    context("Text family") {
        test("Text accepts text-compatible jdbc types") {
            for (t in listOf(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB)) {
                assertCompat(NeutralType.Text(), col(t), true)
            }
        }
        test("Text accepts sqlTypeName containing TEXT") {
            assertCompat(NeutralType.Text(), col(Types.OTHER, "TEXT"), true)
            assertCompat(NeutralType.Text(), col(Types.OTHER, "MEDIUMTEXT"), true)
        }
        test("Text rejects integer") {
            assertCompat(NeutralType.Text(), col(Types.INTEGER), false)
        }
        test("Email shares Text rules") {
            assertCompat(NeutralType.Email, col(Types.VARCHAR), true)
            assertCompat(NeutralType.Email, col(Types.INTEGER), false)
        }
        test("Char accepts CHAR/NCHAR only") {
            assertCompat(NeutralType.Char(8), col(Types.CHAR), true)
            assertCompat(NeutralType.Char(8), col(Types.NCHAR), true)
            assertCompat(NeutralType.Char(8), col(Types.VARCHAR), false)
        }
    }

    context("Numeric family") {
        test("Integer matches Types.INTEGER and INT4") {
            assertCompat(NeutralType.Integer, col(Types.INTEGER), true)
            assertCompat(NeutralType.Integer, col(Types.OTHER, "INT4"), true)
            assertCompat(NeutralType.Integer, col(Types.BIGINT), false)
        }
        test("SmallInt matches Types.SMALLINT and INT2") {
            assertCompat(NeutralType.SmallInt, col(Types.SMALLINT), true)
            assertCompat(NeutralType.SmallInt, col(Types.OTHER, "INT2"), true)
            assertCompat(NeutralType.SmallInt, col(Types.INTEGER), false)
        }
        test("BigInteger matches Types.BIGINT and INT8") {
            assertCompat(NeutralType.BigInteger, col(Types.BIGINT), true)
            assertCompat(NeutralType.BigInteger, col(Types.OTHER, "INT8"), true)
            assertCompat(NeutralType.BigInteger, col(Types.INTEGER), false)
        }
        test("Float SINGLE accepts REAL/FLOAT") {
            val single = NeutralType.Float(FloatPrecision.SINGLE)
            assertCompat(single, col(Types.REAL), true)
            assertCompat(single, col(Types.FLOAT), true)
            assertCompat(single, col(Types.DOUBLE), false)
        }
        test("Float DOUBLE accepts DOUBLE/FLOAT/REAL") {
            val dbl = NeutralType.Float(FloatPrecision.DOUBLE)
            assertCompat(dbl, col(Types.DOUBLE), true)
            assertCompat(dbl, col(Types.FLOAT), true)
            assertCompat(dbl, col(Types.REAL), true)
            assertCompat(dbl, col(Types.INTEGER), false)
        }
        test("Decimal accepts NUMERIC/DECIMAL") {
            assertCompat(NeutralType.Decimal(10, 2), col(Types.DECIMAL), true)
            assertCompat(NeutralType.Decimal(10, 2), col(Types.NUMERIC), true)
            assertCompat(NeutralType.Decimal(10, 2), col(Types.INTEGER), false)
        }
        test("Boolean accepts BOOLEAN and single-bit BIT") {
            assertCompat(NeutralType.BooleanType, col(Types.BOOLEAN), true)
            assertCompat(NeutralType.BooleanType, col(Types.BIT, "BIT"), true)
            assertCompat(NeutralType.BooleanType, col(Types.BIT, "BIT(1)"), true)
        }
        test("Boolean rejects multi-bit BIT and others") {
            assertCompat(NeutralType.BooleanType, col(Types.BIT, "BIT(8)"), false)
            assertCompat(NeutralType.BooleanType, col(Types.INTEGER), false)
        }
    }

    context("Temporal family") {
        test("DateTime without tz accepts TIMESTAMP types") {
            assertCompat(NeutralType.DateTime(), col(Types.TIMESTAMP), true)
            assertCompat(NeutralType.DateTime(), col(Types.TIMESTAMP_WITH_TIMEZONE), true)
        }
        test("DateTime with tz still accepts both") {
            assertCompat(NeutralType.DateTime(timezone = true), col(Types.TIMESTAMP), true)
            assertCompat(NeutralType.DateTime(timezone = true), col(Types.TIMESTAMP_WITH_TIMEZONE), true)
        }
        test("DateTime rejects DATE/TIME") {
            assertCompat(NeutralType.DateTime(), col(Types.DATE), false)
        }
        test("Date accepts DATE only") {
            assertCompat(NeutralType.Date, col(Types.DATE), true)
            assertCompat(NeutralType.Date, col(Types.TIMESTAMP), false)
        }
        test("Time accepts TIME with/without tz") {
            assertCompat(NeutralType.Time, col(Types.TIME), true)
            assertCompat(NeutralType.Time, col(Types.TIME_WITH_TIMEZONE), true)
            assertCompat(NeutralType.Time, col(Types.TIMESTAMP), false)
        }
    }

    context("Structured family") {
        test("Uuid accepts UUID sqlTypeName or CHAR/VARCHAR") {
            assertCompat(NeutralType.Uuid, col(Types.OTHER, "UUID"), true)
            assertCompat(NeutralType.Uuid, col(Types.CHAR), true)
            assertCompat(NeutralType.Uuid, col(Types.VARCHAR), true)
            assertCompat(NeutralType.Uuid, col(Types.INTEGER), false)
        }
        test("Json accepts JSON/JSONB sqlTypeName or text-compatible jdbc types") {
            assertCompat(NeutralType.Json, col(Types.OTHER, "JSON"), true)
            assertCompat(NeutralType.Json, col(Types.OTHER, "JSONB"), true)
            assertCompat(NeutralType.Json, col(Types.VARCHAR), true)
            assertCompat(NeutralType.Json, col(Types.LONGVARCHAR), true)
            assertCompat(NeutralType.Json, col(Types.CLOB), true)
            assertCompat(NeutralType.Json, col(Types.INTEGER), false)
        }
        test("Xml accepts SQLXML, XML sqlTypeName, or text-compatible jdbc types") {
            assertCompat(NeutralType.Xml, col(Types.SQLXML), true)
            assertCompat(NeutralType.Xml, col(Types.OTHER, "XML"), true)
            assertCompat(NeutralType.Xml, col(Types.VARCHAR), true)
            assertCompat(NeutralType.Xml, col(Types.LONGVARCHAR), true)
            assertCompat(NeutralType.Xml, col(Types.CLOB), true)
            assertCompat(NeutralType.Xml, col(Types.INTEGER), false)
        }
        test("Binary accepts binary jdbc types") {
            for (t in listOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)) {
                assertCompat(NeutralType.Binary, col(t), true)
            }
            assertCompat(NeutralType.Binary, col(Types.VARCHAR), false)
        }
    }

    context("Enum") {
        test("matches ENUM sqlTypeName") {
            assertCompat(NeutralType.Enum(), col(Types.OTHER, "ENUM"), true)
        }
        test("matches CHAR/VARCHAR/NCHAR/NVARCHAR") {
            assertCompat(NeutralType.Enum(), col(Types.CHAR), true)
            assertCompat(NeutralType.Enum(), col(Types.VARCHAR), true)
            assertCompat(NeutralType.Enum(), col(Types.NCHAR), true)
            assertCompat(NeutralType.Enum(), col(Types.NVARCHAR), true)
        }
        test("Types.OTHER with custom enum name matches refType") {
            val type = NeutralType.Enum(refType = "color_t")
            assertCompat(type, col(Types.OTHER, "color_t"), true)
        }
        test("Types.OTHER without refType matches any non-well-known sqlTypeName") {
            val type = NeutralType.Enum()
            assertCompat(type, col(Types.OTHER, "my_status"), true)
        }
        test("Types.OTHER rejects well-known names like UUID/JSON") {
            val type = NeutralType.Enum()
            assertCompat(type, col(Types.OTHER, "UUID"), false)
            assertCompat(type, col(Types.OTHER, "JSONB"), false)
        }
        test("Types.OTHER with empty sqlTypeName is rejected") {
            assertCompat(NeutralType.Enum(), col(Types.OTHER, ""), false)
            assertCompat(NeutralType.Enum(), col(Types.OTHER, null), false)
        }
        test("Types.OTHER with refType but mismatched sqlTypeName is rejected") {
            val type = NeutralType.Enum(refType = "color_t")
            assertCompat(type, col(Types.OTHER, "size_t"), false)
        }
    }

    context("Array") {
        test("matches Types.ARRAY") {
            assertCompat(NeutralType.Array("int"), col(Types.ARRAY), true)
        }
        test("matches sqlTypeName ending in []") {
            assertCompat(NeutralType.Array("int"), col(Types.OTHER, "INT4[]"), true)
        }
        test("rejects scalar") {
            assertCompat(NeutralType.Array("int"), col(Types.INTEGER), false)
        }
    }

    context("Geometry") {
        test("VA1d: compatible with a geometry target (WKB round-trip)") {
            assertCompat(NeutralType.Geometry(), col(Types.OTHER, "GEOMETRY"), true)
            assertCompat(NeutralType.Geometry(), col(Types.BINARY, "POINT"), true) // subtype, case-insensitive
            assertCompat(NeutralType.Geometry(), col(Types.OTHER, "geometry"), true)
        }
        test("VA1d: NOT compatible with non-geometry targets (no longer always-true; WKB→text would be binary garbage)") {
            // text targets are NOT compatible: the value path is WKB byte[], not WKT
            assertCompat(NeutralType.Geometry(), col(Types.VARCHAR), false)
            assertCompat(NeutralType.Geometry(), col(Types.LONGVARCHAR), false)
            assertCompat(NeutralType.Geometry(), col(Types.OTHER, "TEXT"), false)
            // and other unrelated targets
            assertCompat(NeutralType.Geometry(), col(Types.INTEGER), false)
            assertCompat(NeutralType.Geometry(), col(Types.BINARY), false) // plain binary, not geometry
            assertCompat(NeutralType.Geometry(), col(Types.TIMESTAMP), false)
        }
    }

    context("describe") {
        test("returns a non-empty label for every NeutralType variant") {
            val variants = listOf(
                NeutralType.Identifier() to "identifier-compatible integer",
                NeutralType.Text() to "text-compatible type",
                NeutralType.Char(4) to "fixed-width char",
                NeutralType.Integer to "INTEGER",
                NeutralType.SmallInt to "SMALLINT",
                NeutralType.BigInteger to "BIGINT",
                NeutralType.Float(FloatPrecision.SINGLE) to "single-precision float",
                NeutralType.Float(FloatPrecision.DOUBLE) to "double-precision float",
                NeutralType.Decimal(10, 2) to "DECIMAL/NUMERIC",
                NeutralType.BooleanType to "BOOLEAN",
                NeutralType.DateTime() to "TIMESTAMP",
                NeutralType.Date to "DATE",
                NeutralType.Time to "TIME",
                NeutralType.Uuid to "UUID-compatible type",
                NeutralType.Json to "JSON-compatible type",
                NeutralType.Xml to "XML-compatible type",
                NeutralType.Binary to "binary/blob type",
                NeutralType.Email to "text-compatible type",
                NeutralType.Enum() to "enum/text-compatible type",
                NeutralType.Array("int") to "array-compatible type",
                NeutralType.Geometry() to "geometry-compatible type",
            )
            for ((type, label) in variants) {
                ImportTypeCompatibility.describe(type) shouldBe label
            }
        }
    }

    context("isMultiBit") {
        test("returns false when not BIT") {
            ImportTypeCompatibility.isMultiBit("VARCHAR(8)") shouldBe false
        }
        test("returns false for plain BIT without parens") {
            ImportTypeCompatibility.isMultiBit("BIT") shouldBe false
        }
        test("returns false for BIT(1)") {
            ImportTypeCompatibility.isMultiBit("BIT(1)") shouldBe false
        }
        test("returns true for BIT(2) and higher") {
            ImportTypeCompatibility.isMultiBit("BIT(2)") shouldBe true
            ImportTypeCompatibility.isMultiBit("BIT(8)") shouldBe true
        }
        test("returns false for BIT() with empty parens or invalid number") {
            ImportTypeCompatibility.isMultiBit("BIT()") shouldBe false
            ImportTypeCompatibility.isMultiBit("BIT(abc)") shouldBe false
        }
    }
})
