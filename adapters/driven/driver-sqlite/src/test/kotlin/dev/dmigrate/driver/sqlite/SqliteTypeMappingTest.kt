package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SqliteAutoincrementReverse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

class SqliteTypeMappingTest : FunSpec({

    fun map(rawType: String, isAI: Boolean = false) =
        SqliteTypeMapping.mapColumn(rawType, isAI, "t", "c")

    // ── Basic types ─────────────────────────────

    test("INTEGER") { map("INTEGER").type shouldBe NeutralType.Integer }
    test("INT") { map("INT").type shouldBe NeutralType.Integer }
    test("BIGINT") { map("BIGINT").type shouldBe NeutralType.BigInteger }
    test("SMALLINT") { map("SMALLINT").type shouldBe NeutralType.SmallInt }
    test("TEXT") { map("TEXT").type shouldBe NeutralType.Text() }
    test("BLOB") { map("BLOB").type shouldBe NeutralType.Binary }
    test("REAL") { map("REAL").type shouldBe NeutralType.Float() }
    test("DOUBLE") { map("DOUBLE").type shouldBe NeutralType.Float() }
    test("FLOAT") { map("FLOAT").type shouldBe NeutralType.Float() }
    test("BOOLEAN") { map("BOOLEAN").type shouldBe NeutralType.BooleanType }
    test("TINYINT(1)") { map("TINYINT(1)").type shouldBe NeutralType.BooleanType }
    test("DATE") { map("DATE").type shouldBe NeutralType.Date }
    test("TIME") { map("TIME").type shouldBe NeutralType.Time }
    test("DATETIME") { map("DATETIME").type shouldBe NeutralType.DateTime() }
    test("TIMESTAMP") { map("TIMESTAMP").type shouldBe NeutralType.DateTime() }
    test("UUID") { map("UUID").type shouldBe NeutralType.Uuid }
    test("JSON") { map("JSON").type shouldBe NeutralType.Json }
    test("JSONB") { map("JSONB").type shouldBe NeutralType.Json }

    test("VARCHAR(100)") { map("VARCHAR(100)").type shouldBe NeutralType.Text(maxLength = 100) }
    test("CHARACTER VARYING(50)") { map("CHARACTER VARYING(50)").type shouldBe NeutralType.Text(maxLength = 50) }
    test("CHAR(5)") { map("CHAR(5)").type shouldBe NeutralType.Char(length = 5) }
    test("DECIMAL(10,2)") { map("DECIMAL(10,2)").type shouldBe NeutralType.Decimal(10, 2) }
    test("NUMERIC(8,4)") { map("NUMERIC(8,4)").type shouldBe NeutralType.Decimal(8, 4) }
    test("NUMERIC without precision") { map("NUMERIC").type shouldBe NeutralType.Float() }

    // ── AUTOINCREMENT ───────────────────────────

    test("AUTOINCREMENT carries R202 64-bit narrowing note") {
        val r = map("INTEGER", isAI = true)
        r.note.shouldNotBeNull()
        r.note?.code shouldBe "R202"
        r.note?.severity shouldBe SchemaReadSeverity.INFO
        r.note?.objectName shouldBe "t.c"
    }

    test("AUTOINCREMENT → Identifier") {
        map("INTEGER", isAI = true).type shouldBe NeutralType.Identifier(autoIncrement = true)
    }

    // ── reverse-preferences: AUTOINCREMENT width preference ──
    test("AUTOINCREMENT under 64-bit preference → biginteger + Identity(legacy) + R204") {
        val r = SqliteTypeMapping.mapColumn(
            "INTEGER", isAutoIncrement = true, "t", "c",
            SqliteAutoincrementReverse.BIGINTEGER_IDENTITY,
        )
        r.type shouldBe NeutralType.BigInteger
        // legacySerialSyntax = true mirrors the MySQL bigint-auto_increment reverse → PG BIGSERIAL
        r.generation shouldBe ColumnGeneration.Identity(legacySerialSyntax = true)
        r.note?.code shouldBe "R204"
        r.note?.severity shouldBe SchemaReadSeverity.INFO
    }

    test("AUTOINCREMENT explicit IDENTIFIER preference stays 32-bit (canonicaliser-safe default)") {
        val r = SqliteTypeMapping.mapColumn(
            "INTEGER", isAutoIncrement = true, "t", "c",
            SqliteAutoincrementReverse.IDENTIFIER,
        )
        r.type shouldBe NeutralType.Identifier(autoIncrement = true)
        r.generation.shouldBeNull()
        r.note?.code shouldBe "R202"
    }

    test("R202 hint names the width flag/config-key (discoverability F1)") {
        val hint = map("INTEGER", isAI = true).note?.hint
        hint.shouldNotBeNull()
        hint shouldContain "--sqlite-autoincrement-width 64"
        hint shouldContain "reverse.sqlite.autoincrement_width"
    }

    // ── Geometry ────────────────────────────────

    test("POINT → Geometry with note") {
        val result = map("POINT")
        (result.type is NeutralType.Geometry) shouldBe true
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R220"
    }

    test("GEOMETRY → Geometry") {
        (map("GEOMETRY").type is NeutralType.Geometry) shouldBe true
    }

    test("POLYGON → Geometry") {
        (map("POLYGON").type is NeutralType.Geometry) shouldBe true
    }

    // ── Untyped / Unknown ───────────────────────

    test("empty type → Text with info note") {
        val result = map("")
        result.type shouldBe NeutralType.Text()
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R200"
    }

    test("unknown type → Text with warning") {
        val result = map("CUSTOMTYPE")
        result.type shouldBe NeutralType.Text()
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R201"
        result.note!!.severity shouldBe SchemaReadSeverity.WARNING
    }

    // ── Defaults ────────────────────────────────

    test("parseDefault null") { SqliteTypeMapping.parseDefault(null).shouldBeNull() }
    test("parseDefault NULL") { SqliteTypeMapping.parseDefault("NULL").shouldBeNull() }
    test("parseDefault TRUE") { SqliteTypeMapping.parseDefault("TRUE") shouldBe DefaultValue.BooleanLiteral(true) }
    test("parseDefault FALSE") { SqliteTypeMapping.parseDefault("FALSE") shouldBe DefaultValue.BooleanLiteral(false) }
    test("parseDefault string") { SqliteTypeMapping.parseDefault("'hello'") shouldBe DefaultValue.StringLiteral("hello") }
    test("parseDefault integer") { SqliteTypeMapping.parseDefault("42") shouldBe DefaultValue.NumberLiteral(42L) }
    test("parseDefault double") { SqliteTypeMapping.parseDefault("3.14") shouldBe DefaultValue.NumberLiteral(3.14) }
    test("parseDefault CURRENT_TIMESTAMP") {
        SqliteTypeMapping.parseDefault("CURRENT_TIMESTAMP") shouldBe
            DefaultValue.FunctionCall("current_timestamp")
    }
    test("parseDefault datetime()") {
        SqliteTypeMapping.parseDefault("(datetime('now'))") shouldBe
            DefaultValue.FunctionCall("current_timestamp")
    }

    // ── Helpers ─────────────────────────────────

    test("extractMaxLength") {
        SqliteTypeMapping.extractMaxLength("VARCHAR(100)") shouldBe 100
        SqliteTypeMapping.extractMaxLength("TEXT").shouldBeNull()
    }

    test("extractPrecisionScale") {
        SqliteTypeMapping.extractPrecisionScale("DECIMAL(10,2)") shouldBe (10 to 2)
        SqliteTypeMapping.extractPrecisionScale("INTEGER") shouldBe (null to null)
    }

    // ── Table classification ────────────────────

    test("isVirtualTable") {
        SqliteTypeMapping.isVirtualTable("CREATE VIRTUAL TABLE x USING fts5(y)") shouldBe true
        SqliteTypeMapping.isVirtualTable("CREATE TABLE x (id INTEGER)") shouldBe false
    }

    test("hasAutoincrement") {
        SqliteTypeMapping.hasAutoincrement("CREATE TABLE x (id INTEGER PRIMARY KEY AUTOINCREMENT)") shouldBe true
        SqliteTypeMapping.hasAutoincrement("CREATE TABLE x (id INTEGER PRIMARY KEY)") shouldBe false
    }

    test("hasWithoutRowid") {
        SqliteTypeMapping.hasWithoutRowid("CREATE TABLE x (k TEXT PRIMARY KEY) WITHOUT ROWID") shouldBe true
        SqliteTypeMapping.hasWithoutRowid("CREATE TABLE x (id INTEGER PRIMARY KEY)") shouldBe false
    }

    test("isSpatiaLiteMetaTable") {
        SqliteTypeMapping.isSpatiaLiteMetaTable("geometry_columns") shouldBe true
        SqliteTypeMapping.isSpatiaLiteMetaTable("spatial_ref_sys") shouldBe true
        SqliteTypeMapping.isSpatiaLiteMetaTable("users") shouldBe false
    }

    // CHECK-constraint extraction lives in [SqliteCheckConstraintScannerTest]
    // since the scanner moved to its own object.

    // ── View query extraction ───────────────────

    test("extractViewQuery") {
        SqliteTypeMapping.extractViewQuery("CREATE VIEW v AS SELECT * FROM t") shouldBe "SELECT * FROM t"
        SqliteTypeMapping.extractViewQuery("CREATE VIEW v").shouldBeNull()
    }
})
