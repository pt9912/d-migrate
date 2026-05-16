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

    // ── Coverage gaps caught in the D.1 post-commit review ──

    test("CreateProcedure with dependencies.tables adds edge to CreateTable") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val createProc = DiffOperation.CreateProcedure(
            id = "create-p",
            objectRef = ref(DiffObjectType.PROCEDURE, "p"),
            procedure = emptyProc.copy(dependencies = DependencyInfo(tables = listOf("orders"))),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, createProc))
        result.operations.single { it.id == "create-p" }.dependencies shouldBe setOf("create-orders")
    }

    test("ReplaceFunction picks up after-side dependencies") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val before = emptyFn.copy(dependencies = DependencyInfo(tables = listOf("legacy")))
        val after = emptyFn.copy(dependencies = DependencyInfo(tables = listOf("orders")))
        val replace = DiffOperation.ReplaceFunction(
            id = "replace-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "f"),
            before = before,
            after = after,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, replace))
        result.operations.single { it.id == "replace-fn" }.dependencies shouldBe setOf("create-orders")
    }

    test("ReplaceView reads after-side dependencies") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val before = ViewDefinition(query = "SELECT 1", dependencies = DependencyInfo(tables = listOf("legacy")))
        val after = ViewDefinition(query = "SELECT * FROM orders", dependencies = DependencyInfo(tables = listOf("orders")))
        val replace = DiffOperation.ReplaceView(
            id = "replace-v",
            objectRef = ref(DiffObjectType.VIEW, "v"),
            before = before,
            after = after,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, replace))
        result.operations.single { it.id == "replace-v" }.dependencies shouldBe setOf("create-orders")
    }

    test("ReplaceTrigger picks up the new owning table and after-side function deps") {
        val createTable = DiffOperation.CreateTable(
            id = "create-orders",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            table = dev.dmigrate.core.model.TableDefinition(),
        )
        val createFn = DiffOperation.CreateFunction(
            id = "create-audit",
            objectRef = ref(DiffObjectType.FUNCTION, "audit_fn"),
            function = emptyFn,
        )
        val before = trigger(table = "legacy")
        val after = trigger(table = "orders").copy(
            dependencies = DependencyInfo(functions = listOf("audit_fn")),
        )
        val replace = DiffOperation.ReplaceTrigger(
            id = "replace-t",
            objectRef = ref(DiffObjectType.TRIGGER, "audit_t"),
            before = before,
            after = after,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createTable, createFn, replace))
        result.operations.single { it.id == "replace-t" }.dependencies shouldBe
            setOf("create-orders", "create-audit")
    }

    test("CreateView with dependencies.functions adds edge to CreateFunction") {
        val createFn = DiffOperation.CreateFunction(
            id = "create-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "is_active"),
            function = emptyFn,
        )
        val createView = DiffOperation.CreateView(
            id = "create-v",
            objectRef = ref(DiffObjectType.VIEW, "active_view"),
            view = ViewDefinition(
                query = "SELECT id FROM users WHERE is_active(id)",
                dependencies = DependencyInfo(functions = listOf("is_active")),
            ),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createFn, createView))
        result.operations.single { it.id == "create-v" }.dependencies shouldBe setOf("create-fn")
    }

    test("CreateView with dependencies.sequences adds edge to CreateSequence") {
        val createSeq = DiffOperation.CreateSequence(
            id = "create-seq",
            objectRef = ref(DiffObjectType.SEQUENCE, "row_seq"),
            sequence = dev.dmigrate.core.model.SequenceDefinition(start = 1),
        )
        val createView = DiffOperation.CreateView(
            id = "create-v",
            objectRef = ref(DiffObjectType.VIEW, "v"),
            view = ViewDefinition(
                query = "SELECT nextval('row_seq')",
                dependencies = DependencyInfo(sequences = listOf("row_seq")),
            ),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(createSeq, createView))
        result.operations.single { it.id == "create-v" }.dependencies shouldBe setOf("create-seq")
    }

    test("CreateView with dependencies.views adds edge to the referenced CreateView") {
        val baseView = DiffOperation.CreateView(
            id = "create-base",
            objectRef = ref(DiffObjectType.VIEW, "base"),
            view = ViewDefinition(query = "SELECT 1"),
        )
        val chainedView = DiffOperation.CreateView(
            id = "create-chained",
            objectRef = ref(DiffObjectType.VIEW, "chained"),
            view = ViewDefinition(
                query = "SELECT * FROM base",
                dependencies = DependencyInfo(views = listOf("base")),
            ),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(baseView, chainedView))
        result.operations.single { it.id == "create-chained" }.dependencies shouldBe setOf("create-base")
    }

    test("DropProcedure depends on DropTrigger that referenced it (reverse-topo edge)") {
        val dropTrigger = DiffOperation.DropTrigger(
            id = "drop-t",
            objectRef = ref(DiffObjectType.TRIGGER, "audit_t"),
            trigger = trigger(table = "orders").copy(
                dependencies = DependencyInfo(functions = listOf("audit_proc")),
            ),
        )
        val dropProc = DiffOperation.DropProcedure(
            id = "drop-p",
            objectRef = ref(DiffObjectType.PROCEDURE, "audit_proc"),
            procedure = emptyProc,
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(dropTrigger, dropProc))
        result.operations.single { it.id == "drop-p" }.dependencies shouldBe setOf("drop-t")
    }

    test("DropSequence depends on the Drop that referenced it") {
        val dropFn = DiffOperation.DropFunction(
            id = "drop-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "next_id"),
            function = emptyFn.copy(dependencies = DependencyInfo(sequences = listOf("row_seq"))),
        )
        val dropSeq = DiffOperation.DropSequence(
            id = "drop-seq",
            objectRef = ref(DiffObjectType.SEQUENCE, "row_seq"),
            sequence = dev.dmigrate.core.model.SequenceDefinition(start = 1),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(dropFn, dropSeq))
        result.operations.single { it.id == "drop-seq" }.dependencies shouldBe setOf("drop-fn")
    }

    test("RenameTable target name is a valid edge target for routine/view deps") {
        val renameTable = DiffOperation.RenameTable(
            id = "rename",
            objectRef = ref(DiffObjectType.TABLE, "orders"),
            fromName = "old_orders",
            toName = "orders",
            overlaySource = "test",
            overlayEntryId = "rename-orders",
            overlayHash = null,
        )
        val createFn = DiffOperation.CreateFunction(
            id = "create-fn",
            objectRef = ref(DiffObjectType.FUNCTION, "f"),
            function = emptyFn.copy(dependencies = DependencyInfo(tables = listOf("orders"))),
        )
        val result = RoutineDependencyAnalyzer.attach(listOf(renameTable, createFn))
        result.operations.single { it.id == "create-fn" }.dependencies shouldBe setOf("rename")
    }
})
