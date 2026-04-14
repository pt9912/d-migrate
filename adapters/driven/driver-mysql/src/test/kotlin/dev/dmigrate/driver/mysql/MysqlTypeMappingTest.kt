package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class MysqlTypeMappingTest : FunSpec({

    fun map(dataType: String, columnType: String = dataType, isAI: Boolean = false,
            charMaxLen: Int? = null, numP: Int? = null, numS: Int? = null) =
        MysqlTypeMapping.mapColumn(dataType, columnType, isAI, charMaxLen, numP, numS, "t", "c")

    // ── Basic types ─────────────────────────────

    test("int") { map("int").type shouldBe NeutralType.Integer }
    test("bigint") { map("bigint").type shouldBe NeutralType.BigInteger }
    test("smallint") { map("smallint").type shouldBe NeutralType.SmallInt }
    test("mediumint → Integer") { map("mediumint").type shouldBe NeutralType.Integer }
    test("tinyint(1) → Boolean") { map("tinyint", "tinyint(1)").type shouldBe NeutralType.BooleanType }
    test("tinyint(4) → SmallInt") { map("tinyint", "tinyint(4)").type shouldBe NeutralType.SmallInt }
    test("varchar(100)") { map("varchar", charMaxLen = 100).type shouldBe NeutralType.Text(maxLength = 100) }
    test("text") { map("text").type shouldBe NeutralType.Text() }
    test("longtext") { map("longtext").type shouldBe NeutralType.Text() }
    test("json") { map("json").type shouldBe NeutralType.Json }
    test("blob") { map("blob").type shouldBe NeutralType.Binary }
    test("binary") { map("binary").type shouldBe NeutralType.Binary }
    test("date") { map("date").type shouldBe NeutralType.Date }
    test("time") { map("time").type shouldBe NeutralType.Time }
    test("datetime") { map("datetime").type shouldBe NeutralType.DateTime() }
    test("timestamp") { map("timestamp").type shouldBe NeutralType.DateTime() }
    test("float") { map("float").type shouldBe NeutralType.Float(FloatPrecision.SINGLE) }
    test("double") { map("double").type shouldBe NeutralType.Float(FloatPrecision.DOUBLE) }
    test("boolean") { map("boolean").type shouldBe NeutralType.BooleanType }

    test("char(36) → Uuid with note") {
        val result = map("char", charMaxLen = 36)
        result.type shouldBe NeutralType.Uuid
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R310"
    }

    test("char(10) → Char") {
        map("char", charMaxLen = 10).type shouldBe NeutralType.Char(length = 10)
    }

    test("decimal(10,2)") {
        map("decimal", numP = 10, numS = 2).type shouldBe NeutralType.Decimal(10, 2)
    }

    // ── AUTO_INCREMENT ──────────────────────────

    test("int AUTO_INCREMENT → Identifier") {
        map("int", isAI = true).type shouldBe NeutralType.Identifier(autoIncrement = true)
    }

    test("bigint AUTO_INCREMENT → BigInteger with R300 note") {
        val result = map("bigint", isAI = true)
        result.type shouldBe NeutralType.BigInteger
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R300"
    }

    test("smallint AUTO_INCREMENT → Identifier") {
        map("smallint", isAI = true).type shouldBe NeutralType.Identifier(autoIncrement = true)
    }

    // ── ENUM ────────────────────────────────────

    test("enum values extracted") {
        val result = map("enum", "enum('a','b','c')")
        val enumType = result.type as NeutralType.Enum
        enumType.values shouldBe listOf("a", "b", "c")
    }

    // ── SET → Note ──────────────────────────────

    test("SET → Text with ACTION_REQUIRED") {
        val result = map("set", "set('x','y')")
        result.type shouldBe NeutralType.Text()
        result.note.shouldNotBeNull()
        result.note!!.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
        result.note!!.code shouldBe "R320"
    }

    // ── Spatial ─────────────────────────────────

    test("point → Geometry") {
        val result = map("point")
        (result.type is NeutralType.Geometry) shouldBe true
    }

    test("geometry → Geometry") {
        val result = map("geometry")
        (result.type is NeutralType.Geometry) shouldBe true
    }

    // ── Unknown ─────────────────────────────────

    test("unknown type → Text with warning") {
        val result = map("year")
        result.type shouldBe NeutralType.Text()
        result.note.shouldNotBeNull()
        result.note!!.severity shouldBe SchemaReadSeverity.WARNING
    }

    // ── extractEnumValues ───────────────────────

    test("extractEnumValues parses values") {
        MysqlTypeMapping.extractEnumValues("enum('pending','shipped')") shouldBe listOf("pending", "shipped")
    }

    test("extractEnumValues empty") {
        MysqlTypeMapping.extractEnumValues("varchar(100)") shouldBe emptyList()
    }

    // ── Defaults ────────────────────────────────

    test("parseDefault null") { MysqlTypeMapping.parseDefault(null, NeutralType.Text()).shouldBeNull() }
    test("parseDefault NULL") { MysqlTypeMapping.parseDefault("NULL", NeutralType.Text()).shouldBeNull() }
    test("parseDefault CURRENT_TIMESTAMP") { MysqlTypeMapping.parseDefault("CURRENT_TIMESTAMP", NeutralType.DateTime()) shouldBe DefaultValue.FunctionCall("current_timestamp") }
    test("parseDefault boolean 1") { MysqlTypeMapping.parseDefault("1", NeutralType.BooleanType) shouldBe DefaultValue.BooleanLiteral(true) }
    test("parseDefault boolean 0") { MysqlTypeMapping.parseDefault("0", NeutralType.BooleanType) shouldBe DefaultValue.BooleanLiteral(false) }
    test("parseDefault string") { MysqlTypeMapping.parseDefault("'hello'", NeutralType.Text()) shouldBe DefaultValue.StringLiteral("hello") }
    test("parseDefault integer") { MysqlTypeMapping.parseDefault("42", NeutralType.Integer) shouldBe DefaultValue.NumberLiteral(42L) }

    // ── Param type mapping ──────────────────────

    test("param int") { MysqlTypeMapping.mapParamType("int") shouldBe "integer" }
    test("param bigint") { MysqlTypeMapping.mapParamType("bigint") shouldBe "biginteger" }
    test("param varchar") { MysqlTypeMapping.mapParamType("varchar") shouldBe "text" }
    test("param json") { MysqlTypeMapping.mapParamType("json") shouldBe "json" }
    test("param unknown passes through") { MysqlTypeMapping.mapParamType("year") shouldBe "year" }
})
