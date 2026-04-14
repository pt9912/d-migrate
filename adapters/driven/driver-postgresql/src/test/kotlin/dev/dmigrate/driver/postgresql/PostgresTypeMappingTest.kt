package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class PostgresTypeMappingTest : FunSpec({

    fun map(dataType: String, udtName: String = dataType, isPk: Boolean = false,
            isIdentity: Boolean = false, colDefault: String? = null,
            charMaxLen: Int? = null, numP: Int? = null, numS: Int? = null) =
        PostgresTypeMapping.mapColumn(dataType, udtName, isPk, isIdentity, colDefault,
            charMaxLen, numP, numS, "t", "c")

    // ── Basic types ─────────────────────────────

    test("integer") { map("integer").type shouldBe NeutralType.Integer }
    test("bigint") { map("bigint").type shouldBe NeutralType.BigInteger }
    test("smallint") { map("smallint").type shouldBe NeutralType.SmallInt }
    test("boolean") { map("boolean").type shouldBe NeutralType.BooleanType }
    test("text") { map("text").type shouldBe NeutralType.Text() }
    test("uuid") { map("uuid").type shouldBe NeutralType.Uuid }
    test("jsonb") { map("jsonb").type shouldBe NeutralType.Json }
    test("json") { map("json").type shouldBe NeutralType.Json }
    test("xml") { map("xml").type shouldBe NeutralType.Xml }
    test("bytea") { map("bytea").type shouldBe NeutralType.Binary }
    test("date") { map("date").type shouldBe NeutralType.Date }
    test("time") { map("time without time zone").type shouldBe NeutralType.Time }
    test("real") { map("real").type shouldBe NeutralType.Float(FloatPrecision.SINGLE) }
    test("double precision") { map("double precision").type shouldBe NeutralType.Float(FloatPrecision.DOUBLE) }

    test("varchar with length") {
        map("character varying", charMaxLen = 100).type shouldBe NeutralType.Text(maxLength = 100)
    }

    test("char with length") {
        map("character", charMaxLen = 5).type shouldBe NeutralType.Char(length = 5)
    }

    test("numeric with precision/scale") {
        map("numeric", numP = 10, numS = 2).type shouldBe NeutralType.Decimal(10, 2)
    }

    test("numeric without precision falls back to float") {
        map("numeric").type shouldBe NeutralType.Float()
    }

    test("timestamp without tz") {
        map("timestamp without time zone").type shouldBe NeutralType.DateTime(timezone = false)
    }

    test("timestamp with tz") {
        map("timestamp with time zone").type shouldBe NeutralType.DateTime(timezone = true)
    }

    // ── Serial/Identity ─────────────────────────

    test("serial PK → Identifier") {
        map("integer", udtName = "int4", isPk = true, colDefault = "nextval('t_id_seq'::regclass)").type shouldBe
            NeutralType.Identifier(autoIncrement = true)
    }

    test("bigserial PK → BigInteger with note") {
        val result = map("bigint", udtName = "int8", isPk = true, colDefault = "nextval('t_id_seq'::regclass)")
        result.type shouldBe NeutralType.BigInteger
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R300"
    }

    test("identity PK integer → Identifier") {
        map("integer", udtName = "int4", isPk = true, isIdentity = true).type shouldBe
            NeutralType.Identifier(autoIncrement = true)
    }

    test("identity PK bigint → BigInteger with note") {
        val result = map("bigint", udtName = "int8", isPk = true, isIdentity = true)
        result.type shouldBe NeutralType.BigInteger
        result.note.shouldNotBeNull()
    }

    // ── Array ───────────────────────────────────

    test("integer array") {
        map("array", udtName = "_int4").type shouldBe NeutralType.Array("integer")
    }

    test("text array") {
        map("array", udtName = "_text").type shouldBe NeutralType.Array("text")
    }

    // ── User-defined ────────────────────────────

    test("geometry → Geometry") {
        PostgresTypeMapping.mapUserDefined("geometry", "t", "c").type shouldBe NeutralType.Geometry()
    }

    test("custom enum → Enum refType") {
        val result = PostgresTypeMapping.mapUserDefined("order_status", "t", "c")
        (result.type as NeutralType.Enum).refType shouldBe "order_status"
    }

    // ── Unknown type ────────────────────────────

    test("unknown type → Text with warning") {
        val result = map("cidr")
        result.type shouldBe NeutralType.Text()
        result.note.shouldNotBeNull()
        result.note!!.severity shouldBe SchemaReadSeverity.WARNING
    }

    // ── Defaults ────────────────────────────────

    test("parseDefault null → null") { PostgresTypeMapping.parseDefault(null).shouldBeNull() }
    test("parseDefault NULL → null") { PostgresTypeMapping.parseDefault("NULL").shouldBeNull() }
    test("parseDefault nextval → null") { PostgresTypeMapping.parseDefault("nextval('seq')").shouldBeNull() }
    test("parseDefault true") { PostgresTypeMapping.parseDefault("true") shouldBe DefaultValue.BooleanLiteral(true) }
    test("parseDefault false") { PostgresTypeMapping.parseDefault("false") shouldBe DefaultValue.BooleanLiteral(false) }
    test("parseDefault CURRENT_TIMESTAMP") { PostgresTypeMapping.parseDefault("CURRENT_TIMESTAMP") shouldBe DefaultValue.FunctionCall("current_timestamp") }
    test("parseDefault now()") { PostgresTypeMapping.parseDefault("now()") shouldBe DefaultValue.FunctionCall("current_timestamp") }
    test("parseDefault gen_random_uuid()") { PostgresTypeMapping.parseDefault("gen_random_uuid()") shouldBe DefaultValue.FunctionCall("gen_uuid") }
    test("parseDefault string literal") { PostgresTypeMapping.parseDefault("'hello'") shouldBe DefaultValue.StringLiteral("hello") }
    test("parseDefault integer") { PostgresTypeMapping.parseDefault("42") shouldBe DefaultValue.NumberLiteral(42L) }
    test("parseDefault cast") { PostgresTypeMapping.parseDefault("'pending'::order_status") shouldBe DefaultValue.StringLiteral("pending") }

    // ── Composite field mapping ─────────────────

    test("composite integer") { PostgresTypeMapping.mapCompositeFieldType("integer") shouldBe NeutralType.Integer }
    test("composite text") { PostgresTypeMapping.mapCompositeFieldType("text") shouldBe NeutralType.Text() }
    test("composite varchar(100)") { PostgresTypeMapping.mapCompositeFieldType("character varying(100)") shouldBe NeutralType.Text(maxLength = 100) }
    test("composite numeric(10,2)") { PostgresTypeMapping.mapCompositeFieldType("numeric(10,2)") shouldBe NeutralType.Decimal(10, 2) }
    test("composite uuid") { PostgresTypeMapping.mapCompositeFieldType("uuid") shouldBe NeutralType.Uuid }
    test("composite timestamptz") { PostgresTypeMapping.mapCompositeFieldType("timestamp with time zone") shouldBe NeutralType.DateTime(timezone = true) }

    // ── Param type mapping ──────────────────────

    test("param int4") { PostgresTypeMapping.mapParamType("int4") shouldBe "integer" }
    test("param int8") { PostgresTypeMapping.mapParamType("int8") shouldBe "biginteger" }
    test("param text") { PostgresTypeMapping.mapParamType("text") shouldBe "text" }
    test("param bool") { PostgresTypeMapping.mapParamType("bool") shouldBe "boolean" }
    test("param unknown passes through") { PostgresTypeMapping.mapParamType("hstore") shouldBe "hstore" }

    // ── isSerialDefault ─────────────────────────

    test("isSerialDefault true") { PostgresTypeMapping.isSerialDefault("nextval('seq'::regclass)") shouldBe true }
    test("isSerialDefault false for null") { PostgresTypeMapping.isSerialDefault(null) shouldBe false }
    test("isSerialDefault false for literal") { PostgresTypeMapping.isSerialDefault("42") shouldBe false }
})
