package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteRoutineDdlHelperTest : FunSpec({

    val helper = SqliteRoutineDdlHelper { "\"$it\"" }

    // ── Views ───────────────────────────────────────

    test("generateViews with valid query produces CREATE VIEW") {
        val views = mapOf(
            "active_users" to ViewDefinition(
                query = "SELECT 1",
                sourceDialect = "sqlite"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateViews(views, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE VIEW IF NOT EXISTS \"active_users\" AS"
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

    test("generateFunctions skips all functions with E054 note") {
        val functions = mapOf(
            "my_func" to FunctionDefinition(
                body = "RETURN 1;",
                language = "sql",
                parameters = listOf(ParameterDefinition("x", "integer"))
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- Function \"my_func\" is not supported in SQLite"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E054"
        result[0].notes[0].objectName shouldBe "my_func"
        skipped shouldHaveSize 1
        skipped[0].type shouldBe "function"
        skipped[0].name shouldBe "my_func"
    }

    // ── Procedures ──────────────────────────────────

    test("generateProcedures skips all procedures with E054 note") {
        val procedures = mapOf(
            "my_proc" to ProcedureDefinition(
                body = "SELECT 1;",
                parameters = listOf(ParameterDefinition("val", "text"))
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateProcedures(procedures, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- Procedure \"my_proc\" is not supported in SQLite"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E054"
        result[0].notes[0].objectName shouldBe "my_proc"
        skipped shouldHaveSize 1
        skipped[0].type shouldBe "procedure"
        skipped[0].name shouldBe "my_proc"
    }

    // ── Triggers ────────────────────────────────────

    test("generateTriggers with body produces CREATE TRIGGER") {
        val triggers = mapOf(
            "audit_insert" to TriggerDefinition(
                table = "users",
                event = TriggerEvent.INSERT,
                timing = TriggerTiming.AFTER,
                forEach = TriggerForEach.ROW,
                body = "INSERT INTO audit_log VALUES (NEW.id);"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE TRIGGER \"audit_insert\""
        result[0].sql shouldContain "AFTER INSERT ON \"users\""
        result[0].sql shouldContain "FOR EACH ROW"
        result[0].sql shouldContain "BEGIN"
        result[0].sql shouldContain "INSERT INTO audit_log VALUES (NEW.id);"
        result[0].sql shouldContain "END;"
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
            "pg_trg" to TriggerDefinition(
                table = "items",
                event = TriggerEvent.DELETE,
                timing = TriggerTiming.BEFORE,
                body = "DELETE FROM audit WHERE id = OLD.id;",
                sourceDialect = "postgresql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "postgresql"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "pg_trg"
    }
})
