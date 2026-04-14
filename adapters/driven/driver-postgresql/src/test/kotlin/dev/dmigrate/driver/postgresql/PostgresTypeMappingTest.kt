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

    test("geometry → Geometry with PostGIS note") {
        val result = PostgresTypeMapping.mapUserDefined("geometry", "t", "c")
        result.type shouldBe NeutralType.Geometry()
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R401"
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

    // ── More column type edge cases ──────────────

    test("decimal maps same as numeric") { map("decimal", numP = 8, numS = 4).type shouldBe NeutralType.Decimal(8, 4) }
    test("time with time zone") { map("time with time zone").type shouldBe NeutralType.Time }
    test("char default length 1") { map("character").type shouldBe NeutralType.Char(length = 1) }
    test("user-defined enum column") { map("user-defined", udtName = "status_type").type shouldBe NeutralType.Enum(refType = "status_type") }
    test("smallint serial PK → Identifier") {
        map("smallint", udtName = "int2", isPk = true, colDefault = "nextval('t_id_seq')").type shouldBe
            NeutralType.Identifier(autoIncrement = true)
    }

    test("unknown udt serial PK → Identifier (else branch)") {
        map("numeric", udtName = "numeric", isPk = true, isIdentity = true).type shouldBe
            NeutralType.Identifier(autoIncrement = true)
    }

    // ── More default parsing ───────────────────

    test("parseDefault double") { PostgresTypeMapping.parseDefault("3.14") shouldBe DefaultValue.NumberLiteral(3.14) }
    test("parseDefault cast number") { PostgresTypeMapping.parseDefault("0::integer") shouldBe DefaultValue.NumberLiteral(0L) }
    test("parseDefault cast double") { PostgresTypeMapping.parseDefault("1.5::numeric") shouldBe DefaultValue.NumberLiteral(1.5) }
    test("parseDefault cast string literal") { PostgresTypeMapping.parseDefault("'active'::varchar") shouldBe DefaultValue.StringLiteral("active") }
    test("parseDefault cast function") { PostgresTypeMapping.parseDefault("clock_timestamp()::timestamptz") shouldBe DefaultValue.FunctionCall("clock_timestamp()::timestamptz") }
    test("parseDefault plain string") { PostgresTypeMapping.parseDefault("'hello'") shouldBe DefaultValue.StringLiteral("hello") }
    test("parseDefault unknown expression") { PostgresTypeMapping.parseDefault("random()") shouldBe DefaultValue.FunctionCall("random()") }

    // ── Array element types ────────────────────

    test("array element int8") { PostgresTypeMapping.mapArrayElementType("int8") shouldBe "biginteger" }
    test("array element int2") { PostgresTypeMapping.mapArrayElementType("int2") shouldBe "integer" }
    test("array element json") { PostgresTypeMapping.mapArrayElementType("json") shouldBe "json" }
    test("array element uuid") { PostgresTypeMapping.mapArrayElementType("uuid") shouldBe "uuid" }
    test("array element bool") { PostgresTypeMapping.mapArrayElementType("bool") shouldBe "boolean" }
    test("array element float4") { PostgresTypeMapping.mapArrayElementType("float4") shouldBe "float" }
    test("array element float8") { PostgresTypeMapping.mapArrayElementType("float8") shouldBe "float" }
    test("array element numeric") { PostgresTypeMapping.mapArrayElementType("numeric") shouldBe "decimal" }
    test("array element jsonb") { PostgresTypeMapping.mapArrayElementType("jsonb") shouldBe "json" }
    test("array element varchar") { PostgresTypeMapping.mapArrayElementType("varchar") shouldBe "text" }
    test("array element bpchar") { PostgresTypeMapping.mapArrayElementType("bpchar") shouldBe "text" }
    test("array element unknown") { PostgresTypeMapping.mapArrayElementType("hstore") shouldBe "text" }

    // ── Composite field mapping ─────────────────

    test("composite integer") { PostgresTypeMapping.mapCompositeFieldType("integer") shouldBe NeutralType.Integer }
    test("composite int4") { PostgresTypeMapping.mapCompositeFieldType("int4") shouldBe NeutralType.Integer }
    test("composite bigint") { PostgresTypeMapping.mapCompositeFieldType("bigint") shouldBe NeutralType.BigInteger }
    test("composite int8") { PostgresTypeMapping.mapCompositeFieldType("int8") shouldBe NeutralType.BigInteger }
    test("composite smallint") { PostgresTypeMapping.mapCompositeFieldType("smallint") shouldBe NeutralType.SmallInt }
    test("composite text") { PostgresTypeMapping.mapCompositeFieldType("text") shouldBe NeutralType.Text() }
    test("composite boolean") { PostgresTypeMapping.mapCompositeFieldType("boolean") shouldBe NeutralType.BooleanType }
    test("composite bool") { PostgresTypeMapping.mapCompositeFieldType("bool") shouldBe NeutralType.BooleanType }
    test("composite varchar(100)") { PostgresTypeMapping.mapCompositeFieldType("character varying(100)") shouldBe NeutralType.Text(maxLength = 100) }
    test("composite varchar no length") { PostgresTypeMapping.mapCompositeFieldType("varchar") shouldBe NeutralType.Text(maxLength = null) }
    test("composite numeric(10,2)") { PostgresTypeMapping.mapCompositeFieldType("numeric(10,2)") shouldBe NeutralType.Decimal(10, 2) }
    test("composite numeric no precision") { PostgresTypeMapping.mapCompositeFieldType("numeric") shouldBe NeutralType.Float() }
    test("composite uuid") { PostgresTypeMapping.mapCompositeFieldType("uuid") shouldBe NeutralType.Uuid }
    test("composite json") { PostgresTypeMapping.mapCompositeFieldType("json") shouldBe NeutralType.Json }
    test("composite jsonb") { PostgresTypeMapping.mapCompositeFieldType("jsonb") shouldBe NeutralType.Json }
    test("composite bytea") { PostgresTypeMapping.mapCompositeFieldType("bytea") shouldBe NeutralType.Binary }
    test("composite date") { PostgresTypeMapping.mapCompositeFieldType("date") shouldBe NeutralType.Date }
    test("composite time") { PostgresTypeMapping.mapCompositeFieldType("time") shouldBe NeutralType.Time }
    test("composite time with tz") { PostgresTypeMapping.mapCompositeFieldType("time with time zone") shouldBe NeutralType.Time }
    test("composite timestamp") { PostgresTypeMapping.mapCompositeFieldType("timestamp without time zone") shouldBe NeutralType.DateTime(timezone = false) }
    test("composite timestamptz") { PostgresTypeMapping.mapCompositeFieldType("timestamp with time zone") shouldBe NeutralType.DateTime(timezone = true) }
    test("composite unknown") { PostgresTypeMapping.mapCompositeFieldType("hstore") shouldBe NeutralType.Text() }

    // ── Param type mapping ──────────────────────

    test("param int4") { PostgresTypeMapping.mapParamType("int4") shouldBe "integer" }
    test("param int2") { PostgresTypeMapping.mapParamType("int2") shouldBe "smallint" }
    test("param int8") { PostgresTypeMapping.mapParamType("int8") shouldBe "biginteger" }
    test("param text") { PostgresTypeMapping.mapParamType("text") shouldBe "text" }
    test("param varchar") { PostgresTypeMapping.mapParamType("varchar") shouldBe "text" }
    test("param bpchar") { PostgresTypeMapping.mapParamType("bpchar") shouldBe "text" }
    test("param bool") { PostgresTypeMapping.mapParamType("bool") shouldBe "boolean" }
    test("param float4") { PostgresTypeMapping.mapParamType("float4") shouldBe "float" }
    test("param float8") { PostgresTypeMapping.mapParamType("float8") shouldBe "float" }
    test("param numeric") { PostgresTypeMapping.mapParamType("numeric") shouldBe "decimal" }
    test("param uuid") { PostgresTypeMapping.mapParamType("uuid") shouldBe "uuid" }
    test("param json") { PostgresTypeMapping.mapParamType("json") shouldBe "json" }
    test("param jsonb") { PostgresTypeMapping.mapParamType("jsonb") shouldBe "json" }
    test("param bytea") { PostgresTypeMapping.mapParamType("bytea") shouldBe "binary" }
    test("param void") { PostgresTypeMapping.mapParamType("void") shouldBe "void" }
    test("param unknown passes through") { PostgresTypeMapping.mapParamType("hstore") shouldBe "hstore" }

    // ── isSerialDefault ─────────────────────────

    test("isSerialDefault true") { PostgresTypeMapping.isSerialDefault("nextval('seq'::regclass)") shouldBe true }
    test("isSerialDefault false for null") { PostgresTypeMapping.isSerialDefault(null) shouldBe false }
    test("isSerialDefault false for literal") { PostgresTypeMapping.isSerialDefault("42") shouldBe false }
})
