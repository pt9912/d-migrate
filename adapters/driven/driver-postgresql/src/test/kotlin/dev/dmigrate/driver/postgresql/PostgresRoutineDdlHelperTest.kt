package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PostgresRoutineDdlHelperTest : FunSpec({

    val helper = PostgresRoutineDdlHelper { "\"$it\"" }

    // ── Views ───────────────────────────────────────

    test("generateViews with valid query produces CREATE VIEW") {
        val views = mapOf(
            "active_users" to ViewDefinition(
                query = "SELECT 1",
                sourceDialect = "postgresql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateViews(views, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE OR REPLACE VIEW \"active_users\" AS"
        result[0].sql shouldContain "SELECT 1"
        skipped.shouldBeEmpty()
    }

    test("generateViews with null query skips and adds to skipped") {
        val views = mapOf(
            "broken_view" to ViewDefinition(query = null)
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateViews(views, skipped)

        result.shouldBeEmpty()
        skipped shouldHaveSize 1
        skipped[0].type shouldBe "view"
        skipped[0].name shouldBe "broken_view"
        skipped[0].reason shouldBe "No query defined"
    }

    // ── Functions ───────────────────────────────────

    test("generateFunctions with body produces CREATE FUNCTION") {
        val functions = mapOf(
            "add_one" to FunctionDefinition(
                body = "BEGIN RETURN 1; END;",
                language = "plpgsql",
                parameters = listOf(ParameterDefinition("x", "integer"))
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE OR REPLACE FUNCTION \"add_one\""
        result[0].sql shouldContain "\"x\" INTEGER"
        result[0].sql shouldContain "BEGIN RETURN 1; END;"
        result[0].sql shouldContain "LANGUAGE plpgsql"
        result[0].sql shouldContain "\$\$"
        skipped.shouldBeEmpty()
    }

    test("generateFunctions with null body produces TODO and ManualActionRequired note") {
        val functions = mapOf(
            "missing_fn" to FunctionDefinition(body = null)
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "\"missing_fn\""
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_fn"
    }

    test("generateFunctions with wrong sourceDialect produces TODO") {
        val functions = mapOf(
            "mysql_fn" to FunctionDefinition(
                body = "SELECT 1",
                sourceDialect = "mysql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "mysql"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "mysql_fn"
    }

    // ── Procedures ──────────────────────────────────

    test("generateProcedures with body produces CREATE PROCEDURE") {
        val procedures = mapOf(
            "do_work" to ProcedureDefinition(
                body = "BEGIN RAISE NOTICE 'done'; END;",
                language = "plpgsql",
                parameters = listOf(ParameterDefinition("val", "text"))
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateProcedures(procedures, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE OR REPLACE PROCEDURE \"do_work\""
        result[0].sql shouldContain "\"val\" TEXT"
        result[0].sql shouldContain "BEGIN RAISE NOTICE 'done'; END;"
        result[0].sql shouldContain "LANGUAGE plpgsql"
        skipped.shouldBeEmpty()
    }

    test("generateProcedures with null body produces TODO") {
        val procedures = mapOf(
            "missing_proc" to ProcedureDefinition(body = null)
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateProcedures(procedures, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "\"missing_proc\""
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_proc"
    }

    // ── Triggers ────────────────────────────────────

    test("generateTriggers with body produces function and trigger statements") {
        val triggers = mapOf(
            "audit_insert" to TriggerDefinition(
                table = "users",
                event = TriggerEvent.INSERT,
                timing = TriggerTiming.AFTER,
                forEach = TriggerForEach.ROW,
                body = "BEGIN INSERT INTO audit_log VALUES (NEW.id); RETURN NEW; END;"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 2
        // First statement: trigger function
        result[0].sql shouldContain "CREATE OR REPLACE FUNCTION \"trg_fn_audit_insert\"() RETURNS TRIGGER"
        result[0].sql shouldContain "BEGIN INSERT INTO audit_log VALUES (NEW.id); RETURN NEW; END;"
        result[0].sql shouldContain "LANGUAGE plpgsql"
        // Second statement: trigger itself
        result[1].sql shouldContain "CREATE TRIGGER \"audit_insert\""
        result[1].sql shouldContain "AFTER INSERT ON \"users\""
        result[1].sql shouldContain "FOR EACH ROW"
        result[1].sql shouldContain "EXECUTE FUNCTION \"trg_fn_audit_insert\"()"
        skipped.shouldBeEmpty()
    }

    test("generateTriggers with null body produces TODO") {
        val triggers = mapOf(
            "missing_trg" to TriggerDefinition(
                table = "orders",
                event = TriggerEvent.UPDATE,
                timing = TriggerTiming.BEFORE,
                body = null
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "\"missing_trg\""
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_trg"
    }

    test("generateTriggers with wrong sourceDialect produces TODO") {
        val triggers = mapOf(
            "mysql_trg" to TriggerDefinition(
                table = "items",
                event = TriggerEvent.DELETE,
                timing = TriggerTiming.BEFORE,
                body = "DELETE FROM audit WHERE id = OLD.id;",
                sourceDialect = "mysql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "mysql"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "mysql_trg"
    }
})
