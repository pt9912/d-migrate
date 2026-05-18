package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * E.2 Sub-Slice A.3: pin that the Mapper sets `OperationRisk.hasGap`
 * on `ReplaceTrigger` based on the [TriggerPlanningContext].
 */
class OperationMapperTriggerGapTest : FunSpec({

    val planner = DiffPlanner()

    fun trigger(body: String) = TriggerDefinition(
        table = "orders",
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.BEFORE,
        body = body,
    )

    fun replaceTriggerPlan(context: TriggerPlanningContext): DiffOperation.ReplaceTrigger {
        val before = trigger("audit_orders()")
        val after = trigger("audit_orders_v2()")
        val current = SchemaDefinition(name = "App", version = "1", triggers = mapOf("audit_log" to before))
        val desired = SchemaDefinition(name = "App", version = "1", triggers = mapOf("audit_log" to after))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff, triggerPlanningContext = context)
        val op = plan.operations.single { it is DiffOperation.ReplaceTrigger }
        return op.shouldBeInstanceOf<DiffOperation.ReplaceTrigger>()
    }

    test("DROP_CREATE_FALLBACK mode marks ReplaceTrigger up + down with hasGap = true") {
        val op = replaceTriggerPlan(TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK))
        op.risks.up.hasGap shouldBe true
        op.risks.down!!.hasGap shouldBe true
    }

    test("NATIVE_REPLACE mode leaves hasGap = false on both directions") {
        val op = replaceTriggerPlan(TriggerPlanningContext(TriggerReplaceMode.NATIVE_REPLACE))
        op.risks.up.hasGap shouldBe false
        op.risks.down!!.hasGap shouldBe false
    }

    test("default planning context is DROP_CREATE_FALLBACK (conservative)") {
        TriggerPlanningContext().replaceMode shouldBe TriggerReplaceMode.DROP_CREATE_FALLBACK
        // and the default-planner path mirrors it:
        val op = replaceTriggerPlan(TriggerPlanningContext())
        op.risks.up.hasGap shouldBe true
    }

    test("CreateTrigger does not pick up hasGap regardless of mode") {
        val current = SchemaDefinition(name = "App", version = "1")
        val desired = SchemaDefinition(name = "App", version = "1", triggers = mapOf("audit_log" to trigger("audit_orders()")))
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(
            current, desired, diff,
            triggerPlanningContext = TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK),
        )
        val create = plan.operations.single { it is DiffOperation.CreateTrigger } as DiffOperation.CreateTrigger
        create.risks.up.hasGap shouldBe false
    }

    test("DropTrigger does not pick up hasGap regardless of mode") {
        val current = SchemaDefinition(name = "App", version = "1", triggers = mapOf("audit_log" to trigger("audit_orders()")))
        val desired = SchemaDefinition(name = "App", version = "1")
        val diff = SchemaComparator().compare(current, desired)
        val plan = planner.plan(
            current, desired, diff,
            triggerPlanningContext = TriggerPlanningContext(TriggerReplaceMode.DROP_CREATE_FALLBACK),
        )
        val drop = plan.operations.single { it is DiffOperation.DropTrigger } as DiffOperation.DropTrigger
        // Drop's destructive up-risk is unchanged; hasGap stays false for single-statement ops.
        drop.risks.up.hasGap shouldBe false
    }
})
