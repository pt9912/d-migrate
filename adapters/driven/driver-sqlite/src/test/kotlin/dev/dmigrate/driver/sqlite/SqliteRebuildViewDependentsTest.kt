package dev.dmigrate.driver.sqlite

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
})
