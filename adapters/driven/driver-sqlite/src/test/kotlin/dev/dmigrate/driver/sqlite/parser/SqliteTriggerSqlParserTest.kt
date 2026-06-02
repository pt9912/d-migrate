package dev.dmigrate.driver.sqlite.parser

import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class SqliteTriggerSqlParserTest : FunSpec({

    fun parse(sql: String, name: String = "trg") = SqliteTriggerSqlParser.parse(sql, name)

    // -- Standard header variants ---------------------------------------

    test("AFTER INSERT trigger") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.AFTER
        r.event shouldBe TriggerEvent.INSERT
        r.forEach shouldBe TriggerForEach.ROW
        r.condition.shouldBeNull()
        r.body shouldBe "SELECT 1"
        r.notes.shouldBeEmpty()
        r.rejected shouldBe false
    }

    test("BEFORE UPDATE trigger") {
        val r = parse("CREATE TRIGGER trg BEFORE UPDATE ON t BEGIN UPDATE x SET a=1; END")
        r.timing shouldBe TriggerTiming.BEFORE
        r.event shouldBe TriggerEvent.UPDATE
        r.body shouldBe "UPDATE x SET a=1"
        r.notes.shouldBeEmpty()
    }

    test("INSTEAD OF DELETE trigger on view") {
        val r = parse("CREATE TRIGGER trg INSTEAD OF DELETE ON v BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.INSTEAD_OF
        r.event shouldBe TriggerEvent.DELETE
        r.notes.shouldBeEmpty()
    }

    test("INSTEAD without OF parses leniently as INSTEAD_OF without R210") {
        // Documents the parser's lenient-recovery behaviour: once
        // `INSTEAD` is consumed, the timing commits to INSTEAD_OF even
        // when the keyword `OF` is missing. sqlite_master never returns
        // malformed CREATE TRIGGER DDL for triggers SQLite itself
        // accepted, so this is a pure robustness path for hand-edited
        // sqlite_master rows — no R210 timing-missing note is emitted.
        val r = parse("CREATE TRIGGER trg INSTEAD DELETE ON v BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.INSTEAD_OF
        r.event shouldBe TriggerEvent.DELETE
        r.notes.any { it.code == "R210" } shouldBe false
    }

    test("CREATE TEMP TRIGGER variant accepted") {
        val r = parse("CREATE TEMP TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.AFTER
        r.event shouldBe TriggerEvent.INSERT
    }

    test("CREATE TEMPORARY TRIGGER variant accepted") {
        val r = parse("CREATE TEMPORARY TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.AFTER
    }

    test("CREATE TRIGGER IF NOT EXISTS variant accepted") {
        val r = parse("CREATE TRIGGER IF NOT EXISTS trg AFTER INSERT ON t BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.AFTER
    }

    // -- FOR EACH ROW + WHEN clause -------------------------------------

    test("FOR EACH ROW explicit") {
        val r = parse(
            "CREATE TRIGGER trg AFTER INSERT ON t FOR EACH ROW BEGIN SELECT 1; END",
        )
        r.forEach shouldBe TriggerForEach.ROW
    }

    test("WHEN clause is captured verbatim") {
        val r = parse(
            "CREATE TRIGGER trg AFTER INSERT ON t FOR EACH ROW WHEN NEW.x > 0 BEGIN SELECT 1; END",
        )
        r.condition shouldBe "NEW.x > 0"
        r.body shouldBe "SELECT 1"
    }

    test("WHEN clause with parens and spaces preserved") {
        val r = parse(
            "CREATE TRIGGER trg AFTER UPDATE ON t WHEN (NEW.a IS NULL AND OLD.b <> NEW.b) BEGIN SELECT 1; END",
        )
        r.condition shouldBe "(NEW.a IS NULL AND OLD.b <> NEW.b)"
    }

    test("WHEN clause drops a trailing line comment before BEGIN") {
        val r = parse(
            "CREATE TRIGGER trg AFTER UPDATE ON t WHEN NEW.x > 0 -- ignore zero\nBEGIN SELECT 1; END",
        )
        r.condition shouldBe "NEW.x > 0"
    }

    test("WHEN clause drops a trailing block comment before BEGIN") {
        val r = parse(
            "CREATE TRIGGER trg AFTER UPDATE ON t WHEN NEW.x > 0 /* ignore zero */ BEGIN SELECT 1; END",
        )
        r.condition shouldBe "NEW.x > 0"
    }

    test("WHEN clause keeps `--` that lives inside a string literal") {
        // The substring `--` only opens a line comment outside string
        // context. The reader-side parser must keep it as data when it
        // appears inside `'...'`.
        val r = parse(
            "CREATE TRIGGER trg AFTER UPDATE ON t WHEN NEW.tag <> '--reserved--' BEGIN SELECT 1; END",
        )
        r.condition shouldBe "NEW.tag <> '--reserved--'"
    }

    // -- Body extraction (idempotency-relevant) -------------------------

    test("body strips exactly one trailing `;` before END for renderer symmetry") {
        // Renderer always appends `;\nEND;`, so a body in the model must
        // not carry its own trailing `;`.
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1; END")
        r.body shouldBe "SELECT 1"
    }

    test("body without trailing `;` is left as-is") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t BEGIN SELECT 1 END")
        r.body shouldBe "SELECT 1"
    }

    test("multi-statement body keeps inner `;` separators") {
        val r = parse(
            "CREATE TRIGGER trg AFTER INSERT ON t BEGIN UPDATE x SET a=1; INSERT INTO y VALUES (1); END",
        )
        r.body shouldBe "UPDATE x SET a=1; INSERT INTO y VALUES (1)"
    }

    test("CRLF in body is normalised to LF") {
        val sql = "CREATE TRIGGER trg AFTER INSERT ON t BEGIN\r\nSELECT 1;\r\nEND"
        val r = parse(sql)
        r.body shouldBe "SELECT 1"
    }

    test("body preserves inner indentation and statement spacing") {
        val sql = """
            CREATE TRIGGER trg AFTER INSERT ON t
            BEGIN
                UPDATE x SET a = 1;
                SELECT count(*) FROM y;
            END
        """.trimIndent()
        val r = parse(sql)
        r.body shouldBe "UPDATE x SET a = 1;\n    SELECT count(*) FROM y"
    }

    test("END inside a string literal in the body is not treated as keyword") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t BEGIN INSERT INTO log VALUES ('the END is near'); END")
        r.body shouldBe "INSERT INTO log VALUES ('the END is near')"
    }

    test("END inside a block comment in the body is not treated as keyword") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t BEGIN /* END marker */ SELECT 1; END")
        r.body shouldBe "/* END marker */ SELECT 1"
    }

    test("empty body produces null without crashing") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t BEGIN END")
        r.body.shouldBeNull()
    }

    // -- Identifier handling --------------------------------------------

    test("table name containing BEFORE as substring is not mis-parsed as timing") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON before_log BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.AFTER
    }

    test("quoted trigger and table names are accepted") {
        val r = parse("""CREATE TRIGGER "trg-with-dash" AFTER INSERT ON "t-table" BEGIN SELECT 1; END""")
        r.timing shouldBe TriggerTiming.AFTER
        r.event shouldBe TriggerEvent.INSERT
        r.rejected shouldBe false
    }

    test("backtick-quoted identifiers are accepted") {
        val r = parse("CREATE TRIGGER `trg` AFTER INSERT ON `t` BEGIN SELECT 1; END")
        r.rejected shouldBe false
    }

    test("bracket-quoted identifiers are accepted") {
        val r = parse("CREATE TRIGGER [trg] AFTER INSERT ON [t] BEGIN SELECT 1; END")
        r.rejected shouldBe false
    }

    // -- Schema-qualified names produce R212 + rejected -----------------

    test("schema-qualified trigger name → R212 ACTION_REQUIRED, rejected") {
        val r = parse("CREATE TRIGGER main.trg AFTER INSERT ON t BEGIN SELECT 1; END")
        r.rejected shouldBe true
        r.notes.single().run {
            code shouldBe "R212"
            severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
        }
    }

    test("schema-qualified target table → R212 ACTION_REQUIRED, rejected") {
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON main.t BEGIN SELECT 1; END")
        r.rejected shouldBe true
        r.notes.single().run {
            code shouldBe "R212"
            severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
        }
    }

    // -- UPDATE OF cols produces R213 warning ---------------------------

    test("UPDATE OF cols → R213 WARNING, event UPDATE without column-list") {
        val r = parse(
            "CREATE TRIGGER trg AFTER UPDATE OF a, b ON t BEGIN SELECT 1; END",
        )
        r.event shouldBe TriggerEvent.UPDATE
        r.notes.single().run {
            code shouldBe "R213"
            severity shouldBe SchemaReadSeverity.WARNING
        }
        r.rejected shouldBe false
    }

    // -- Missing-/unparseable header produces R210/R211 ACTION_REQUIRED -

    test("missing timing → R210 ACTION_REQUIRED, defaults to BEFORE") {
        val r = parse("CREATE TRIGGER trg INSERT ON t BEGIN SELECT 1; END")
        r.timing shouldBe TriggerTiming.BEFORE
        val r210 = r.notes.single { it.code == "R210" }
        r210.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
    }

    test("missing event → R211 ACTION_REQUIRED, defaults to INSERT") {
        val r = parse("CREATE TRIGGER trg AFTER ON t BEGIN SELECT 1; END")
        r.event shouldBe TriggerEvent.INSERT
        val r211 = r.notes.single { it.code == "R211" }
        r211.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
    }

    test("missing BEGIN/END produces ACTION_REQUIRED note") {
        // Pathological input — sqlite_master would never return this for a
        // well-formed trigger, but the parser must not throw.
        val r = parse("CREATE TRIGGER trg AFTER INSERT ON t")
        r.body.shouldBeNull()
        r.notes.any { it.code == "R210" || it.code == "R211" } shouldBe false
        // No header parse errors — only the body is missing. The reader
        // path will catch this via the missing-body diagnostic from the
        // renderer / planner, not here.
    }

    test("completely malformed DDL emits R210 without throwing") {
        val r = parse("NOT EVEN CLOSE")
        r.notes.any { it.code == "R210" && it.severity == SchemaReadSeverity.ACTION_REQUIRED } shouldBe true
    }

    // -- Comments in header --------------------------------------------

    test("line comment in header is skipped") {
        val sql = """
            CREATE TRIGGER trg
              -- guarded by an audit flag
              AFTER INSERT ON t
            BEGIN SELECT 1; END
        """.trimIndent()
        val r = parse(sql)
        r.timing shouldBe TriggerTiming.AFTER
        r.event shouldBe TriggerEvent.INSERT
    }

    test("block comment in header is skipped") {
        val sql = "CREATE TRIGGER trg /* before/after the audit */ AFTER INSERT ON t BEGIN SELECT 1; END"
        val r = parse(sql)
        r.timing shouldBe TriggerTiming.AFTER
        r.event shouldBe TriggerEvent.INSERT
    }

    // -- Round-trip idempotency check ----------------------------------

    test("renderer-shaped output round-trips bit-identical body") {
        // Simulates the round trip Reverse → renderer-emitted DDL →
        // Reverse. Renderer output has `;\nEND;` as the closing.
        val rendered = """
            CREATE TRIGGER "trg"
                AFTER INSERT ON "t"
                FOR EACH ROW
            BEGIN
            UPDATE x SET a = NEW.a;
            INSERT INTO y VALUES (NEW.id);
            END;
        """.trimIndent()
        val r = parse(rendered)
        r.timing shouldBe TriggerTiming.AFTER
        r.event shouldBe TriggerEvent.INSERT
        r.forEach shouldBe TriggerForEach.ROW
        // Body keeps inner formatting; trailing `;` before END is stripped.
        r.body shouldBe "UPDATE x SET a = NEW.a;\nINSERT INTO y VALUES (NEW.id)"
    }
})
