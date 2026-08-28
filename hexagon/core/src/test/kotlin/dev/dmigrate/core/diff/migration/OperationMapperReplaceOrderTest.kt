package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Ein geändertes Objekt wird als Löschen + Anlegen abgebildet — bei Indizes,
 * Constraints und dem Primärschlüssel gleichermaßen. Alle drei Paare stehen in
 * derselben Phase und tragen denselben Objektnamen, und `stableOrder` bricht den
 * Gleichstand über die Operations-ID. Die ID beginnt mit der Operationsart, also
 * steht `Add…` lexikografisch **immer** vor `Drop…`: ohne Abhängigkeitskante
 * kommt das Anlegen zuerst, deterministisch.
 *
 * Aufgefallen ist es an einem Index gegen echtes SQL Server: `CREATE` lief auf
 * einen Namen, den der nachfolgende `DROP` erst noch freigeben sollte — Msg 1913,
 * Transaktion zurückgerollt, Exit 5, am Ziel unverändert. Für Primärschlüssel und
 * Constraints ist es derselbe Fehler mit anderer Nummer: Msg 1779 / 2714 auf
 * SQL Server, 42P16 / 42710 auf PostgreSQL.
 */
class OperationMapperReplaceOrderTest : FunSpec({

    val comparator = SchemaComparator()
    val planner = DiffPlanner()

    fun schema(index: IndexDefinition) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf("t" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "label" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
            indices = listOf(index),
        )),
    )

    val before = IndexDefinition("ix", listOf(IndexColumn("label")))
    val after = before.copy(includeColumns = listOf("id"))

    test("the recreate of a changed index is ordered after its drop") {
        val current = schema(before)
        val desired = schema(after)
        val plan = planner.plan(current, desired, comparator.compare(current, desired))

        val kinds = plan.operations.map { it::class.simpleName }
        val dropAt = kinds.indexOf("DropIndex")
        val addAt = kinds.indexOf("AddIndex")
        (dropAt >= 0) shouldBe true
        (addAt > dropAt) shouldBe true
    }

    test("the ordering is carried by a declared dependency, not by chance") {
        val current = schema(before)
        val desired = schema(after)
        val plan = planner.plan(current, desired, comparator.compare(current, desired))

        val drop = plan.operations.first { it is DiffOperation.DropIndex }
        val add = plan.operations.first { it is DiffOperation.AddIndex }
        // Ohne die Kante haenge die Reihenfolge am Hash der IDs — eine Aenderung
        // an der Payload-Projektion koennte sie still umdrehen.
        add.dependencies shouldBe setOf(drop.id)
    }

    test("the recreate of a changed constraint is ordered after its drop") {
        fun withCheck(expr: String) = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("total" to ColumnDefinition(NeutralType.Integer)),
                constraints = listOf(ConstraintDefinition(
                    name = "ck_total", type = ConstraintType.CHECK, expression = expr,
                )),
            )),
        )
        val current = withCheck("total >= 0")
        val desired = withCheck("total > 0")
        val plan = planner.plan(current, desired, comparator.compare(current, desired))

        val drop = plan.operations.first { it is DiffOperation.DropConstraint }
        val add = plan.operations.first { it is DiffOperation.AddConstraint }
        add.dependencies.contains(drop.id) shouldBe true
        (plan.operations.indexOf(add) > plan.operations.indexOf(drop)) shouldBe true
    }

    test("a reshaped primary key is dropped before it is added") {
        fun withPk(cols: List<String>) = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "tenant" to ColumnDefinition(NeutralType.Integer, required = true),
                ),
                primaryKey = cols,
            )),
        )
        val current = withPk(listOf("id"))
        val desired = withPk(listOf("id", "tenant"))
        val plan = planner.plan(current, desired, comparator.compare(current, desired))

        val drop = plan.operations.first { it is DiffOperation.DropPrimaryKey }
        val add = plan.operations.first { it is DiffOperation.AddPrimaryKey }
        add.dependencies.contains(drop.id) shouldBe true
        (plan.operations.indexOf(add) > plan.operations.indexOf(drop)) shouldBe true
    }

    test("a table getting its first primary key waits for nothing") {
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            )),
        )
        val desired = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                primaryKey = listOf("id"),
            )),
        )
        val plan = planner.plan(current, desired, comparator.compare(current, desired))
        plan.operations.first { it is DiffOperation.AddPrimaryKey }.dependencies shouldBe emptySet()
    }

    test("an index that is only added carries no dependency") {
        val current = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "label" to ColumnDefinition(NeutralType.Text()),
                ),
                primaryKey = listOf("id"),
            )),
        )
        val desired = schema(before)
        val plan = planner.plan(current, desired, comparator.compare(current, desired))
        plan.operations.first { it is DiffOperation.AddIndex }.dependencies shouldBe emptySet()
    }
})
