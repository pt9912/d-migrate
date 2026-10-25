package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Issue #50: was ein Neubau an Sichten und Triggern mitnimmt, ohne dass `dependencies` es nennt. */
class SqliteRebuildViewDependentsTest : FunSpec({

    fun table(nullable: Boolean) = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "n" to ColumnDefinition(NeutralType.Text(), required = !nullable),
        ),
        primaryKey = listOf("id"),
    )

    fun insteadOf(view: String) = TriggerDefinition(
        table = view,
        event = TriggerEvent.INSERT,
        timing = TriggerTiming.INSTEAD_OF,
        forEach = TriggerForEach.ROW,
        body = "SELECT 1;",
    )

    fun plan(source: SchemaDefinition, target: SchemaDefinition) = SqliteRebuildPlanner.planRebuild(
        table = "t",
        bucket = emptyList(),
        source = source.tables.getValue("t"),
        target = target.tables.getValue("t"),
        bucketRisk = OperationRisk.SAFE,
        sql = SqliteDiffSqlBuilders(),
        sourceSchema = source,
        targetSchema = target,
    )

    test("eine Sicht, die nur das Soll kennt, wird vor dem Anlegen geloescht (zweiter Eimer)") {
        val source = SchemaDefinition(name = "S", version = "1", tables = mapOf("t" to table(false)))
        val target = SchemaDefinition(
            name = "S", version = "1", tables = mapOf("t" to table(true)),
            views = mapOf("v_new" to ViewDefinition(query = "SELECT id FROM t")),
        )
        val p = plan(source, target)
        p.dependentViewsToDrop.map { it.name } shouldBe listOf("v_new")
        p.dependentViewsToRecreate.map { it.name } shouldBe listOf("v_new")
    }

    test("ein INSTEAD-OF-Trigger auf einer mitgenommenen Sicht wird mitgenommen") {
        val schema = { nullable: Boolean ->
            SchemaDefinition(
                name = "S", version = "1", tables = mapOf("t" to table(nullable)),
                views = mapOf("v_t" to ViewDefinition(query = "SELECT id, n FROM t")),
                triggers = mapOf("v_t::trg" to insteadOf("v_t")),
            )
        }
        val p = plan(schema(false), schema(true))
        p.dependentTriggersToDrop.map { it.name } shouldBe listOf("trg")
        p.dependentTriggersToRecreate.map { it.name } shouldBe listOf("trg")
    }

    // classify(): Operationen an einer Sicht, die nur der Text an die Neubau-Tabelle bindet.
    val rebuildOp = DiffOperation.AlterColumnNullability(
        id = "alter-1",
        objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("t", "n")),
        before = false,
        after = true,
    )
    val viewRef = DiffObjectRef(DiffObjectType.VIEW, listOf("v_t"))
    val triggerRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("v_t::trg"))
    val noDeps = ViewDefinition(query = "SELECT id, n FROM t")
    val withView = SchemaDefinition(
        name = "S", version = "1", tables = mapOf("t" to table(false)),
        views = mapOf("v_t" to noDeps, "v_other" to ViewDefinition(query = "SELECT 1")),
    )

    val viewAndTriggerOps = mapOf(
        "ReplaceView" to DiffOperation.ReplaceView("op", viewRef, noDeps, noDeps.copy(query = "SELECT id FROM t")),
        "DropView" to DiffOperation.DropView("op", viewRef, noDeps),
        "CreateView" to DiffOperation.CreateView("op", viewRef, noDeps),
        "CreateTrigger" to DiffOperation.CreateTrigger("op", triggerRef, insteadOf("v_t")),
        "DropTrigger" to DiffOperation.DropTrigger("op", triggerRef, insteadOf("v_t")),
        "ReplaceTrigger" to DiffOperation.ReplaceTrigger("op", triggerRef, insteadOf("v_t"), insteadOf("v_t")),
    )
    for ((name, op) in viewAndTriggerOps) {
        test("classify(): $name an einer abgeleiteten Sicht faellt in den Eimer der Tabelle") {
            val c = SqliteRebuildPlanner.classify(listOf(rebuildOp, op), current = withView, desired = withView)
            c.rebuildBuckets.getValue("t").map { it.id } shouldBe listOf("alter-1", "op")
            c.simpleOps shouldBe emptyList()
        }
        test("classify(): $name ohne Schemas bleibt eine einfache Operation (altes Verhalten)") {
            val c = SqliteRebuildPlanner.classify(listOf(rebuildOp, op))
            c.simpleOps.map { it.id } shouldBe listOf("op")
        }
    }

    test("classify(): eine Sicht, die die Tabelle nicht nennt, bleibt eine einfache Operation") {
        val other = DiffOperation.DropView("op", DiffObjectRef(DiffObjectType.VIEW, listOf("v_other")), withView.views.getValue("v_other"))
        val c = SqliteRebuildPlanner.classify(listOf(rebuildOp, other), current = withView, desired = withView)
        c.rebuildBuckets.getValue("t").map { it.id } shouldBe listOf("alter-1")
        c.simpleOps.map { it.id } shouldBe listOf("op")
    }

    test("classify(): eine Sicht nur im Ist (DropView) oder nur im Soll (CreateView) gehoert in den Eimer") {
        val empty = SchemaDefinition(name = "S", version = "1", tables = mapOf("t" to table(false)))
        val drop = DiffOperation.DropView("op", viewRef, noDeps)
        val create = DiffOperation.CreateView("op", viewRef, noDeps)
        SqliteRebuildPlanner.classify(listOf(rebuildOp, drop), current = withView, desired = empty)
            .rebuildBuckets.getValue("t").map { it.id } shouldBe listOf("alter-1", "op")
        SqliteRebuildPlanner.classify(listOf(rebuildOp, create), current = empty, desired = withView)
            .rebuildBuckets.getValue("t").map { it.id } shouldBe listOf("alter-1", "op")
    }
})
