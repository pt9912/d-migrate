package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaComparator
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
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * E.2 Sub-Slice A.3: end-to-end pin from Mapper → Renderer for the
 * `--strict-gap-operations` lift.
 *
 * Default path (`strictGapOperations = false`): ReplaceTrigger in
 * DROP_CREATE_FALLBACK mode renders two statements + a
 * `W_TRIGGER_REPLACE_GAP` WARNING.
 *
 * Strict path (`strictGapOperations = true`): the same operation is
 * blocked with `MANUAL_ACTION_REQUIRED` and emits zero statements +
 * no gap warning (the strict diagnostic carries the BLOCKER reason).
 *
 * The Mapper is responsible for `hasGap`; the renderer is responsible
 * for the lift. This test exercises both sides together.
 */
class PostgresStrictGapLiftTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun ordersTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
        ),
    )

    fun trigger(body: String) = TriggerDefinition(
        table = "orders",
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.BEFORE,
        body = body,
    )

    fun planReplace(context: TriggerPlanningContext) = run {
        val before = trigger("audit_orders()")
        val after = trigger("audit_orders_v2()")
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to ordersTable()),
            triggers = mapOf("audit_log" to before),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("orders" to ordersTable()),
            triggers = mapOf("audit_log" to after),
        )
        val diff = SchemaComparator().compare(current, desired)
        planner.plan(current, desired, diff, triggerPlanningContext = context)
    }

    val lenientOptions = DdlGenerationOptions(strictGapOperations = false)
    val strictOptions = DdlGenerationOptions(strictGapOperations = true)

    test("strict mode + hasGap blocks ReplaceTrigger with MANUAL_ACTION_REQUIRED and emits no statements") {
        val plan = planReplace(TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK))
        // sanity: Mapper marked hasGap
        val op = plan.operations.single { it is DiffOperation.ReplaceTrigger } as DiffOperation.ReplaceTrigger
        op.risks.up.hasGap shouldBe true

        val r = gen.generateUp(plan, strictOptions)
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        r.statements.shouldBeEmpty()
        // no W_TRIGGER_REPLACE_GAP — the operation didn't render
        r.diagnostics.none { it.code == "W_TRIGGER_REPLACE_GAP" } shouldBe true
        // strict-skip diagnostic is present
        r.diagnostics.any { it.code == "OPERATION_HAS_GAP_STRICT_BLOCKED" } shouldBe true
    }

    test("lenient mode + hasGap renders Drop+Create and emits W_TRIGGER_REPLACE_GAP warning") {
        val plan = planReplace(TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK))
        val r = gen.generateUp(plan, lenientOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 2
        r.diagnostics.any { it.code == "W_TRIGGER_REPLACE_GAP" } shouldBe true
        r.diagnostics.none { it.code == "OPERATION_HAS_GAP_STRICT_BLOCKED" } shouldBe true
    }

    test("strict mode + NATIVE_REPLACE (hasGap = false) leaves the lenient native-replace path intact") {
        val plan = planReplace(TriggerPlanningContext(TriggerReplaceMode.NATIVE_REPLACE))
        val op = plan.operations.single { it is DiffOperation.ReplaceTrigger } as DiffOperation.ReplaceTrigger
        op.risks.up.hasGap shouldBe false

        val r = gen.generateUp(plan, strictOptions)
        r.isBlocked shouldBe false
        r.statements shouldHaveSize 1
        r.statements.single().sql.contains("CREATE OR REPLACE TRIGGER") shouldBe true
        r.diagnostics.none { it.code == "OPERATION_HAS_GAP_STRICT_BLOCKED" } shouldBe true
        r.diagnostics.none { it.code == "W_TRIGGER_REPLACE_GAP" } shouldBe true
    }
})
