package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MysqlRoutineDdlHelperTest : FunSpec({

    val helper = MysqlRoutineDdlHelper { "`$it`" }

    // ── Views ───────────────────────────────────────

    test("generateViews with valid query produces CREATE VIEW") {
        val views = mapOf(
            "active_users" to ViewDefinition(
                query = "SELECT 1",
                sourceDialect = "mysql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateViews(views, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE OR REPLACE VIEW `active_users` AS"
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

    test("generateFunctions with body produces CREATE FUNCTION with DELIMITER") {
        val functions = mapOf(
            "add_one" to FunctionDefinition(
                body = "RETURN x + 1;",
                sourceDialect = "mysql",
                parameters = listOf(ParameterDefinition("x", "integer")),
                returns = ReturnType("integer")
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "DELIMITER //"
        result[0].sql shouldContain "CREATE FUNCTION `add_one`"
        result[0].sql shouldContain "`x` INTEGER"
        result[0].sql shouldContain "RETURNS INTEGER"
        result[0].sql shouldContain "BEGIN"
        result[0].sql shouldContain "RETURN x + 1;"
        result[0].sql shouldContain "END //"
        result[0].sql shouldContain "DELIMITER ;"
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
        result[0].sql shouldContain "`missing_fn`"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_fn"
    }

    test("generateFunctions with wrong sourceDialect produces TODO") {
        val functions = mapOf(
            "pg_fn" to FunctionDefinition(
                body = "BEGIN RETURN 1; END;",
                sourceDialect = "postgresql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "-- TODO"
        result[0].sql shouldContain "postgresql"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "pg_fn"
    }

    // ── Procedures ──────────────────────────────────

    test("generateProcedures with body produces CREATE PROCEDURE with DELIMITER") {
        val procedures = mapOf(
            "do_work" to ProcedureDefinition(
                body = "SELECT 1;",
                sourceDialect = "mysql",
                parameters = listOf(ParameterDefinition("val", "text"))
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateProcedures(procedures, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "DELIMITER //"
        result[0].sql shouldContain "CREATE PROCEDURE `do_work`"
        result[0].sql shouldContain "`val` TEXT"
        result[0].sql shouldContain "BEGIN"
        result[0].sql shouldContain "SELECT 1;"
        result[0].sql shouldContain "END //"
        result[0].sql shouldContain "DELIMITER ;"
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
        result[0].sql shouldContain "`missing_proc`"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_proc"
    }

    // ── Triggers ────────────────────────────────────

    test("generateTriggers with body produces single trigger statement with DELIMITER") {
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
        result[0].sql shouldContain "DELIMITER //"
        result[0].sql shouldContain "CREATE TRIGGER `audit_insert`"
        result[0].sql shouldContain "AFTER INSERT ON `users`"
        result[0].sql shouldContain "FOR EACH ROW"
        result[0].sql shouldContain "BEGIN"
        result[0].sql shouldContain "INSERT INTO audit_log VALUES (NEW.id);"
        result[0].sql shouldContain "END //"
        result[0].sql shouldContain "DELIMITER ;"
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
        result[0].sql shouldContain "`missing_trg`"
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
