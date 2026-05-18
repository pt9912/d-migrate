package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.TriggerCapability
import dev.dmigrate.driver.TriggerCapabilityDefaults
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.2 Trigger-Migration Sub-Slice A.2: PostgreSQL trigger renderer
 * pins. Covers Create/Drop, Replace native (PG-14+) and Drop+Create
 * fallback (older PG / file-only target), body-as-function-reference
 * validation, and the `W_TRIGGER_REPLACE_GAP` warning diagnostic.
 */
class PostgresTriggerDdlHelperTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun ordersTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
        ),
    )

    fun schemaWith(triggers: Map<String, TriggerDefinition>): SchemaDefinition =
        SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf("orders" to ordersTable()),
            triggers = triggers,
        )

    val sampleTrigger = TriggerDefinition(
        table = "orders",
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.BEFORE,
        forEach = TriggerForEach.ROW,
        body = "audit_orders()",
    )

    val pg14Options = DdlGenerationOptions(
        triggerCapability = TriggerCapabilityDefaults.forDialect(DatabaseDialect.POSTGRESQL),
        postgresMajorVersion = 14,
    )

    val pg13Options = DdlGenerationOptions(
        triggerCapability = TriggerCapabilityDefaults.forDialect(DatabaseDialect.POSTGRESQL),
        postgresMajorVersion = 13,
    )

    val fileOnlyOptions = DdlGenerationOptions(
        triggerCapability = TriggerCapabilityDefaults.forDialect(DatabaseDialect.POSTGRESQL),
        postgresMajorVersion = null,
    )

    test("CreateTrigger Up emits `CREATE TRIGGER` with timing/event/table/EXECUTE FUNCTION") {
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", sampleTrigger)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), schemaWith(mapOf("audit_log" to sampleTrigger)), diff)
        val r = gen.generateUp(plan, pg14Options)
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql.shouldContain("CREATE TRIGGER \"audit_log\"")
        sql.shouldContain("BEFORE INSERT")
        sql.shouldContain("ON \"orders\"")
        sql.shouldContain("FOR EACH ROW")
        sql.shouldContain("EXECUTE FUNCTION audit_orders();")
        sql.shouldNotContain("EXECUTE PROCEDURE")
    }

    test("CreateTrigger renders WHEN clause when condition is set") {
        val withWhen = sampleTrigger.copy(condition = "NEW.amount > 100")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", withWhen)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), schemaWith(mapOf("audit_log" to withWhen)), diff)
        val r = gen.generateUp(plan, pg14Options)
        val sql = r.statements.single().sql
        sql.shouldContain("WHEN (NEW.amount > 100)")
    }

    test("CreateTrigger renders FOR EACH STATEMENT when forEach is STATEMENT") {
        val stmtTrigger = sampleTrigger.copy(forEach = TriggerForEach.STATEMENT)
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", stmtTrigger)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), schemaWith(mapOf("audit_log" to stmtTrigger)), diff)
        val r = gen.generateUp(plan, pg14Options)
        r.statements.single().sql.shouldContain("FOR EACH STATEMENT")
    }

    test("CreateTrigger Up emits AFTER/INSTEAD OF/DELETE/UPDATE keyword combinations") {
        val instead = sampleTrigger.copy(
            timing = TriggerTiming.INSTEAD_OF,
            event = TriggerEvent.UPDATE,
        )
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", instead)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), schemaWith(mapOf("audit_log" to instead)), diff)
        val r = gen.generateUp(plan, pg14Options)
        r.statements.single().sql.shouldContain("INSTEAD OF UPDATE")
    }

    test("DropTrigger Up emits `DROP TRIGGER ... ON <table>`") {
        val current = schemaWith(mapOf("audit_log" to sampleTrigger))
        val desired = SchemaDefinition(name = "App", version = "1", tables = current.tables)
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateUp(plan, pg14Options)
        r.isBlocked shouldBe false
        r.statements.single().sql shouldBe "DROP TRIGGER \"audit_log\" ON \"orders\";"
    }

    test("CreateTrigger Down inverts to DROP TRIGGER ... ON <table>") {
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", sampleTrigger)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), schemaWith(mapOf("audit_log" to sampleTrigger)), diff)
        val r = gen.generateDown(plan, pg14Options)
        r.isBlocked shouldBe false
        r.statements.single().sql shouldBe "DROP TRIGGER \"audit_log\" ON \"orders\";"
    }

    test("ReplaceTrigger Up on PG-14 emits native `CREATE OR REPLACE TRIGGER` without gap warning") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "audit_orders_v2()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateUp(plan, pg14Options)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 1
        val sql = r.statements.single().sql
        sql.shouldContain("CREATE OR REPLACE TRIGGER \"audit_log\"")
        sql.shouldContain("EXECUTE FUNCTION audit_orders_v2();")
        r.diagnostics.none { it.code == "W_TRIGGER_REPLACE_GAP" } shouldBe true
    }

    test("ReplaceTrigger Up on PG-13 falls back to Drop+Create with W_TRIGGER_REPLACE_GAP warning") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "audit_orders_v2()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateUp(plan, pg13Options)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 2
        r.statements[0].sql shouldBe "DROP TRIGGER \"audit_log\" ON \"orders\";"
        r.statements[1].sql.shouldContain("CREATE TRIGGER \"audit_log\"")
        r.statements[1].sql.shouldContain("EXECUTE FUNCTION audit_orders_v2();")
        r.statements[1].sql.shouldNotContain("OR REPLACE")
        val gapWarning = r.diagnostics.single { it.code == "W_TRIGGER_REPLACE_GAP" }
        gapWarning.severity shouldBe DiffDiagnostic.Severity.WARNING
    }

    test("ReplaceTrigger Up on file-only target (no server version) falls back to Drop+Create") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "audit_orders_v2()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateUp(plan, fileOnlyOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 2
        r.diagnostics.any { it.code == "W_TRIGGER_REPLACE_GAP" } shouldBe true
    }

    test("ReplaceTrigger Down without before body blocks with ROUTINE_DOWN_BODY_UNKNOWN") {
        val before = sampleTrigger.copy(body = null)
        val after = sampleTrigger.copy(body = "audit_orders_v2()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateDown(plan, pg14Options)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.any { it.code == "ROUTINE_DOWN_BODY_UNKNOWN" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("Body that is not a function reference blocks with TRIGGER_BODY_NOT_FUNCTION_REFERENCE") {
        val inline = sampleTrigger.copy(body = "BEGIN INSERT INTO log VALUES (NEW.id); END")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", inline)))
        val plan = planner.plan(SchemaDefinition(name = "App", version = "1"), schemaWith(mapOf("audit_log" to inline)), diff)
        val r = gen.generateUp(plan, pg14Options)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.TRIGGER_BODY_NOT_FUNCTION_REFERENCE
        r.diagnostics.any { it.code == "TRIGGER_BODY_NOT_FUNCTION_REFERENCE" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("Body validator accepts simple identifier with empty arg list") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("audit_orders()")
        v shouldBe PostgresTriggerDdlHelper.FunctionReferenceValidation.Ok
    }

    test("Body validator accepts schema-qualified identifier") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("audit.log_change()")
        v shouldBe PostgresTriggerDdlHelper.FunctionReferenceValidation.Ok
    }

    test("Body validator accepts literal arguments") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("log_change('orders', 'INSERT')")
        v shouldBe PostgresTriggerDdlHelper.FunctionReferenceValidation.Ok
    }

    test("Body validator accepts trailing semicolon and trims whitespace") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("  audit_orders()  ;  ")
        v shouldBe PostgresTriggerDdlHelper.FunctionReferenceValidation.Ok
    }

    test("Body validator rejects multi-line BEGIN/END block") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("BEGIN\nINSERT INTO log VALUES (NEW.id);\nEND")
        check(v is PostgresTriggerDdlHelper.FunctionReferenceValidation.Invalid)
    }

    test("Body validator rejects multiple statements separated by semicolons") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("audit_orders(); cleanup()")
        check(v is PostgresTriggerDdlHelper.FunctionReferenceValidation.Invalid)
    }

    test("Body validator rejects empty body") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference("")
        check(v is PostgresTriggerDdlHelper.FunctionReferenceValidation.Invalid)
    }

    test("Body validator rejects null body") {
        val v = PostgresTriggerDdlHelper.validateBodyAsFunctionReference(null)
        check(v is PostgresTriggerDdlHelper.FunctionReferenceValidation.Invalid)
    }

    test("ReplaceTrigger PG-13 fallback aborts on invalid body before emitting DROP") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "BEGIN INSERT INTO log VALUES (NEW.id); END")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateUp(plan, pg13Options)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.TRIGGER_BODY_NOT_FUNCTION_REFERENCE
        r.statements.shouldBeEmpty()
    }
})
