package dev.dmigrate.driver.sqlite

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
 * E.2 Trigger-Migration Sub-Slice C: SQLite trigger renderer pins.
 * Covers Create/Drop, Replace via Drop+Create (SQLite has no
 * `CREATE OR REPLACE TRIGGER`), the strict-gap lift, the
 * `FOR EACH STATEMENT` rejection, and the
 * `SqliteRebuildPlanner`-absorption invariant: trigger ops on a
 * rebuild-bound table are absorbed into the rebuild bucket and not
 * re-emitted by the standalone trigger renderer.
 */
class SqliteTriggerDdlHelperTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

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
        body = "UPDATE orders SET created_at = strftime('%s','now') WHERE id = NEW.id;",
    )

    val lenientOptions = DdlGenerationOptions()
    val strictOptions = DdlGenerationOptions(strictGapOperations = true)

    val fallbackContext = TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK)

    test("CreateTrigger Up emits CREATE TRIGGER ... BEGIN <body> END;") {
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
        sql.shouldContain("CREATE TRIGGER \"audit_log\"")
        sql.shouldContain("BEFORE INSERT ON \"orders\"")
        sql.shouldContain("FOR EACH ROW")
        sql.shouldContain("BEGIN")
        sql.shouldContain("END;")
        sql.shouldContain("UPDATE orders SET created_at")
    }

    test("CreateTrigger with WHEN condition renders the WHEN clause") {
        val withWhen = sampleTrigger.copy(condition = "NEW.amount > 100")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", withWhen)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to withWhen)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.statements.single().sql.shouldContain("WHEN NEW.amount > 100")
    }

    test("DropTrigger Up emits DROP TRIGGER bare name (no schema/table qualifier)") {
        val current = schemaWith(mapOf("audit_log" to sampleTrigger))
        val desired = SchemaDefinition(name = "App", version = "1", tables = current.tables)
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements.single().sql shouldBe "DROP TRIGGER \"audit_log\";"
        r.statements.single().sql.shouldNotContain("orders.audit_log")
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
        r.statements.single().sql shouldBe "DROP TRIGGER \"audit_log\";"
    }

    test("ReplaceTrigger renders Drop+Create and emits W_TRIGGER_REPLACE_GAP warning") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "UPDATE orders SET updated_at = strftime('%s','now') WHERE id = NEW.id;")
        val current = schemaWith(mapOf("audit_log" to before))
        val desired = schemaWith(mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 2
        r.statements[0].sql shouldBe "DROP TRIGGER \"audit_log\";"
        r.statements[1].sql.shouldContain("CREATE TRIGGER \"audit_log\"")
        r.statements[1].sql.shouldContain("updated_at")
        val gapWarning = r.diagnostics.single { it.code == "W_TRIGGER_REPLACE_GAP" }
        gapWarning.severity shouldBe DiffDiagnostic.Severity.WARNING
    }

    test("ReplaceTrigger Down without before body blocks with ROUTINE_DOWN_BODY_UNKNOWN") {
        val before = sampleTrigger.copy(body = null)
        val after = sampleTrigger.copy(body = "UPDATE orders SET updated_at = strftime('%s','now') WHERE id = NEW.id;")
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

    test("CreateTrigger without body blocks ROUTINE_BODY_UNKNOWN (non-Replace path)") {
        val noBody = sampleTrigger.copy(body = null)
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", noBody)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to noBody)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "ROUTINE_BODY_UNKNOWN" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("CreateTrigger with multi-statement BEGIN/END body emits exactly one MigrationDdlStatement") {
        // Pin: the renderer wraps a multi-statement body in BEGIN..END
        // and emits a single MigrationDdlStatement. The JDBC executor
        // runs it as one Statement.execute(...); a future regression
        // that pre-splits on `;` would break this test.
        val multiStmt = sampleTrigger.copy(
            body = "UPDATE orders SET created_at = strftime('%s','now') WHERE id = NEW.id; " +
                "INSERT INTO audit_log(orders_id) VALUES (NEW.id);",
        )
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", multiStmt)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to multiStmt)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 1
        val sql = r.statements.single().sql
        sql.shouldContain("UPDATE orders")
        sql.shouldContain("INSERT INTO audit_log")
        // Trailing `;` from the body is deduplicated — the wrapper's
        // `END;` is the single statement terminator.
        sql.shouldContain(";\nEND;")
    }

    test("CreateTrigger with body that ends in `;` does not produce double terminator") {
        // E.2 review follow-up: bodies with a trailing `;` must not
        // collide with the BEGIN..END's `END;` terminator.
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", sampleTrigger)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to sampleTrigger)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        val sql = r.statements.single().sql
        // The body literal ends in `;`. Deduplicate-then-terminate
        // produces `... NEW.id;\nEND;` exactly once, not `... NEW.id;;`.
        sql.shouldNotContain(";;")
    }

    test("ReplaceTrigger with cross-table move renders bare DROP + CREATE referencing after.table") {
        val customersTable = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, required = true)),
        )
        val before = sampleTrigger.copy(table = "orders")
        val after = sampleTrigger.copy(table = "customers")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to ordersTable(), "customers" to customersTable),
            triggers = mapOf("audit_log" to before),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to ordersTable(), "customers" to customersTable),
            triggers = mapOf("audit_log" to after),
        )
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 2
        r.statements[0].sql shouldBe "DROP TRIGGER \"audit_log\";"
        r.statements[1].sql.shouldContain("ON \"customers\"")
        r.statements[1].sql.shouldNotContain("ON \"orders\"")
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
        r.diagnostics.any { it.code == "SQLITE_TRIGGER_STATEMENT_LEVEL_UNSUPPORTED" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("Trigger with sourceDialect = postgresql is rejected for SQLite emission") {
        val crossDialect = sampleTrigger.copy(sourceDialect = "postgresql")
        val diff = SchemaDiff(triggersAdded = listOf(NamedTrigger("audit_log", crossDialect)))
        val plan = planner.plan(
            SchemaDefinition(name = "App", version = "1"),
            schemaWith(mapOf("audit_log" to crossDialect)),
            diff,
            triggerPlanningContext = fallbackContext,
        )
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.diagnostics.any { it.code == "SQLITE_TRIGGER_BODY_NOT_RENDERABLE" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("ReplaceTrigger with strict mode blocks MANUAL_ACTION_REQUIRED + no statements + no gap warning") {
        val before = sampleTrigger
        val after = sampleTrigger.copy(body = "UPDATE orders SET updated_at = strftime('%s','now') WHERE id = NEW.id;")
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

    test("Trigger op on rebuild table is absorbed into rebuild bucket — no separate CREATE TRIGGER leaks") {
        // A rebuild on `orders` (e.g. AlterColumnType) absorbs every
        // trigger-op on `orders`. The SqliteRebuildPlanner.classify
        // contract is that the trigger never reaches the standalone
        // renderer; tests pin this by counting `CREATE TRIGGER`
        // occurrences in the final DDL and showing they all sit inside
        // the rebuild bucket's output, not outside.
        val ordersWithNullableNote = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                "note" to ColumnDefinition(type = NeutralType.Text(), required = false),
            ),
        )
        val ordersWithRequiredNote = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
                // nullability change forces a SQLite rebuild
                "note" to ColumnDefinition(type = NeutralType.Text(), required = true),
            ),
        )
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to ordersWithNullableNote),
            triggers = mapOf("audit_log" to sampleTrigger),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to ordersWithRequiredNote),
            triggers = mapOf("audit_log" to sampleTrigger),
        )
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = fallbackContext)
        val r = gen.generateUp(plan, lenientOptions)
        // The rebuild renders, and within the rebuild's dependent-
        // trigger drop/recreate it produces CREATE TRIGGER. The
        // standalone trigger renderer must not produce an additional
        // statement for `audit_log` outside that rebuild — otherwise
        // we'd see the same trigger created twice. Counting matching
        // statements that carry only the trigger's op id (and not the
        // rebuild's op ids) is the most direct way to assert that.
        val standaloneTriggerStatements = r.statements.filter { stmt ->
            stmt.operationIds.size == 1 &&
                stmt.operationIds.any { it.startsWith("CreateTrigger") || it.startsWith("DropTrigger") }
        }
        standaloneTriggerStatements.shouldBeEmpty()
    }
})
