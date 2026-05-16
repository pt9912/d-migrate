package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice D.1 pins for the cross-object
 * dependency analyzer. The tests construct flat DiffOperation
 * lists directly (bypassing the planner / mapper) so each
 * edge-rule is exercised in isolation.
 */
class RoutineDependencyAnalyzerTest : FunSpec({

    fun ref(type: DiffObjectType, name: String) = DiffObjectRef(type = type, path = listOf(name))

    val emptyFn = FunctionDefinition(body = "BEGIN RETURN 1; END")
    val emptyProc = ProcedureDefinition(body = "BEGIN END")
    fun trigger(table: String, body: String = "BEGIN END") = TriggerDefinition(
        table = table,
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.BEFORE,
        body = body,
    )

    test("CreateView with dependencies.tables adds edge to CreateTable") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val createView = DiffOperation.CreateView(
            id = "create-v",
            objectRef = ref(DiffObjectType.VIEW, "v"),
            view = ViewDefinition(
                query = "SELECT id FROM orders",
                dependencies = DependencyInfo(tables = listOf("orders")),
            ),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, createView))
        val attached = result.operations.single { it.id == "create-v" }
        attached.dependencies shouldBe setOf("create-orders")
        result.unsafePairs.shouldBeEmpty()
    }

    test("CreateFunction with dependencies.tables adds edge to CreateTable") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val createFn = DiffOperation.CreateFunction(
            id = "create-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "compute_total"),
            function = emptyFn.copy(dependencies = DependencyInfo(tables = listOf("orders"))),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, createFn))
        result.operations.single { it.id == "create-fn" }.dependencies shouldBe setOf("create-orders")
    }

    test("CreateFunction with dependencies.sequences adds edge to CreateSequence") {
        val createSeq = DiffOperation.CreateSequence(
            id = "create-seq",
            objectRef = ref(DiffObjectType.SEQUENCE, "order_seq"),
            sequence = dev.dmigrate.core.model.SequenceDefinition(start = 1),
        )
        val createFn = DiffOperation.CreateFunction(
            id = "create-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "next_id"),
            function = emptyFn.copy(dependencies = DependencyInfo(sequences = listOf("order_seq"))),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createSeq, createFn))
        result.operations.single { it.id == "create-fn" }.dependencies shouldBe setOf("create-seq")
    }

    test("ReplaceProcedure picks up after-side dependencies, not before") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val before = emptyProc.copy(dependencies = DependencyInfo(tables = listOf("legacy")))
        val after = emptyProc.copy(dependencies = DependencyInfo(tables = listOf("orders")))
        val replace = DiffOperation.ReplaceProcedure(
            id = "replace-p",
            objectRef = ref(DiffObjectType.PROCEDURE, "p"),
            before = before,
            after = after,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, replace))
        result.operations.single { it.id == "replace-p" }.dependencies shouldBe setOf("create-orders")
    }

    test("CreateTrigger always edges to its owning CreateTable via trigger.table") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val createTrigger = DiffOperation.CreateTrigger(
            id = "create-t",
            objectRef = ref(DiffObjectType.TRIGGER, "audit_t"),
            trigger = trigger(table = "orders"),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, createTrigger))
        result.operations.single { it.id == "create-t" }.dependencies shouldBe setOf("create-orders")
    }

    test("DropTrigger drops before its referenced DropFunction (reverse-topo edge)") {
        // The trigger references a function in its DependencyInfo;
        // the function's drop must wait for the trigger's drop.
        val dropTrigger = DiffOperation.DropTrigger(
            id = "drop-t",
            objectRef = ref(DiffObjectType.TRIGGER, "audit_t"),
            trigger = trigger(table = "orders").copy(
                dependencies = DependencyInfo(functions = listOf("audit_fn")),
            ),
        )
        val dropFn = DiffOperation.DropFunction(
            id = "drop-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "audit_fn"),
            function = emptyFn,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(dropTrigger, dropFn))
        // dropFn must depend on dropTrigger so the trigger is gone
        // before the function it referenced.
        result.operations.single { it.id == "drop-fn" }.dependencies shouldBe setOf("drop-t")
    }

    test("DropView drops before its referenced DropTable (reverse-topo edge)") {
        val dropView = DiffOperation.DropView(
            id = "drop-v",
            objectRef = ref(DiffObjectType.VIEW, "v"),
            view = ViewDefinition(
                query = "SELECT 1",
                dependencies = DependencyInfo(tables = listOf("orders")),
            ),
        )
        val dropTable = DiffOperation.DropTable(
            id = "drop-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(dropView, dropTable))
        result.operations.single { it.id == "drop-orders" }.dependencies shouldBe setOf("drop-v")
    }

    test("Routine ↔ Routine without manifest edge surfaces UNSAFE_DEPENDENCY_PAIR") {
        // Two co-resident routines, neither's `dependencies` lists
        // the other → unsafe-pair finding.
        val createFnA = DiffOperation.CreateFunction(
            id = "create-a",
            objectRef = ref(DiffObjectType.FUNCTION, "a"),
            function = emptyFn,
        )
        val createFnB = DiffOperation.CreateFunction(
            id = "create-b",
            objectRef = ref(DiffObjectType.FUNCTION, "b"),
            function = emptyFn,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createFnA, createFnB))
        result.unsafePairs.size shouldBe 1
        val pair = result.unsafePairs.single()
        pair.first.displayName shouldBe "a"
        pair.second.displayName shouldBe "b"
    }

    test("Routine ↔ Routine with manifest edge in one direction is SAFE") {
        // `b` declares it depends on `a` → safe.
        val createFnA = DiffOperation.CreateFunction(
            id = "create-a",
            objectRef = ref(DiffObjectType.FUNCTION, "a"),
            function = emptyFn,
        )
        val createFnB = DiffOperation.CreateFunction(
            id = "create-b",
            objectRef = ref(DiffObjectType.FUNCTION, "b"),
            function = emptyFn.copy(dependencies = DependencyInfo(functions = listOf("a"))),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createFnA, createFnB))
        result.unsafePairs.shouldBeEmpty()
        result.operations.single { it.id == "create-b" }.dependencies shouldBe setOf("create-a")
    }

    test("Single routine in the plan never triggers unsafe-pair detection") {
        val createFn = DiffOperation.CreateFunction(
            id = "create-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "f"),
            function = emptyFn,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createFn))
        result.unsafePairs.shouldBeEmpty()
    }

    test("Existing dependencies are preserved (additive contract)") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val createFn = DiffOperation.CreateFunction(
            id = "create-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "f"),
            function = emptyFn.copy(dependencies = DependencyInfo(tables = listOf("orders"))),
            dependencies = setOf("preexisting-edge"),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, createFn))
        val edges = result.operations.single { it.id == "create-fn" }.dependencies
        edges shouldBe setOf("preexisting-edge", "create-orders")
    }
})
