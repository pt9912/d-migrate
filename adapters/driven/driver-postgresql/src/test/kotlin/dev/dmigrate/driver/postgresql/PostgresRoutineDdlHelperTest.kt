package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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

    test("generateFunctions emits volatility, STRICT and SECURITY DEFINER (F3)") {
        val functions = mapOf(
            "last_day" to FunctionDefinition(
                body = "SELECT 1",
                language = "sql",
                parameters = listOf(ParameterDefinition("p1", "timestamptz")),
                volatility = FunctionVolatility.IMMUTABLE,
                strict = true,
                security = RoutineSecurity.DEFINER,
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "\$\$ LANGUAGE sql IMMUTABLE STRICT SECURITY DEFINER;"
        skipped.shouldBeEmpty()
    }

    test("generateFunctions emits STABLE but omits the VOLATILE default (F3)") {
        val functions = mapOf(
            "f_stable" to FunctionDefinition(body = "SELECT 1", language = "sql",
                volatility = FunctionVolatility.STABLE),
            "f_volatile" to FunctionDefinition(body = "SELECT 1", language = "sql",
                volatility = FunctionVolatility.VOLATILE),
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        val stableSql = result.first { it.sql.contains("\"f_stable\"") }.sql
        val volatileSql = result.first { it.sql.contains("\"f_volatile\"") }.sql
        stableSql shouldContain "\$\$ LANGUAGE sql STABLE;"
        volatileSql shouldContain "\$\$ LANGUAGE sql;"
        volatileSql shouldNotContain "VOLATILE"
    }

    test("generateFunctions with null body produces action-required note") {
        val functions = mapOf(
            "missing_fn" to FunctionDefinition(body = null)
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldBe ""
        result[0].render() shouldContain "-- [E053] Function 'missing_fn' has no body and must be manually implemented."
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_fn"
    }

    test("generateFunctions with wrong sourceDialect produces action-required note") {
        val functions = mapOf(
            "mysql_fn" to FunctionDefinition(
                body = "SELECT 1",
                sourceDialect = "mysql"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateFunctions(functions, skipped)

        result shouldHaveSize 1
        result[0].sql shouldBe ""
        result[0].render() shouldContain "must be manually rewritten for PostgreSQL"
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

    test("generateProcedures with null body produces action-required note") {
        val procedures = mapOf(
            "missing_proc" to ProcedureDefinition(body = null)
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateProcedures(procedures, skipped)

        result shouldHaveSize 1
        result[0].sql shouldBe ""
        result[0].render() shouldContain "-- [E053] Procedure 'missing_proc' has no body and must be manually implemented."
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

    test("generateTriggers emits a multi-event trigger as `INSERT OR UPDATE` in canonical order (F4)") {
        // Pagila's film_fulltext_trigger fires `BEFORE INSERT OR UPDATE`.
        // The events set is passed UPDATE-first to prove emission follows the
        // canonical enum order (INSERT before UPDATE), not iteration order.
        val triggers = mapOf(
            ObjectKeyCodec.triggerKey("film", "film_fulltext_trigger") to TriggerDefinition(
                table = "film",
                events = setOf(TriggerEvent.UPDATE, TriggerEvent.INSERT),
                timing = TriggerTiming.BEFORE,
                forEach = TriggerForEach.ROW,
                body = "EXECUTE FUNCTION film_fulltext_update()",
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "BEFORE INSERT OR UPDATE ON \"film\""
        skipped.shouldBeEmpty()
    }

    test("generateTriggers emits the bare trigger name for a canonical table::name key (F1)") {
        // Pagila keeps `last_updated` on many tables; the model keys them
        // `table::last_updated` for uniqueness, but PostgreSQL's per-table
        // trigger namespace means the emitted identifier must be the bare
        // `last_updated`, not the canonical key `users::last_updated`.
        val triggers = mapOf(
            ObjectKeyCodec.triggerKey("users", "last_updated") to TriggerDefinition(
                table = "users",
                event = TriggerEvent.UPDATE,
                timing = TriggerTiming.BEFORE,
                forEach = TriggerForEach.ROW,
                body = "EXECUTE FUNCTION last_updated()"
            )
        )
        val skipped = mutableListOf<SkippedObject>()

        val result = helper.generateTriggers(triggers, skipped)

        result shouldHaveSize 1
        result[0].sql shouldContain "CREATE TRIGGER \"last_updated\""
        result[0].sql shouldNotContain "users::last_updated"
        result[0].sql shouldContain "BEFORE UPDATE ON \"users\""
        result[0].sql shouldContain "EXECUTE FUNCTION last_updated()"
        skipped.shouldBeEmpty()
    }

    test("generateTriggers with null body produces action-required note") {
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
        result[0].sql shouldBe ""
        result[0].render() shouldContain "-- [E053] Trigger 'missing_trg' has no body and must be manually implemented."
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "missing_trg"
    }

    test("generateTriggers with wrong sourceDialect produces action-required note") {
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
        result[0].sql shouldBe ""
        result[0].render() shouldContain "must be manually rewritten for PostgreSQL"
        result[0].notes shouldHaveSize 1
        result[0].notes[0].type shouldBe NoteType.ACTION_REQUIRED
        result[0].notes[0].code shouldBe "E053"
        skipped shouldHaveSize 1
        skipped[0].name shouldBe "mysql_trg"
    }
})
