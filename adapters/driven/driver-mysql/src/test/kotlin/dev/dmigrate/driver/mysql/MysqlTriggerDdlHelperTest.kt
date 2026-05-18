package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.TriggerPlanningContext
import dev.dmigrate.core.diff.migration.TriggerReplaceMode
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.2 Trigger-Migration Sub-Slice B: MySQL trigger renderer pins.
 * Covers Create/Drop, Replace via Drop+Create (MySQL has no
 * `CREATE OR REPLACE TRIGGER`), the strict-gap lift, and the
 * MySQL-specific rejections for `WHEN` and `FOR EACH STATEMENT`.
 */
class MysqlTriggerDdlHelperTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()

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
        body = "SET NEW.created_at = NOW()",
    )

    val lenientOptions = DdlGenerationOptions()
    val strictOptions = DdlGenerationOptions(strictGapOperations = true)

    val fallbackContext = TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK)

    test("CreateTrigger Up emits CREATE TRIGGER with timing/event/ON table/FOR EACH ROW + inline body") {
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", sampleTrigger)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to sampleTrigger)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql.shouldContain("CREATE TRIGGER `audit_log`")
        sql.shouldContain("BEFORE INSERT")
        sql.shouldContain("ON `orders`")
        sql.shouldContain("FOR EACH ROW")
        sql.shouldContain("SET NEW.created_at = NOW();")
        // No `EXECUTE FUNCTION` wrapper — MySQL body is inline.
        sql.shouldNotContain("EXECUTE FUNCTION")
    }

    test("CreateTrigger body without trailing semicolon gets one appended") {
        val noSemi = sampleTrigger.copy(body = "SET NEW.created_at = NOW()")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", noSemi)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to noSemi)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.statements.single().sql.trimEnd().endsWith(";") shouldBe true
    }

    test("CreateTrigger body with BEGIN/END block renders as inline statement") {
        val begin = sampleTrigger.copy(body = "BEGIN INSERT INTO audit VALUES (NEW.id); END")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", begin)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to begin)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        val sql = r.statements.single().sql
        sql.shouldContain("BEGIN INSERT INTO audit VALUES (NEW.id); END")
        // No MySQL `DELIMITER //` wrapper in the artefact.
        sql.shouldNotContain("DELIMITER")
    }

    test("DropTrigger Up emits `DROP TRIGGER <name>` without table qualifier") {
        val current = schemaWith(mapOf("audit_log" to sampleTrigger))
        val desired = SchemaDefinition(name = "App", version = "1", tables = current.tables)
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements.single().sql shouldBe "DROP TRIGGER `audit_log`;"
        // Critical: no `<table>.<name>` qualifier — that is a MySQL
        // syntax error. The renderer drops only the bare trigger name.
        r.statements.single().sql.shouldNotContain("orders.audit_log")
        r.statements.single().sql.shouldNotContain("`orders`.`audit_log`")
    }

    test("CreateTrigger Down inverts to DROP TRIGGER bare name") {
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", sampleTrigger)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to sampleTrigger)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateDown(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements.single().sql shouldBe "DROP TRIGGER `audit_log`;"
    }

    test("ReplaceTrigger Up renders Drop+Create and emits W_TRIGGER_REPLACE_GAP warning") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "SET NEW.created_at = UTC_TIMESTAMP()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 2
        r.statements[0].sql shouldBe "DROP TRIGGER `audit_log`;"
        r.statements[1].sql.shouldContain("CREATE TRIGGER `audit_log`")
        r.statements[1].sql.shouldContain("UTC_TIMESTAMP()")
        val gapWarning = r.diagnostics.single { it.code == "W_TRIGGER_REPLACE_GAP" }
        gapWarning.severity shouldBe DiffDiagnostic.Severity.WARNING
    }

    test("ReplaceTrigger Down without before body blocks with ROUTINE_DOWN_BODY_UNKNOWN") {
        val before = sampleTrigger.copy(body = null)
        val after = sampleTrigger.copy(body = "SET NEW.created_at = UTC_TIMESTAMP()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateDown(plan, lenientOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.any { it.code == "ROUTINE_DOWN_BODY_UNKNOWN" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("CreateTrigger with WHEN condition blocks DIALECT_UNSUPPORTED_OPERATION") {
        val withWhen = sampleTrigger.copy(condition = "NEW.amount > 100")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", withWhen)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to withWhen)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.any { it.code == "MYSQL_TRIGGER_CONDITION_UNSUPPORTED" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("CreateTrigger with FOR EACH STATEMENT blocks DIALECT_UNSUPPORTED_OPERATION") {
        val stmtLevel = sampleTrigger.copy(forEach = TriggerForEach.STATEMENT)
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", stmtLevel)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to stmtLevel)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        r.diagnostics.any { it.code == "MYSQL_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("ReplaceTrigger with strict mode blocks MANUAL_ACTION_REQUIRED and emits no statements / no gap warning") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "SET NEW.created_at = UTC_TIMESTAMP()")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        // Sanity: the Mapper set hasGap on the ReplaceTrigger op.
        val op = plan.operations.single { it is DiffOperation.ReplaceTrigger } as DiffOperation.ReplaceTrigger
        op.risks.up.hasGap shouldBe true

        val r = gen.generateUp(plan, strictOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.statements.shouldBeEmpty()
        r.diagnostics.none { it.code == "W_TRIGGER_REPLACE_GAP" } shouldBe true
        r.diagnostics.any { it.code == "OPERATION_HAS_GAP_STRICT_BLOCKED" } shouldBe true
    }
})
