package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

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

    test("AUTOINCREMENT → Identifier") {
        map("INTEGER", isAI = true).type shouldBe NeutralType.Identifier(autoIncrement = true)
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
    test("parseDefault CURRENT_TIMESTAMP") { SqliteTypeMapping.parseDefault("CURRENT_TIMESTAMP") shouldBe DefaultValue.FunctionCall("current_timestamp") }
    test("parseDefault datetime()") { SqliteTypeMapping.parseDefault("(datetime('now'))") shouldBe DefaultValue.FunctionCall("current_timestamp") }

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

    // ── View query extraction ───────────────────

    test("extractViewQuery") {
        SqliteTypeMapping.extractViewQuery("CREATE VIEW v AS SELECT * FROM t") shouldBe "SELECT * FROM t"
        SqliteTypeMapping.extractViewQuery("CREATE VIEW v").shouldBeNull()
    }

    // ── Trigger SQL parsing ─────────────────────

    test("parseTriggerSql AFTER INSERT") {
        val result = SqliteTypeMapping.parseTriggerSql(
            "CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END", "trg")
        result.timing shouldBe dev.dmigrate.core.model.TriggerTiming.AFTER
        result.event shouldBe dev.dmigrate.core.model.TriggerEvent.INSERT
        result.body shouldBe "SELECT 1;"
        result.notes shouldBe emptyList()
    }

    test("parseTriggerSql BEFORE UPDATE") {
        val result = SqliteTypeMapping.parseTriggerSql(
            "CREATE TRIGGER trg BEFORE UPDATE ON t BEGIN UPDATE x SET a=1; END", "trg")
        result.timing shouldBe dev.dmigrate.core.model.TriggerTiming.BEFORE
        result.event shouldBe dev.dmigrate.core.model.TriggerEvent.UPDATE
    }

    test("parseTriggerSql INSTEAD OF DELETE") {
        val result = SqliteTypeMapping.parseTriggerSql(
            "CREATE TRIGGER trg INSTEAD OF DELETE ON v BEGIN SELECT 1; END", "trg")
        result.timing shouldBe dev.dmigrate.core.model.TriggerTiming.INSTEAD_OF
        result.event shouldBe dev.dmigrate.core.model.TriggerEvent.DELETE
    }

    test("parseTriggerSql unknown timing/event produces notes") {
        val result = SqliteTypeMapping.parseTriggerSql("CREATE TRIGGER trg ON t BEGIN END", "trg")
        result.notes.size shouldBe 2
    }
})
